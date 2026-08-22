//! Packed chunk-row encoding for `assistant/chunk` delta runs (upstream
//! `core/session/src/chunk-rows.ts` parity). A run of at least
//! [`MIN_RUN`] consecutive whitelisted same-kind, same-block delta chunk
//! events packs into one storage row; every other event passes through
//! verbatim. Both layouts decode identically, so changing [`MIN_RUN`]
//! never invalidates stored logs — it is a format constant, not a tunable.
//!
//! Pure and stateless: [`pack_chunk_runs`] maps an event batch to storage
//! records and [`expand_chunk_row`] maps one packed row back to its exact
//! events. The import bridge uses the same expansion semantics when
//! replaying upstream fixtures; this module adds the encoding direction.

use serde_json::{json, Map, Value};

use crate::SessionEvent;

/// Minimum members before a run packs. Below it a row's envelope rivals the
/// event lines it replaces.
pub const MIN_RUN: usize = 3;

/// The delta kinds that pack.
#[derive(Debug, Clone, Copy, PartialEq)]
enum DeltaKind {
    Text,
    Reasoning,
    ToolCall,
}

impl DeltaKind {
    fn row_tag(self) -> &'static str {
        match self {
            DeltaKind::Text => "text-chunks",
            DeltaKind::Reasoning => "reasoning-chunks",
            DeltaKind::ToolCall => "tool-call-chunks",
        }
    }
}

/// One durable log line's JSON value: a session event verbatim, or a packed
/// chunk row.
#[derive(Debug, Clone, PartialEq)]
pub enum StorageRecord {
    /// An event stored verbatim, one line per event.
    Event(SessionEvent),
    /// A packed run of delta chunks.
    ChunkRow(Value),
}

fn has_exact_keys(value: &Map<String, Value>, keys: &[&str]) -> bool {
    value.len() == keys.len() && keys.iter().all(|k| value.contains_key(*k))
}

/// Classifies an event for packing: its delta kind when the entire shape
/// (payload, chunk — exact keys, primitive types) is whitelisted, else
/// `None` (store verbatim). Inputs come from live appends and parsed
/// fixture files, so the checks are structural, not type-trusted.
fn classify(event: &SessionEvent) -> Option<DeltaKind> {
    if event.event_type != "assistant/chunk" {
        return None;
    }
    let data = event.payload.as_object()?;
    if !has_exact_keys(data, &["turn", "step", "chunk"]) {
        return None;
    }
    data.get("turn")?.as_u64()?;
    data.get("step")?.as_u64()?;
    let chunk = data.get("chunk")?.as_object()?;
    chunk.get("index")?.as_u64()?;
    let chunk_type = chunk.get("type")?.as_str()?;
    match chunk_type {
        "text-delta" | "reasoning-delta" => {
            if has_exact_keys(chunk, &["type", "index", "text"])
                && chunk.get("text").and_then(Value::as_str).is_some()
            {
                Some(if chunk_type == "text-delta" {
                    DeltaKind::Text
                } else {
                    DeltaKind::Reasoning
                })
            } else {
                None
            }
        }
        "tool-call-delta" => {
            let id_ok = chunk.get("id").and_then(Value::as_str).is_some()
                && chunk
                    .get("argumentsDelta")
                    .and_then(Value::as_str)
                    .is_some();
            if !id_ok {
                return None;
            }
            let name = chunk.get("name");
            let shape_ok = match name {
                None => has_exact_keys(chunk, &["type", "index", "id", "argumentsDelta"]),
                Some(Value::String(_)) => {
                    has_exact_keys(chunk, &["type", "index", "id", "name", "argumentsDelta"])
                }
                Some(_) => false,
            };
            shape_ok.then_some(DeltaKind::ToolCall)
        }
        // Whitelist fall-through over parsed data: block-start/end, usage,
        // finish, and any future chunk variant stay one event per line.
        _ => None,
    }
}

