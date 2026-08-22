//! Strict surface validation — the accept-time checks a writer runs before
//! an event joins the log (upstream `core/session/src/surface.ts` parity).
//! [`crate::SessionLog::derive_messages`] stays a lenient projection on
//! purpose; this module is the strict gate the Kotlin writer calls first.
//!
//! Canonical-form adaptation, documented: upstream carries `surfaceOp` /
//! `sourceEventSeqs` on the event envelope with the replace op spelled
//! `{op: 'replace', start, end}`. This engine keeps surface metadata inside
//! the payload (its on-disk format makes no envelope promise), and the
//! import bridge has already folded `start/end` ranges into the cited shadow
//! set — so a replace here is `surfaceOp: "replace"` plus the
//! `sourceEventSeqs` that name exactly the shadowed nodes. Consequences:
//! - upstream's contiguous-range check is expressed as "every cited live
//!   node is shadowed", the set having already been computed by the fold;
//! - an upstream `start/end not found in surface` rejection surfaces here
//!   as "a replace must shadow at least one live surface node".

use serde_json::Value;

use crate::SessionEvent;

/// Validation failures, each naming the offending seq for log forensics.
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum SurfaceError {
    /// Event seq does not equal its index — the log is not contiguous.
    #[error("seq {seq} is not contiguous; expected {expected}")]
    SeqDiscontinuity {
        /// Seq found on the event.
        seq: u64,
        /// Seq expected at this position.
        expected: u64,
    },
    /// A non-surface-eligible event carries surface metadata.
    #[error("event {seq} of type {event_type} is not surface-eligible and cannot carry {field}")]
    IneligibleCarrier {
        /// Offending event seq.
        seq: u64,
        /// Offending event type.
        event_type: String,
        /// The payload field it must not carry (`surfaceOp`/`sourceEventSeqs`).
        field: &'static str,
    },
    /// Malformed `surfaceOp` payload value.
    #[error("event {seq} carries an invalid surfaceOp")]
    InvalidOp {
        /// Offending event seq.
        seq: u64,
    },
    /// `sourceEventSeqs` is malformed (not an array of safe non-negative
    /// integers, contains duplicates, or references the event itself/later).
    #[error("event {seq}: sourceEventSeqs {reason}")]
    InvalidProvenance {
        /// Offending event seq.
        seq: u64,
        /// What is wrong with the citations.
        reason: String,
    },
    /// A replace cites no live surface node (upstream: start/end not found).
    #[error("event {seq}: a replace must shadow at least one live surface node")]
    EmptyShadow {
        /// Offending event seq.
        seq: u64,
    },
    /// A `tool/result` replace violates its rewrite restriction.
    #[error("event {seq}: tool/result surface replacement {reason}")]
    InvalidToolResultRewrite {
        /// Offending event seq.
        seq: u64,
        /// Which restriction failed.
        reason: String,
    },
}

/// Event types that can join the model-visible surface (upstream
/// `SURFACE_EVENT_TYPES`).
#[must_use]
pub fn is_surface_eligible_type(event_type: &str) -> bool {
    matches!(
        event_type,
        "user/message" | "assistant/message" | "tool/result"
    )
}

/// The surface operation one event carries: not surface-eligible, a plain
/// append (marker absent or `"append"` — this engine's default), or a
/// positional replacement.
enum SurfaceOp {
    NotSurface,
    Append,
    Replace,
}

/// Reads and validates the payload-carried surface marker of one event.
fn surface_op_of(event: &SessionEvent) -> Result<SurfaceOp, SurfaceError> {
    let ineligible = |field: &'static str| SurfaceError::IneligibleCarrier {
        seq: event.seq,
        event_type: event.event_type.clone(),
        field,
    };
    if !is_surface_eligible_type(&event.event_type) {
        if event.payload.get("surfaceOp").is_some() {
            return Err(ineligible("surfaceOp"));
        }
        if event.payload.get("sourceEventSeqs").is_some() {
            return Err(ineligible("sourceEventSeqs"));
        }
        return Ok(SurfaceOp::NotSurface);
    }
    let Some(op) = event.payload.get("surfaceOp") else {
        return Ok(SurfaceOp::Append); // append by default
    };
    match op.as_str() {
        Some("append") => Ok(SurfaceOp::Append),
        Some("replace") => Ok(SurfaceOp::Replace),
        _ => Err(SurfaceError::InvalidOp { seq: event.seq }),
    }
}

/// Whether an eligible event's payload projects onto the surface. Mirrors
/// [`crate::SessionLog::derive_messages`]: a `user/message`/`tool/result`
/// always projects when it carries `content`; an `assistant/message`
/// without a `content` key is usage-only bookkeeping and never joins.
fn projects_to_surface(event: &SessionEvent) -> bool {
    event.payload.get("content").is_some()
}

