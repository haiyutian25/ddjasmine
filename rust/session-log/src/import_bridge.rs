//! One-way import bridge from upstream session-log JSONL into this engine's
//! canonical in-memory representation.
//!
//! Purpose: validating this engine's projection and recovery against real
//! upstream session logs (the workspace's snapshot fixtures). It is a validation
//! surface, not a product feature — ANDROID-PLAN §0 promises no on-disk
//! compatibility with upstream, so nothing here feeds the persistence path.
//!
//! Translation rules (upstream → this engine):
//! - Header `{"type":"session","version",...}` maps onto [`SessionHeader`]
//!   with the upstream `version` checked against [`FORMAT_VERSION`].
//! - Packed storage rows (`text-chunks` / `reasoning-chunks` /
//!   `tool-call-chunks`, upstream chunk-rows.ts) expand back into their exact
//!   `assistant/chunk` events: member `k` has seq `seq0 + k` and time
//!   `time0` plus the first `k` `dt` gaps.
//! - Envelope surface metadata folds into the payload: `surfaceOp:"append"`
//!   drops (this engine's default); `{op:"replace",start,end}` becomes the
//!   payload `surfaceOp:"replace"` + `sourceEventSeqs` citing exactly the
//!   live surface nodes the upstream range shadows — computed by folding the
//!   surface during import, mirroring upstream's `foldSurface` splice, so a
//!   range whose endpoints sit at non-contiguous seqs after earlier
//!   replacements still cites the right nodes.
//! - Payloads of the closed-core event types translate into this engine's
//!   canonical shapes (upstream nests messages, uses content-block arrays, and
//!   spells `callId`): text blocks render joined into `content`, tool-call
//!   blocks collect into `tool_calls`, and call ids normalize to
//!   `tool_call_id`. A upstream assistant message whose content block array is
//!   empty (a usage-only record) keeps no `content` key, so this engine's
//!   projection skips it exactly like upstream's `deriveEventMessage`.
//! - Every other event type passes through with its payload verbatim; the
//!   bridge does not enforce upstream's `ignorable`-marker refusal semantics —
//!   unknown event types stay inert in this engine's projection.

use serde_json::{json, Value};

use crate::{LogError, SessionEvent, SessionHeader, SessionLog, FORMAT_VERSION};

/// upstream storage-row tags that expand into `assistant/chunk` runs.
const PACKED_ROW_TAGS: [&str; 3] = ["text-chunks", "reasoning-chunks", "tool-call-chunks"];

/// upstream event types eligible to carry surface metadata (upstream surface.ts).
const SURFACE_EVENT_TYPES: [&str; 3] = ["user/message", "assistant/message", "tool/result"];

/// upstream event types whose payloads translate into this engine's canonical
/// shapes; `tool/call` joins the surface types so crash-recovery pairing
/// sees the normalized `tool_call_id`.
const TRANSLATED_EVENT_TYPES: [&str; 4] = [
    "user/message",
    "assistant/message",
    "tool/call",
    "tool/result",
];

/// Imports a complete upstream session-log JSONL document into an in-memory log.
///
/// The document must start with a upstream `session` header line whose `version`
/// this build supports; every later line is an event or a packed chunk row,
/// and seqs must stay contiguous from zero (packed rows contribute their
/// member seqs). The result is an in-memory log: projection and recovery
/// run on it exactly as on native logs, but nothing is persisted.
///
/// # Errors
/// Returns [`LogError::MissingHeader`] for an empty document,
/// [`LogError::UnsupportedFormatVersion`] for a foreign upstream version,
/// [`LogError::SeqDiscontinuity`] for a seq gap, and
/// [`LogError::MalformedRecord`] for any structurally invalid line
/// (bad UTF-8 or JSON, missing fields, a replace whose endpoints are not
/// live surface nodes, or surface metadata on an ineligible event).
pub fn import_jsonl(bytes: &[u8]) -> Result<std::sync::Arc<SessionLog>, LogError> {
    let body = bytes.strip_suffix(b"\n").unwrap_or(bytes);
    if body.is_empty() {
        return Err(LogError::MissingHeader);
    }
    let mut header: Option<SessionHeader> = None;
    let mut events: Vec<SessionEvent> = Vec::new();
    let mut surface: Vec<u64> = Vec::new();
    for (index, raw_line) in body.split(|&b| b == b'\n').enumerate() {
        let line_no = index + 1;
        let line = raw_line.strip_suffix(b"\r").unwrap_or(raw_line);
        let text = std::str::from_utf8(line)
            .map_err(|e| malformed(line_no, format!("invalid UTF-8: {e}")))?;
        if text.trim().is_empty() {
            return Err(malformed(line_no, "blank line"));
        }
        let value: Value =
            serde_json::from_str(text).map_err(|e| malformed(line_no, e.to_string()))?;
        match header {
            None => header = Some(parse_header(&value, line_no)?),
            Some(_) => import_record(&value, &mut events, &mut surface, line_no)?,
        }
    }
    let header = header.ok_or(LogError::MissingHeader)?;
    Ok(SessionLog::from_parts(header, events))
}