/// Whether `next` extends a run ending in `prev` (same kind already
/// checked by the caller).
fn continues(prev: &SessionEvent, next: &SessionEvent, kind: DeltaKind) -> bool {
    if next.seq != prev.seq + 1 {
        return false;
    }
    // Integer times keep gap encoding exact; both are i64 here, and the
    // decoded sum is checked on expansion.
    let gap = next.time_ms - prev.time_ms;
    if !(-(1i64 << 53)..=(1i64 << 53)).contains(&gap) {
        return false;
    }
    let field = |e: &SessionEvent, k: &str| e.payload.get(k).and_then(Value::as_u64);
    if field(next, "turn") != field(prev, "turn") || field(next, "step") != field(prev, "step") {
        return false;
    }
    let index = |e: &SessionEvent| e.payload.pointer("/chunk/index").and_then(Value::as_u64);
    if index(next) != index(prev) {
        return false;
    }
    if kind != DeltaKind::ToolCall {
        return true;
    }
    // `name` must match in presence AND value — a mixed run is not
    // representable.
    let name = |e: &SessionEvent| e.payload.pointer("/chunk/name").cloned();
    let id = |e: &SessionEvent| {
        e.payload
            .pointer("/chunk/id")
            .and_then(Value::as_str)
            .map(str::to_string)
    };
    id(prev) == id(next) && name(prev) == name(next)
}

/// Builds the row for a completed run (`run.len() >= MIN_RUN`, uniform per
/// [`continues`]).
fn build_row(kind: DeltaKind, run: &[SessionEvent]) -> Value {
    let first = &run[0];
    let mut dt = Vec::with_capacity(run.len() - 1);
    for (i, event) in run.iter().enumerate().skip(1) {
        dt.push(json!(event.time_ms - run[i - 1].time_ms));
    }
    let base = |payload_key: &str, payload: Value| {
        json!({
            "turn": first.payload.get("turn").cloned().unwrap_or(Value::Null),
            "step": first.payload.get("step").cloned().unwrap_or(Value::Null),
            "index": first.payload.pointer("/chunk/index").cloned().unwrap_or(Value::Null),
            "dt": dt,
            payload_key: payload,
        })
    };
    match kind {
        DeltaKind::ToolCall => {
            let mut data = base(
                "args",
                Value::Array(
                    run.iter()
                        .map(|e| {
                            e.payload
                                .pointer("/chunk/argumentsDelta")
                                .cloned()
                                .unwrap_or(Value::Null)
                        })
                        .collect(),
                ),
            );
            let id = first
                .payload
                .pointer("/chunk/id")
                .cloned()
                .unwrap_or(Value::Null);
            if let Some(map) = data.as_object_mut() {
                map.insert("id".into(), id);
                if let Some(name) = first.payload.pointer("/chunk/name").cloned() {
                    map.insert("name".into(), name);
                }
            }
            json!({
                "type": kind.row_tag(),
                "seq0": first.seq,
                "time0": first.time_ms,
                "data": data,
            })
        }
        DeltaKind::Text | DeltaKind::Reasoning => {
            let texts: Vec<Value> = run
                .iter()
                .map(|e| {
                    e.payload
                        .pointer("/chunk/text")
                        .cloned()
                        .unwrap_or(Value::Null)
                })
                .collect();
            json!({
                "type": kind.row_tag(),
                "seq0": first.seq,
                "time0": first.time_ms,
                "data": base("texts", Value::Array(texts)),
            })
        }
    }
}

/// Emits a completed run: packed when it reached [`MIN_RUN`], verbatim
/// otherwise. A non-empty run always has its kind set.
fn flush_run(out: &mut Vec<StorageRecord>, kind: Option<DeltaKind>, run: &[SessionEvent]) {
    if run.len() >= MIN_RUN {
        let kind = kind.expect("a run with members always has its kind");
        out.push(StorageRecord::ChunkRow(build_row(kind, run)));
    } else {
        out.extend(run.iter().cloned().map(StorageRecord::Event));
    }
}

/// Packs an event batch for storage: each run of at least [`MIN_RUN`]
/// consecutive whitelisted same-kind, same-block delta chunk events becomes
/// one chunk row; every other event passes through verbatim, in order.
#[must_use]
pub fn pack_chunk_runs(events: &[SessionEvent]) -> Vec<StorageRecord> {
    let mut out = Vec::new();
    let mut kind: Option<DeltaKind> = None;
    let mut run: Vec<SessionEvent> = Vec::new();
    for event in events {
        let Some(k) = classify(event) else {
            flush_run(&mut out, kind, &run);
            kind = None;
            run.clear();
            out.push(StorageRecord::Event(event.clone()));
            continue;
        };
        let continues_run =
            kind == Some(k) && run.last().is_some_and(|last| continues(last, event, k));
        if continues_run {
            run.push(event.clone());
            continue;
        }
        flush_run(&mut out, kind, &run);
        kind = Some(k);
        run.clear();
        run.push(event.clone());
    }
    flush_run(&mut out, kind, &run);
    out
}

/// Storage-row tags that expand into `assistant/chunk` runs.
pub const PACKED_ROW_TAGS: [&str; 3] = ["text-chunks", "reasoning-chunks", "tool-call-chunks"];