/// Validates cited source seqs: array of unique, non-negative safe integers,
/// all strictly earlier than the event itself. Upstream allows an empty list
/// only on `assistant/message` (citations without replacement).
fn assert_provenance(
    event: &SessionEvent,
    cited: Option<&Vec<Value>>,
) -> Result<Vec<u64>, SurfaceError> {
    let Some(raw) = cited else {
        return Ok(Vec::new());
    };
    let bad = |reason: String| SurfaceError::InvalidProvenance {
        seq: event.seq,
        reason,
    };
    if raw.is_empty() && event.event_type != "assistant/message" {
        return Err(bad("must not be empty except on assistant/message".into()));
    }
    let mut seqs = Vec::with_capacity(raw.len());
    for source in raw {
        let Some(seq) = source.as_u64() else {
            return Err(bad("must densely contain non-negative safe integers".into()));
        };
        if seq >= event.seq {
            return Err(bad(format!(
                "must reference earlier events: {seq} >= current seq {current}",
                current = event.seq
            )));
        }
        if seqs.contains(&seq) {
            return Err(bad("must not contain duplicates".into()));
        }
        seqs.push(seq);
    }
    Ok(seqs)
}

/// Restricts a `tool/result` replacement to rewriting exactly one current
/// result's content: one cited live node, itself a `tool/result`, and every
/// payload field outside `content` deep-equal to the original (upstream
/// `assertToolResultRewrite` parity on this engine's flat payload shape;
/// surface metadata keys ride the payload here, so they are stripped too —
/// upstream compares only `data`, which never carries them).
fn assert_tool_result_rewrite(
    event: &SessionEvent,
    shadowed: &[u64],
    events: &[SessionEvent],
) -> Result<(), SurfaceError> {
    if event.event_type != "tool/result" {
        return Ok(());
    }
    let bad = |reason: String| SurfaceError::InvalidToolResultRewrite {
        seq: event.seq,
        reason,
    };
    if shadowed.len() != 1 {
        return Err(bad("must rewrite exactly one current node".into()));
    }
    let original = &events[shadowed[0] as usize];
    if original.event_type != "tool/result" {
        return Err(bad("must target a current tool/result".into()));
    }
    let strip_content = |payload: &Value| -> Value {
        let mut clone = payload.clone();
        if let Some(map) = clone.as_object_mut() {
            map.remove("content");
            map.remove("surfaceOp");
            map.remove("sourceEventSeqs");
        }
        clone
    };
    if strip_content(&original.payload) != strip_content(&event.payload) {
        return Err(bad("may change only content".into()));
    }
    Ok(())
}

