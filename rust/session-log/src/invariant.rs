//! Relational invariants over the session event log — a pure state machine
//! replaying the turn/step/tool-call discipline (upstream
//! `core/session/src/invariant.ts` `validateEvent` parity).
//!
//! Canonical-form adaptation, documented: upstream reads turn/step numbers
//! and call ids from typed payload fields (`event.data.turn`, `message.source
//! .callId`); this engine's flat payloads use `turn`, `step`, `tool_call_id`.
//! The `surfaceOp !== 'append'` rewrite exemption reads this engine's
//! payload-carried marker. Envelope checks (key whitelist, safe-integer seq)
//! are structurally enforced by [`crate::SessionEvent`]'s types and the
//! loader's seq-contiguity check; the two legacy rejections the upstream
//! envelope enforcer carries (`request/header-delta` and reason
//! `'fallback'`) are reproduced here as payload checks.

use std::collections::HashSet;

use serde_json::Value;

use crate::SessionEvent;

/// One invariant violation: the offending seq plus the upstream-style
/// message.
#[derive(Debug, thiserror::Error)]
#[error("event {seq}: {message}")]
pub struct InvariantFailure {
    /// Seq of the offending event.
    pub seq: u64,
    /// What rule was broken.
    pub message: String,
}

/// Per-session bookkeeping for relational log checks (upstream
/// `SessionTrace`).
#[derive(Debug, Clone, Default)]
pub struct SessionTrace {
    /// Seq of the last accepted event.
    pub last_seq: Option<u64>,
    /// Turn currently open, if any.
    pub open_turn: Option<u64>,
    /// Step currently open, if any.
    pub open_step: Option<u64>,
    /// Turn number the next `turn/start` must carry.
    pub next_turn: u64,
    /// Step number the next `step/start` in the open turn must carry.
    pub next_step: u64,
    /// Tool calls opened in the open step with no result yet.
    pub pending_calls: HashSet<String>,
}

impl SessionTrace {
    /// A fresh trace: no events seen, next turn and step both 1.
    #[must_use]
    pub fn new() -> Self {
        Self {
            last_seq: None,
            open_turn: None,
            open_step: None,
            next_turn: 1,
            next_step: 1,
            pending_calls: HashSet::new(),
        }
    }