/// Expands one packed row back into its exact events: member `k` has seq
/// `seq0 + k` and time `time0 + Σ dt[0..k]`.
///
/// # Errors
/// Returns a diagnostic string for a malformed row — the uniform
/// `malformed <tag> storage row: <why>` upstream shape.
pub fn expand_chunk_row(row: &Value) -> Result<Vec<SessionEvent>, String> {
    let tag = row
        .get("type")
        .and_then(Value::as_str)
        .filter(|t| PACKED_ROW_TAGS.contains(t))
        .ok_or_else(|| "malformed packed row: not a chunk-row tag".to_string())?;
    let malformed = |why: &str| format!("malformed {tag} storage row: {why}");
    let seq0 = row
        .get("seq0")
        .and_then(Value::as_u64)
        .ok_or_else(|| malformed("lacks seq0"))?;
    let time0 = row
        .get("time0")
        .and_then(Value::as_i64)
        .ok_or_else(|| malformed("lacks time0"))?;
    let data = row
        .get("data")
        .and_then(Value::as_object)
        .ok_or_else(|| malformed("lacks data"))?;
    let get_num = |key: &str| {
        data.get(key)
            .and_then(Value::as_u64)
            .ok_or_else(|| malformed("turn/step/index must be numbers"))
    };
    let turn = get_num("turn")?;
    let step = get_num("step")?;
    let index = get_num("index")?;
    let (payload_key, chunk_type) = match tag {
        "text-chunks" => ("texts", "text-delta"),
        "reasoning-chunks" => ("texts", "reasoning-delta"),
        _ => ("args", "tool-call-delta"),
    };
    let members = data
        .get(payload_key)
        .and_then(Value::as_array)
        .filter(|a| !a.is_empty())
        .ok_or_else(|| malformed("payload must be a non-empty string array"))?;
    let dt = data
        .get("dt")
        .and_then(Value::as_array)
        .ok_or_else(|| malformed("dt must be an array of integers"))?;
    if dt.len() + 1 != members.len() {
        return Err(malformed("dt arity must be members - 1"));
    }
    let chunk_base = match tag {
        "tool-call-chunks" => {
            let id = data
                .get("id")
                .and_then(Value::as_str)
                .ok_or_else(|| malformed("lacks id"))?;
            let mut map = Map::new();
            map.insert("type".into(), json!("tool-call-delta"));
            map.insert("index".into(), json!(index));
            map.insert("id".into(), json!(id));
            if let Some(name) = data.get("name") {
                map.insert("name".into(), name.clone());
            }
            map
        }
        _ => {
            let mut map = Map::new();
            map.insert("type".into(), json!(chunk_type));
            map.insert("index".into(), json!(index));
            map
        }
    };
    let mut events = Vec::with_capacity(members.len());
    let mut time = time0;
    for (k, member) in members.iter().enumerate() {
        if k > 0 {
            let gap = dt[k - 1]
                .as_i64()
                .ok_or_else(|| malformed("dt must be integers"))?;
            time = time
                .checked_add(gap)
                .ok_or_else(|| malformed("time overflow reconstructing member"))?;
        }
        let mut chunk = chunk_base.clone();
        chunk.insert(
            if tag == "tool-call-chunks" {
                "argumentsDelta"
            } else {
                "text"
            }
            .into(),
            member.clone(),
        );
        events.push(SessionEvent {
            seq: seq0 + k as u64,
            event_type: "assistant/chunk".into(),
            time_ms: time,
            payload: json!({"turn": turn, "step": step, "chunk": chunk}),
        });
    }
    Ok(events)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn delta(seq: u64, time: i64, chunk: Value) -> SessionEvent {
        SessionEvent {
            seq,
            event_type: "assistant/chunk".into(),
            time_ms: time,
            payload: json!({"turn": 1, "step": 1, "chunk": chunk}),
        }
    }

    fn text_delta(seq: u64, time: i64, index: u64, text: &str) -> SessionEvent {
        delta(
            seq,
            time,
            json!({"type": "text-delta", "index": index, "text": text}),
        )
    }

    #[test]
    fn runs_of_min_size_pack_and_round_trip() {
        let events: Vec<SessionEvent> = (0..4)
            .map(|i| text_delta(i, 100 + i as i64 * 7, 0, &format!("t{i}")))
            .collect();
        let records = pack_chunk_runs(&events);
        assert_eq!(records.len(), 1);
        let StorageRecord::ChunkRow(row) = &records[0] else {
            panic!("expected a packed row");
        };
        assert_eq!(row["type"], "text-chunks");
        assert_eq!(row["seq0"], 0);
        assert_eq!(row["time0"], 100);
        assert_eq!(row["data"]["dt"], json!([7, 7, 7]));
        assert_eq!(row["data"]["texts"], json!(["t0", "t1", "t2", "t3"]));
        let expanded = expand_chunk_row(row).unwrap();
        assert_eq!(expanded, events);
    }

    #[test]
    fn short_runs_and_other_events_pass_through_verbatim() {
        let events = vec![
            text_delta(0, 10, 0, "a"),
            text_delta(1, 11, 0, "b"), // only 2 in the run: stays verbatim
            SessionEvent {
                seq: 2,
                event_type: "user/message".into(),
                time_ms: 12,
                payload: json!({"content": "hi"}),
            },
        ];
        let records = pack_chunk_runs(&events);
        assert_eq!(records.len(), 3);
        assert!(records.iter().all(|r| matches!(r, StorageRecord::Event(_))));
    }

    #[test]
    fn non_whitelisted_chunks_never_pack() {
        let events: Vec<SessionEvent> = (0..4)
            .map(|i| {
                delta(
                    i,
                    100,
                    json!({"type": "block-start", "index": 0, "block": "text"}),
                )
            })
            .collect();
        assert_eq!(pack_chunk_runs(&events).len(), 4);
    }

    #[test]
    fn tool_call_runs_require_matching_id_and_name_presence() {
        let call = |seq: u64, id: &str, name: Option<&str>, args: &str| {
            let mut chunk = json!({"type": "tool-call-delta", "index": 0, "id": id,
                                   "argumentsDelta": args});
            if let Some(n) = name {
                chunk["name"] = json!(n);
            }
            delta(seq, 100, chunk)
        };
        let events = vec![
            call(0, "c1", Some("bash"), "{}"),
            call(1, "c1", Some("bash"), "a"),
            call(2, "c1", Some("bash"), "b"),
        ];
        let records = pack_chunk_runs(&events);
        let StorageRecord::ChunkRow(row) = &records[0] else {
            panic!("expected a packed row");
        };
        assert_eq!(row["type"], "tool-call-chunks");
        assert_eq!(row["data"]["id"], "c1");
        assert_eq!(row["data"]["name"], "bash");
        assert_eq!(row["data"]["args"], json!(["{}", "a", "b"]));
        assert_eq!(expand_chunk_row(row).unwrap(), events);

        // mixed name presence breaks the run
        let mixed = vec![
            call(0, "c1", Some("bash"), "{}"),
            call(1, "c1", None, "a"),
            call(2, "c1", None, "b"),
        ];
        assert_eq!(pack_chunk_runs(&mixed).len(), 3);
    }

    #[test]
    fn kind_or_block_switches_flush_the_run() {
        let events = vec![
            text_delta(0, 10, 0, "a"),
            text_delta(1, 11, 0, "b"),
            text_delta(2, 12, 0, "c"),
            text_delta(3, 13, 1, "d"), // block switch: run of 3 packs, rest verbatim
        ];
        let records = pack_chunk_runs(&events);
        assert_eq!(records.len(), 2);
        assert!(matches!(&records[0], StorageRecord::ChunkRow(_)));
        assert!(matches!(&records[1], StorageRecord::Event(_)));
    }

    #[test]
    fn negative_dt_from_clock_rollback_round_trips() {
        let events = vec![
            text_delta(0, 200, 0, "a"),
            text_delta(1, 100, 0, "b"), // clock rolled back
            text_delta(2, 150, 0, "c"),
        ];
        let records = pack_chunk_runs(&events);
        let StorageRecord::ChunkRow(row) = &records[0] else {
            panic!("expected a packed row");
        };
        assert_eq!(row["data"]["dt"], json!([-100, 50]));
        assert_eq!(expand_chunk_row(row).unwrap(), events);
    }

    #[test]
    fn malformed_rows_fail_loud() {
        assert!(expand_chunk_row(&json!({"type": "text-chunks"})).is_err());
        assert!(
            expand_chunk_row(&json!({"type": "nope", "seq0": 0, "time0": 0, "data": {}})).is_err()
        );
        let row = json!({"type": "text-chunks", "seq0": 0, "time0": 0,
                         "data": {"turn": 1, "step": 1, "index": 0,
                                  "dt": [1, 2], "texts": ["a", "b"]}});
        assert!(expand_chunk_row(&row).unwrap_err().contains("dt arity"));
    }
}