/// Validates one event list strictly: seq contiguity, surface-marker
/// eligibility, citation provenance, live-node shadowing, and the
/// tool/result rewrite restriction — folding the surface exactly as
/// [`crate::SessionLog::derive_messages`] does, but rejecting where the
/// lenient projection would merely degrade.
///
/// # Errors
/// Returns the first [`SurfaceError`] describing the violation.
pub fn validate_surface(events: &[SessionEvent]) -> Result<(), SurfaceError> {
    let mut nodes: Vec<u64> = Vec::new();
    for (index, event) in events.iter().enumerate() {
        let expected = index as u64;
        if event.seq != expected {
            return Err(SurfaceError::SeqDiscontinuity {
                seq: event.seq,
                expected,
            });
        }
        let op = surface_op_of(event)?;
        let cited_raw = event
            .payload
            .get("sourceEventSeqs")
            .and_then(Value::as_array)
            .cloned();
        let cited = assert_provenance(event, cited_raw.as_ref())?;
        match op {
            SurfaceOp::NotSurface => continue,
            SurfaceOp::Append => {
                if projects_to_surface(event) {
                    nodes.push(event.seq);
                }
            }
            SurfaceOp::Replace => {
                let shadowed: Vec<u64> = nodes
                    .iter()
                    .copied()
                    .filter(|n| cited.contains(n))
                    .collect();
                if shadowed.is_empty() {
                    return Err(SurfaceError::EmptyShadow { seq: event.seq });
                }
                assert_tool_result_rewrite(event, &shadowed, events)?;
                let insert_at = nodes
                    .iter()
                    .position(|n| cited.contains(n))
                    .unwrap_or(nodes.len());
                nodes.retain(|n| !cited.contains(n));
                nodes.insert(insert_at, event.seq);
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn event(seq: u64, kind: &str, payload: Value) -> SessionEvent {
        SessionEvent {
            seq,
            event_type: kind.into(),
            time_ms: 0,
            payload,
        }
    }

    #[test]
    fn clean_append_and_replace_logs_validate() {
        let events = vec![
            event(0, "user/message", json!({"content": "q"})),
            event(
                1,
                "assistant/message",
                json!({"content": "a1", "surfaceOp": "append"}),
            ),
            event(
                2,
                "assistant/message",
                json!({"content": "a2", "surfaceOp": "replace",
                       "sourceEventSeqs": [1]}),
            ),
        ];
        assert!(validate_surface(&events).is_ok());
    }

    #[test]
    fn non_eligible_events_must_not_carry_surface_metadata() {
        let events = vec![event(0, "step/start", json!({"surfaceOp": "append"}))];
        let err = validate_surface(&events).unwrap_err();
        assert!(err.to_string().contains("not surface-eligible"));
        let events = vec![event(0, "turn/end", json!({"sourceEventSeqs": [0]}))];
        assert!(matches!(
            validate_surface(&events).unwrap_err(),
            SurfaceError::IneligibleCarrier {
                field: "sourceEventSeqs",
                ..
            }
        ));
    }

    #[test]
    fn invalid_op_and_bad_provenance_are_rejected() {
        let events = vec![
            event(0, "user/message", json!({"content": "q"})),
            event(
                1,
                "assistant/message",
                json!({"content": "a", "surfaceOp": 7}),
            ),
        ];
        assert!(matches!(
            validate_surface(&events).unwrap_err(),
            SurfaceError::InvalidOp { .. }
        ));

        let events = vec![
            event(0, "user/message", json!({"content": "q"})),
            event(
                1,
                "assistant/message",
                json!({"content": "a", "surfaceOp": "append",
                "sourceEventSeqs": [1]}),
            ), // cites itself
        ];
        assert!(matches!(
            validate_surface(&events).unwrap_err(),
            SurfaceError::InvalidProvenance { .. }
        ));

        let events = vec![
            event(0, "user/message", json!({"content": "q"})),
            event(
                1,
                "user/message",
                json!({"content": "r", "surfaceOp": "replace",
                "sourceEventSeqs": []}),
            ), // empty only on assistant/message
        ];
        assert!(matches!(
            validate_surface(&events).unwrap_err(),
            SurfaceError::InvalidProvenance { .. }
        ));
    }

    #[test]
    fn a_replace_citing_no_live_node_is_rejected() {
        let events = vec![
            event(0, "step/start", json!({})), // earlier, but never on the surface
            event(1, "user/message", json!({"content": "q"})),
            event(
                2,
                "assistant/message",
                json!({"content": "s", "surfaceOp": "replace",
                "sourceEventSeqs": [0]}),
            ),
        ];
        assert!(matches!(
            validate_surface(&events).unwrap_err(),
            SurfaceError::EmptyShadow { .. }
        ));
    }

    #[test]
    fn tool_result_replacement_must_change_only_content() {
        let events = vec![
            event(
                0,
                "tool/result",
                json!({"content": "full output",
                "tool_call_id": "c1"}),
            ),
            event(
                1,
                "tool/result",
                json!({"content": "[pruned]",
                "tool_call_id": "c1", "surfaceOp": "replace",
                "sourceEventSeqs": [0]}),
            ),
        ];
        assert!(validate_surface(&events).is_ok()); // only content changed

        let events = vec![
            event(
                0,
                "tool/result",
                json!({"content": "x", "tool_call_id": "c1"}),
            ),
            event(
                1,
                "tool/result",
                json!({"content": "x", "tool_call_id": "OTHER",
                "surfaceOp": "replace", "sourceEventSeqs": [0]}),
            ),
        ];
        assert!(matches!(
            validate_surface(&events).unwrap_err(),
            SurfaceError::InvalidToolResultRewrite { .. }
        ));

        let events = vec![
            event(0, "user/message", json!({"content": "q"})),
            event(1, "assistant/message", json!({"content": "a"})),
            event(
                2,
                "tool/result",
                json!({"content": "x", "tool_call_id": "c1",
                "surfaceOp": "replace", "sourceEventSeqs": [0, 1]}),
            ),
        ];
        let err = validate_surface(&events).unwrap_err();
        assert!(err.to_string().contains("exactly one current node"));
    }

    #[test]
    fn seq_discontinuity_is_rejected() {
        let events = vec![event(5, "user/message", json!({"content": "q"}))];
        assert!(matches!(
            validate_surface(&events).unwrap_err(),
            SurfaceError::SeqDiscontinuity {
                seq: 5,
                expected: 0
            }
        ));
    }
}