/// Parses the upstream `session` header line into a [`SessionHeader`].
fn parse_header(value: &Value, line: usize) -> Result<SessionHeader, LogError> {
    if value.get("type").and_then(Value::as_str) != Some("session") {
        return Err(malformed(
            line,
            "first record is not a upstream session header",
        ));
    }
    let version = value
        .get("version")
        .and_then(Value::as_u64)
        .ok_or_else(|| malformed(line, "header lacks version"))?;
    if version != u64::from(FORMAT_VERSION) {
        return Err(LogError::UnsupportedFormatVersion {
            found: version,
            supported: FORMAT_VERSION,
        });
    }
    let session_id = value
        .get("id")
        .and_then(Value::as_str)
        .ok_or_else(|| malformed(line, "header lacks id"))?
        .to_string();
    let created_at_ms = value.get("createdAt").and_then(Value::as_i64).unwrap_or(0);
    Ok(SessionHeader {
        format_version: FORMAT_VERSION,
        session_id,
        created_at_ms,
    })
}

/// Imports one non-header record: a packed row expands, an event translates.
fn import_record(
    value: &Value,
    events: &mut Vec<SessionEvent>,
    surface: &mut Vec<u64>,
    line: usize,
) -> Result<(), LogError> {
    let tag = value
        .get("type")
        .and_then(Value::as_str)
        .ok_or_else(|| malformed(line, "record lacks type"))?;
    if PACKED_ROW_TAGS.contains(&tag) {
        return expand_packed_row(value, tag, events, line);
    }
    let seq = expect_u64(value.get("seq"), line, "event lacks seq")?;
    let time_ms = value
        .get("time")
        .and_then(Value::as_i64)
        .ok_or_else(|| malformed(line, "event lacks time"))?;
    let data = value
        .get("data")
        .cloned()
        .ok_or_else(|| malformed(line, "event lacks data"))?;
    let expected = events.len() as u64;
    if seq != expected {
        return Err(LogError::SeqDiscontinuity {
            index: events.len(),
            expected,
            found: seq,
        });
    }
    let payload = if SURFACE_EVENT_TYPES.contains(&tag) {
        translate_surface_event(tag, &data, value, surface, seq, line)?
    } else {
        if value.get("surfaceOp").is_some() || value.get("sourceEventSeqs").is_some() {
            return Err(malformed(
                line,
                format!("{tag} is not surface-eligible and cannot carry surface metadata"),
            ));
        }
        if TRANSLATED_EVENT_TYPES.contains(&tag) {
            canonical_payload(tag, &data, line)?
        } else {
            data
        }
    };
    events.push(SessionEvent {
        seq,
        event_type: tag.to_string(),
        time_ms,
        payload,
    });
    Ok(())
}