    /// Validates one candidate event against the committed trace and returns
    /// the trace after accepting it. Pure: a rejected event leaves the
    /// committed trace untouched.
    ///
    /// # Errors
    /// Returns the first [`InvariantFailure`] the event triggers.
    pub fn validate_event(&self, event: &SessionEvent) -> Result<Self, InvariantFailure> {
        let fail = |message: String| InvariantFailure {
            seq: event.seq,
            message,
        };
        if Some(event.seq) <= self.last_seq {
            return Err(fail(format!(
                "seq must strictly increase: saw {} after {}",
                event.seq,
                self.last_seq
                    .map_or_else(|| "-1".to_string(), |s| s.to_string())
            )));
        }
        if event.event_type == "request/header-delta" {
            return Err(fail("request/header-delta is retired".into()));
        }
        if event.event_type == "request/header"
            && event.payload.get("reason").and_then(Value::as_str) == Some("fallback")
        {
            return Err(fail("request/header reason 'fallback' is retired".into()));
        }
        let mut next = self.clone();
        next.last_seq = Some(event.seq);

        let turn = |payload: &Value| payload.get("turn").and_then(Value::as_u64);
        let step = |payload: &Value| payload.get("step").and_then(Value::as_u64);
        let require_open_step =
            |trace: &SessionTrace, kind: &str| -> Result<(), InvariantFailure> {
                let (turn, step) = (turn(&event.payload), step(&event.payload));
                if trace.open_turn != turn || trace.open_step != step {
                    return Err(fail(format!(
                        "{kind} names turn {t}/step {s} but open is turn {ot}/step {os}",
                        t = turn.map_or("?".into(), |v| v.to_string()),
                        s = step.map_or("?".into(), |v| v.to_string()),
                        ot = trace.open_turn.map_or("?".into(), |v| v.to_string()),
                        os = trace.open_step.map_or("?".into(), |v| v.to_string()),
                    )));
                }
                Ok(())
            };

        match event.event_type.as_str() {
            "turn/start" => {
                if self.open_turn.is_some() {
                    return Err(fail(format!(
                        "turn/start {} while turn {} is still open",
                        turn(&event.payload).map_or("?".into(), |v| v.to_string()),
                        self.open_turn.map_or("?".into(), |v| v.to_string())
                    )));
                }
                if turn(&event.payload) != Some(self.next_turn) {
                    return Err(fail(format!(
                        "turn/start expected turn {}, got {:?}",
                        self.next_turn,
                        turn(&event.payload)
                    )));
                }
                next.open_turn = Some(self.next_turn);
                next.next_step = 1;
            }
            "turn/end" => {
                if self.open_turn != turn(&event.payload) {
                    return Err(fail(format!(
                        "turn/end {:?} does not match open turn {:?}",
                        turn(&event.payload),
                        self.open_turn
                    )));
                }
                if self.open_step.is_some() {
                    return Err(fail(format!(
                        "turn/end {:?} while step {} is still open",
                        turn(&event.payload),
                        self.open_step.map_or("?".into(), |v| v.to_string())
                    )));
                }
                next.open_turn = None;
                next.next_turn += 1;
            }
            "step/start" => {
                if self.open_turn != turn(&event.payload) {
                    return Err(fail(format!(
                        "step/start in turn {:?} but open turn is {:?}",
                        turn(&event.payload),
                        self.open_turn
                    )));
                }
                if self.open_step.is_some() {
                    return Err(fail(format!(
                        "step/start {:?} while step {} is still open",
                        step(&event.payload),
                        self.open_step.map_or("?".into(), |v| v.to_string())
                    )));
                }
                if step(&event.payload) != Some(self.next_step) {
                    return Err(fail(format!(
                        "step/start expected step {} in turn {:?}, got {:?}",
                        self.next_step,
                        turn(&event.payload),
                        step(&event.payload)
                    )));
                }
                next.open_step = step(&event.payload);
            }
            "step/end" => {
                require_open_step(self, "step/end")?;
                next.pending_calls.clear();
                next.open_step = None;
                next.next_step += 1;
            }
            "assistant/chunk" | "assistant/message" => {
                require_open_step(self, &event.event_type)?;
            }
            "tool/call" => {
                require_open_step(self, "tool/call")?;
                if let Some(call_id) = event.payload.get("callId").and_then(Value::as_str) {
                    next.pending_calls.insert(call_id.to_string());
                }
            }
            "tool/result" => {
                // A surface rewrite of an earlier result is durable turn
                // work, not a second execution of the original call.
                let is_rewrite =
                    event.payload.get("surfaceOp").and_then(Value::as_str) == Some("replace");
                if is_rewrite {
                    if self.open_turn.is_none() {
                        return Err(fail(
                            "tool/result surface replacement appended outside any open turn".into(),
                        ));
                    }
                } else {
                    require_open_step(self, "tool/result")?;
                    let call_id = event
                        .payload
                        .get("tool_call_id")
                        .and_then(Value::as_str)
                        .map(str::to_string);
                    let synthetic_not_started =
                        event.payload.pointer("/error/code").and_then(Value::as_str)
                            == Some(crate::TOOL_NOT_STARTED_CODE);
                    if let Some(call_id) = &call_id {
                        if !self.pending_calls.contains(call_id) && !synthetic_not_started {
                            return Err(fail(format!(
                                "tool/result for {call_id} with no prior tool/call in this step"
                            )));
                        }
                        next.pending_calls.remove(call_id);
                    }
                }
            }
            "user/message" | "session/end-seed" => {}
            "todo/write" | "request/header" | "request/context" if self.open_turn.is_none() => {
                return Err(fail(format!(
                    "{} appended outside any open turn (core execution events must be turn-enclosed)",
                    event.event_type
                )));
            }
            // Merge-extensible event relations belong to their owning plugin.
            _ => {}
        }
        Ok(next)
    }
}