/// Translates one surface event's payload and folds its surface operation.
///
/// Appends push the event's seq onto the live surface; replaces splice the
/// shadowed positional range with the event's seq, exactly like upstream's
/// `foldSurface`, and record the shadowed seqs as the payload's
/// `sourceEventSeqs` citation.
fn translate_surface_event(
    tag: &str,
    data: &Value,
    envelope: &Value,
    surface: &mut Vec<u64>,
    seq: u64,
    line: usize,
) -> Result<Value, LogError> {
    let mut payload = canonical_payload(tag, data, line)?;
    let op = envelope
        .get("surfaceOp")
        .ok_or_else(|| malformed(line, format!("{tag} requires surfaceOp")))?;
    match op {
        Value::String(s) if s == "append" => {
            surface.push(seq);
            Ok(payload)
        }
        Value::Object(fields) => {
            if fields.get("op").and_then(Value::as_str) != Some("replace") {
                return Err(malformed(line, "surfaceOp object must carry op: replace"));
            }
            let start = expect_u64(fields.get("start"), line, "replace surfaceOp lacks start")?;
            let end = expect_u64(fields.get("end"), line, "replace surfaceOp lacks end")?;
            let start_idx = surface.iter().position(|&s| s == start).ok_or_else(|| {
                malformed(line, format!("replace start {start} not found in surface"))
            })?;
            let end_idx = surface.iter().position(|&s| s == end).ok_or_else(|| {
                malformed(line, format!("replace end {end} not found in surface"))
            })?;
            if start_idx > end_idx {
                return Err(malformed(
                    line,
                    "replace start sits after end in surface order",
                ));
            }
            let shadowed: Vec<u64> = surface[start_idx..=end_idx].to_vec();
            match &mut payload {
                Value::Object(map) => {
                    map.insert("surfaceOp".into(), Value::String("replace".into()));
                    map.insert("sourceEventSeqs".into(), json!(shadowed));
                }
                // canonical_payload returns an object for every surface type.
                _ => unreachable!("canonical payload of a surface event is an object"),
            }
            surface.splice(start_idx..=end_idx, [seq]);
            Ok(payload)
        }
        _ => Err(malformed(line, "invalid surfaceOp")),
    }
}

/// Translates a upstream event payload into this engine's canonical payload.
fn canonical_payload(tag: &str, data: &Value, line: usize) -> Result<Value, LogError> {
    match tag {
        "user/message" => Ok(json!({ "content": render_text(data.get("content")) })),
        "assistant/message" => {
            let message = data
                .get("message")
                .ok_or_else(|| malformed(line, "assistant/message lacks message"))?;
            let blocks = message.get("content");
            // A upstream assistant message with an empty block array exists only
            // to host usage; keep no content key so projection skips it.
            let is_empty = matches!(blocks, Some(Value::Array(items)) if items.is_empty());
            if is_empty {
                return Ok(json!({}));
            }
            let mut payload = json!({
                "content": render_text(blocks),
                "tool_calls": tool_calls_of(blocks),
            });
            if data.get("interrupted") == Some(&Value::Bool(true)) {
                if let Value::Object(map) = &mut payload {
                    map.insert("interrupted".into(), Value::Bool(true));
                }
            }
            Ok(payload)
        }
        "tool/call" => {
            let call_id = data
                .get("callId")
                .and_then(Value::as_str)
                .ok_or_else(|| malformed(line, "tool/call lacks callId"))?;
            let name = data
                .get("name")
                .and_then(Value::as_str)
                .ok_or_else(|| malformed(line, "tool/call lacks name"))?;
            Ok(json!({
                "tool_call_id": call_id,
                "name": name,
                "arguments": data.get("arguments").cloned().unwrap_or(Value::Null),
            }))
        }
        "tool/result" => {
            let message = data
                .get("message")
                .ok_or_else(|| malformed(line, "tool/result lacks message"))?;
            let block = message
                .get("content")
                .and_then(Value::as_array)
                .and_then(|blocks| blocks.first())
                .ok_or_else(|| malformed(line, "tool/result message lacks content"))?;
            let call_id = block
                .get("toolCallId")
                .and_then(Value::as_str)
                .or_else(|| message.pointer("/source/callId").and_then(Value::as_str))
                .ok_or_else(|| malformed(line, "tool/result lacks toolCallId"))?;
            Ok(json!({
                "tool_call_id": call_id,
                "content": render_text(block.get("content")),
            }))
        }
        _ => Ok(data.clone()),
    }
}

/// Expands one packed chunk row into its exact `assistant/chunk` events.
fn expand_packed_row(
    value: &Value,
    tag: &str,
    events: &mut Vec<SessionEvent>,
    line: usize,
) -> Result<(), LogError> {
    let seq0 = expect_u64(value.get("seq0"), line, "packed row lacks seq0")?;
    let time0 = value
        .get("time0")
        .and_then(Value::as_i64)
        .ok_or_else(|| malformed(line, "packed row lacks time0"))?;
    let data = value
        .get("data")
        .ok_or_else(|| malformed(line, "packed row lacks data"))?;
    let turn = data.get("turn").cloned().unwrap_or(Value::Null);
    let step = data.get("step").cloned().unwrap_or(Value::Null);
    let index = expect_u64(data.get("index"), line, "packed row lacks index")?;
    let dt: Vec<i64> = data
        .get("dt")
        .and_then(Value::as_array)
        .ok_or_else(|| malformed(line, "packed row lacks dt"))?
        .iter()
        .map(|gap| {
            gap.as_i64()
                .ok_or_else(|| malformed(line, "dt gap is not an integer"))
        })
        .collect::<Result<_, _>>()?;
    let members = if tag == "tool-call-chunks" {
        string_members(data.get("args"), "args", line)?
    } else {
        string_members(data.get("texts"), "texts", line)?
    };
    if members.is_empty() {
        return Err(malformed(line, "packed row has no members"));
    }
    if dt.len() + 1 != members.len() {
        return Err(malformed(
            line,
            format!(
                "dt length {} does not match {} members",
                dt.len(),
                members.len()
            ),
        ));
    }
    let expected = events.len() as u64;
    if seq0 != expected {
        return Err(LogError::SeqDiscontinuity {
            index: events.len(),
            expected,
            found: seq0,
        });
    }
    let call_id = if tag == "tool-call-chunks" {
        Some(
            data.get("id")
                .and_then(Value::as_str)
                .ok_or_else(|| malformed(line, "tool-call-chunks lacks id"))?,
        )
    } else {
        None
    };
    let mut time_ms = time0;
    for (k, member) in members.iter().enumerate() {
        if k > 0 {
            time_ms = time_ms
                .checked_add(dt[k - 1])
                .ok_or_else(|| malformed(line, "member times overflow"))?;
        }
        let chunk = match tag {
            "text-chunks" => json!({ "type": "text-delta", "index": index, "text": member }),
            "reasoning-chunks" => {
                json!({ "type": "reasoning-delta", "index": index, "text": member })
            }
            _ => {
                let mut chunk = json!({
                    "type": "tool-call-delta",
                    "index": index,
                    "id": call_id,
                    "argumentsDelta": member,
                });
                if let Some(name) = data.get("name") {
                    if let Value::Object(map) = &mut chunk {
                        map.insert("name".into(), name.clone());
                    }
                }
                chunk
            }
        };
        events.push(SessionEvent {
            seq: seq0 + k as u64,
            event_type: "assistant/chunk".to_string(),
            time_ms,
            payload: json!({ "turn": turn.clone(), "step": step.clone(), "chunk": chunk }),
        });
    }
    Ok(())
}

/// Renders a upstream content-block array into this engine's string content:
/// text blocks join in order; reasoning, image, tool-call, and tool-result
/// blocks carry no text in this simplified model history.
fn render_text(blocks: Option<&Value>) -> String {
    let Some(Value::Array(items)) = blocks else {
        return String::new();
    };
    let mut out = String::new();
    for item in items {
        if item.get("type").and_then(Value::as_str) == Some("text") {
            if let Some(text) = item.get("text").and_then(Value::as_str) {
                out.push_str(text);
            }
        }
    }
    out
}

/// Collects tool-call blocks into this engine's `tool_calls` array shape.
fn tool_calls_of(blocks: Option<&Value>) -> Vec<Value> {
    let Some(Value::Array(items)) = blocks else {
        return Vec::new();
    };
    items
        .iter()
        .filter(|block| block.get("type").and_then(Value::as_str) == Some("tool-call"))
        .filter_map(|block| {
            Some(json!({
                "id": block.get("id")?.as_str()?,
                "name": block.get("name")?.as_str()?,
                "arguments": block.get("arguments")?.as_str()?,
            }))
        })
        .collect()
}