/// Replays a whole event list through the relational state machine,
/// returning the committed trace.
///
/// # Errors
/// Returns the first [`InvariantFailure`].
pub fn validate_log(events: &[SessionEvent]) -> Result<SessionTrace, InvariantFailure> {
    let mut trace = SessionTrace::new();
    for event in events {
        trace = trace.validate_event(event)?;
    }
    Ok(trace)
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
    fn a_disciplined_log_validates() {
        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(1, "user/message", json!({"content": "q"})),
            event(2, "step/start", json!({"turn": 1, "step": 1})),
            event(
                3,
                "assistant/chunk",
                json!({"turn": 1, "step": 1, "chunk": {}}),
            ),
            event(
                4,
                "assistant/message",
                json!({"turn": 1, "step": 1, "content": "a"}),
            ),
            event(
                5,
                "tool/call",
                json!({"turn": 1, "step": 1, "callId": "c1"}),
            ),
            event(
                6,
                "tool/result",
                json!({"turn": 1, "step": 1, "tool_call_id": "c1", "content": "r"}),
            ),
            event(7, "step/end", json!({"turn": 1, "step": 1})),
            event(8, "turn/end", json!({"turn": 1})),
        ];
        let trace = validate_log(&events).unwrap();
        assert_eq!(trace.next_turn, 2);
        assert!(trace.pending_calls.is_empty());
    }

    #[test]
    fn seq_must_strictly_increase() {
        let mut trace = SessionTrace::new();
        trace = trace
            .validate_event(&event(3, "user/message", json!({})))
            .unwrap();
        let err = trace
            .validate_event(&event(3, "user/message", json!({})))
            .unwrap_err();
        assert!(err.message.contains("strictly increase"));
    }

    #[test]
    fn turn_and_step_numbering_is_enforced() {
        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(1, "turn/start", json!({"turn": 2})), // still open
        ];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("still open"));

        let events = vec![event(0, "turn/start", json!({"turn": 2}))]; // expected 1
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("expected turn 1"));

        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(1, "step/start", json!({"turn": 1, "step": 2})), // expected 1
        ];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("expected step 1"));

        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(1, "step/start", json!({"turn": 1, "step": 1})),
            event(2, "turn/end", json!({"turn": 1})), // step still open
        ];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("still open"));
    }

    #[test]
    fn tool_result_needs_a_prior_call_or_synthetic_exemption() {
        let base = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(1, "step/start", json!({"turn": 1, "step": 1})),
        ];
        let mut events = base.clone();
        events.push(event(
            2,
            "tool/result",
            json!({"turn": 1, "step": 1, "tool_call_id": "ghost", "content": "r"}),
        ));
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("no prior tool/call"));

        // The crash-recovery burial result is exempt.
        let mut events = base;
        events.push(event(
            2,
            "tool/result",
            json!({"turn": 1, "step": 1, "tool_call_id": "ghost", "content": "r",
                   "error": {"code": "TOOL_NOT_STARTED"}}),
        ));
        assert!(validate_log(&events).is_ok());
    }

    #[test]
    fn step_scoped_events_must_name_the_open_step() {
        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(1, "step/start", json!({"turn": 1, "step": 1})),
            event(
                2,
                "assistant/message",
                json!({"turn": 1, "step": 2, "content": "a"}),
            ),
        ];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("but open is turn 1/step 1"));
    }

    #[test]
    fn turn_enclosed_events_reject_outside_turns_and_rewrites_reject_outside() {
        let events = vec![event(0, "request/header", json!({"header": {}}))];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("turn-enclosed"));

        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(
                1,
                "tool/result",
                json!({"tool_call_id": "c1", "content": "r", "surfaceOp": "replace",
                       "sourceEventSeqs": [0]}),
            ),
        ];
        // Rewrite inside an open turn passes even without an open step.
        assert!(validate_log(&events).is_ok());

        let events = vec![event(
            0,
            "tool/result",
            json!({"tool_call_id": "c1", "content": "r", "surfaceOp": "replace",
                   "sourceEventSeqs": []}),
        )];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("outside any open turn"));
    }

    #[test]
    fn legacy_shapes_are_rejected() {
        let events = vec![event(0, "request/header-delta", json!({}))];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("retired"));

        let events = vec![event(0, "request/header", json!({"reason": "fallback"}))];
        assert!(validate_log(&events)
            .unwrap_err()
            .message
            .contains("'fallback' is retired"));
    }
}