/// Validates a packed row's string-member array (`texts` or `args`).
fn string_members<'a>(
    value: Option<&'a Value>,
    key: &str,
    line: usize,
) -> Result<Vec<&'a str>, LogError> {
    let Some(Value::Array(items)) = value else {
        return Err(malformed(line, format!("packed row lacks {key}")));
    };
    items
        .iter()
        .map(|item| {
            item.as_str()
                .ok_or_else(|| malformed(line, format!("{key} member is not a string")))
        })
        .collect()
}

fn expect_u64(value: Option<&Value>, line: usize, reason: &str) -> Result<u64, LogError> {
    value
        .and_then(Value::as_u64)
        .ok_or_else(|| malformed(line, reason.to_string()))
}

fn malformed(line: usize, reason: impl Into<String>) -> LogError {
    LogError::MalformedRecord {
        line,
        reason: reason.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Reads one of the workspace's real upstream snapshot fixtures.
    fn fixture(relative: &str) -> Vec<u8> {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../..")
            .join(relative);
        std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read fixture {relative}: {e}"))
    }

    #[test]
    fn packed_chunks_fixture_expands_rows_and_projects_history() {
        let log = import_jsonl(&fixture(
            "examples/acp-agent/tests/snapshots/packed-chunks/session.jsonl",
        ))
        .unwrap();
        let events = log.events();
        // 124 events, seqs contiguous 0..=123 through the packed rows.
        assert_eq!(events.len(), 124);
        assert_eq!(events.last().expect("events exist").seq, 123);

        // The reasoning row at seq0=10 (17 members) reconstructs deltas
        // 10..=26 with the exact token texts.
        for (seq, text) in [(10usize, "The"), (11, " user"), (26, ".")] {
            assert_eq!(events[seq].event_type, "assistant/chunk");
            assert_eq!(events[seq].payload["chunk"]["type"], "reasoning-delta");
            assert_eq!(events[seq].payload["chunk"]["text"], text);
        }
        // dt[0]=0: the second member shares the first member's timestamp.
        assert_eq!(events[10].time_ms, events[11].time_ms);
        // The tool-call row at seq0=28 carries the call identity per delta.
        assert_eq!(events[28].payload["chunk"]["type"], "tool-call-delta");
        assert_eq!(
            events[28].payload["chunk"]["id"],
            "call_00_JliP571Bh0QQ8QExbSPk0080"
        );
        assert_eq!(events[28].payload["chunk"]["name"], "bash");
        assert_eq!(events[52].payload["chunk"]["argumentsDelta"], "}");

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 5);
        assert_eq!(messages[0].role, "user");
        assert_eq!(
            messages[0].content,
            "Use the bash tool to run exactly: echo HELLO. Report the tool result you got back verbatim, then stop."
        );
        assert_eq!(messages[1].role, "user");
        assert!(messages[1].content.starts_with("Current runtime context."));
        // Reasoning blocks render no text; the tool call survives intact.
        assert_eq!(messages[2].role, "assistant");
        assert_eq!(messages[2].content, "");
        assert_eq!(messages[2].tool_calls.len(), 1);
        assert_eq!(
            messages[2].tool_calls[0].id,
            "call_00_JliP571Bh0QQ8QExbSPk0080"
        );
        assert_eq!(messages[2].tool_calls[0].name, "bash");
        assert_eq!(
            messages[2].tool_calls[0].arguments_json,
            "{\"command\": \"echo HELLO\", \"description\": \"Run echo HELLO\"}"
        );
        assert_eq!(messages[3].role, "tool");
        assert_eq!(
            messages[3].tool_call_id.as_deref(),
            Some("call_00_JliP571Bh0QQ8QExbSPk0080")
        );
        assert_eq!(
            messages[3].content,
            "Error: bash is disabled by policy in this session"
        );
        assert_eq!(messages[4].role, "assistant");
        assert!(messages[4].content.starts_with("The tool returned:"));

        // The turn closed durably: recovery is a no-op.
        assert!(log.close_interrupted_turns().unwrap().is_empty());
    }

    #[test]
    fn compaction_fixture_translates_the_replace_op_through_the_surface_fold() {
        let log = import_jsonl(&fixture(
            "examples/headless-agent/tests/snapshots/compaction-recovery/session.jsonl",
        ))
        .unwrap();
        let events = log.events();
        assert_eq!(events.len(), 31);

        // The upstream replace {start:4,end:4} at seq 21 becomes this engine's
        // cited-seqs form naming the shadowed node.
        let replace = &events[21];
        assert_eq!(replace.payload["surfaceOp"], "replace");
        assert_eq!(replace.payload["sourceEventSeqs"], json!([4u64]));

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 4);
        // The compaction checkpoint occupies the replaced user message's slot.
        assert_eq!(messages[0].role, "user");
        assert!(messages[0]
            .content
            .contains("automatically generated checkpoint"));
        assert!(messages[0]
            .content
            .contains("The request established a durable compaction premise."));
        // History outside the replaced range survives.
        assert_eq!(messages[1].role, "assistant");
        assert_eq!(messages[1].tool_calls[0].id, "call_compaction_marker");
        assert_eq!(messages[2].role, "tool");
        assert_eq!(
            messages[2].tool_call_id.as_deref(),
            Some("call_compaction_marker")
        );
        assert_eq!(messages[2].content, "alpha\n");
        assert_eq!(messages[3].role, "assistant");
        assert_eq!(messages[3].content, "COMPACTION RECOVERED");

        // Below the replace boundary the original user message is visible.
        let before = log.derive_messages(21);
        assert_eq!(before.len(), 3);
        assert!(before[0]
            .content
            .starts_with("Establish a durable compaction premise"));
    }

    #[test]
    fn cancel_fixture_projects_the_interrupted_assistant_prefix() {
        let log = import_jsonl(&fixture(
            "examples/acp-agent/tests/snapshots/cancel/session.jsonl",
        ))
        .unwrap();
        assert_eq!(log.events().len(), 14);
        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 3);
        assert_eq!(messages[2].role, "assistant");
        assert_eq!(messages[2].content, "partial");
        // The interrupted-prefix marker rides the payload for diagnostics.
        assert_eq!(
            log.events()[11].payload["interrupted"],
            json!(true),
            "interrupted assistant message keeps its marker"
        );
        // The turn closed durably; recovery is a no-op.
        assert!(log.close_interrupted_turns().unwrap().is_empty());
    }

    #[test]
    fn replace_ops_fold_positions_not_seq_order() {
        let mut doc =
            String::from("{\"type\":\"session\",\"version\":0,\"id\":\"s\",\"createdAt\":7}\n");
        doc.push_str("{\"type\":\"user/message\",\"seq\":0,\"time\":1,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"}]},\"surfaceOp\":\"append\"}\n");
        doc.push_str("{\"type\":\"assistant/message\",\"seq\":1,\"time\":2,\"data\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"b\"}]}},\"surfaceOp\":\"append\"}\n");
        doc.push_str("{\"type\":\"user/message\",\"seq\":2,\"time\":3,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"c\"}]},\"surfaceOp\":\"append\"}\n");
        // Replace the surface positions of seq 0..=1 with seq 3.
        doc.push_str("{\"type\":\"user/message\",\"seq\":3,\"time\":4,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"summary\"}]},\"surfaceOp\":{\"op\":\"replace\",\"start\":0,\"end\":1}}\n");
        // Surface is now [3, 2]; replacing positions 0..=1 cites seqs 3
        // then 2 — descending seq order, ascending surface positions.
        doc.push_str("{\"type\":\"user/message\",\"seq\":4,\"time\":5,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"final\"}]},\"surfaceOp\":{\"op\":\"replace\",\"start\":3,\"end\":2}}\n");

        let log = import_jsonl(doc.as_bytes()).unwrap();
        let events = log.events();
        assert_eq!(events[3].payload["sourceEventSeqs"], json!([0u64, 1u64]));
        assert_eq!(events[4].payload["sourceEventSeqs"], json!([3u64, 2u64]));

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].content, "final");
    }

    #[test]
    fn packed_rows_reconstruct_member_seq_and_times() {
        let doc = concat!(
            "{\"type\":\"session\",\"version\":0,\"id\":\"s\",\"createdAt\":0}\n",
            "{\"type\":\"tool-call-chunks\",\"seq0\":0,\"time0\":100,\"data\":{\"turn\":1,\"step\":1,\"index\":0,\"dt\":[5,7],\"id\":\"c1\",\"name\":\"bash\",\"args\":[\"a\",\"b\",\"c\"]}}\n",
        );
        let log = import_jsonl(doc.as_bytes()).unwrap();
        let events = log.events();
        assert_eq!(events.len(), 3);
        assert_eq!((events[0].seq, events[0].time_ms), (0, 100));
        assert_eq!((events[1].seq, events[1].time_ms), (1, 105));
        assert_eq!((events[2].seq, events[2].time_ms), (2, 112));
        assert_eq!(events[1].payload["chunk"]["argumentsDelta"], "b");
        assert_eq!(events[1].payload["chunk"]["name"], "bash");
    }

    #[test]
    fn empty_content_assistant_messages_do_not_project() {
        let doc = concat!(
            "{\"type\":\"session\",\"version\":0,\"id\":\"s\",\"createdAt\":0}\n",
            "{\"type\":\"user/message\",\"seq\":0,\"time\":1,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"q\"}]},\"surfaceOp\":\"append\"}\n",
            // Usage-only assistant message: empty content array, upstream drops
            // it from derived history.
            "{\"type\":\"assistant/message\",\"seq\":1,\"time\":2,\"data\":{\"message\":{\"role\":\"assistant\",\"content\":[]}},\"surfaceOp\":\"append\"}\n",
        );
        let log = import_jsonl(doc.as_bytes()).unwrap();
        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].role, "user");
    }

    #[test]
    fn import_rejects_structural_violations() {
        assert!(matches!(import_jsonl(b""), Err(LogError::MissingHeader)));
        let foreign = b"{\"type\":\"session\",\"version\":99,\"id\":\"x\",\"createdAt\":0}\n";
        assert!(matches!(
            import_jsonl(foreign),
            Err(LogError::UnsupportedFormatVersion { found: 99, .. })
        ));
        let gap = concat!(
            "{\"type\":\"session\",\"version\":0,\"id\":\"s\",\"createdAt\":0}\n",
            "{\"type\":\"turn/start\",\"seq\":0,\"time\":1,\"data\":{\"turn\":1}}\n",
            "{\"type\":\"turn/end\",\"seq\":5,\"time\":2,\"data\":{\"turn\":1,\"reason\":{\"kind\":\"completed\"}}}\n",
        );
        assert!(matches!(
            import_jsonl(gap.as_bytes()),
            Err(LogError::SeqDiscontinuity {
                expected: 1,
                found: 5,
                ..
            })
        ));
        // A surface event without its marker is invalid upstream.
        let unmarked = concat!(
            "{\"type\":\"session\",\"version\":0,\"id\":\"s\",\"createdAt\":0}\n",
            "{\"type\":\"user/message\",\"seq\":0,\"time\":1,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"q\"}]}}\n",
        );
        assert!(matches!(
            import_jsonl(unmarked.as_bytes()),
            Err(LogError::MalformedRecord { line: 2, .. })
        ));
        // Surface metadata on an ineligible event is invalid upstream.
        let misflagged = concat!(
            "{\"type\":\"session\",\"version\":0,\"id\":\"s\",\"createdAt\":0}\n",
            "{\"type\":\"turn/start\",\"seq\":0,\"time\":1,\"data\":{\"turn\":1},\"surfaceOp\":\"append\"}\n",
        );
        assert!(matches!(
            import_jsonl(misflagged.as_bytes()),
            Err(LogError::MalformedRecord { line: 2, .. })
        ));
        // A replace whose endpoints are not live surface nodes.
        let orphan_replace = concat!(
            "{\"type\":\"session\",\"version\":0,\"id\":\"s\",\"createdAt\":0}\n",
            "{\"type\":\"user/message\",\"seq\":0,\"time\":1,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"q\"}]},\"surfaceOp\":\"append\"}\n",
            "{\"type\":\"user/message\",\"seq\":1,\"time\":2,\"data\":{\"content\":[{\"type\":\"text\",\"text\":\"r\"}]},\"surfaceOp\":{\"op\":\"replace\",\"start\":0,\"end\":9}}\n",
        );
        assert!(matches!(
            import_jsonl(orphan_replace.as_bytes()),
            Err(LogError::MalformedRecord { line: 3, .. })
        ));
    }
}
