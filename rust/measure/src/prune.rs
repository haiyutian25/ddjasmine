//! Deterministic tool-result pruning (upstream
//! `compaction-tool-result-pruner` config + `measureContent`/`pruneContent`
//! parity). Slicing is by Unicode code point, never by UTF-16 code unit or
//! UTF-8 byte, so a retained boundary cannot split a surrogate pair;
//! grapheme clusters may still split.
//!
//! Canonical-form adaptation, documented: upstream prunes a typed
//! content-block array; this engine's `tool/result` payload carries
//! `content` as a plain string, which is measured and pruned as one text
//! span. An array value is handled block-wise exactly like upstream (text
//! blocks sliced, non-text blocks passed through) for self-describing
//! extensions. The replacement event cites the shadowed node through this
//! engine's payload-carried `sourceEventSeqs` and is planned together with
//! the adjacent `compaction/prune` shadow-price event the O(1) folds
//! consume.

use serde_json::Value;
use thiserror::Error;

use session_log::{project_message, SessionEvent};

use crate::estimate::estimate_message;

/// Fixed marker substituted for every removed middle span.
pub const PRUNE_MARKER: &str = "\n\n[... tool result middle pruned ...]\n\n";

/// Low-friction defaults for coding-agent tool output.
pub const DEFAULT_THRESHOLD_CHARS: usize = 8192;
/// Default retained head, in code points.
pub const DEFAULT_HEAD_CHARS: usize = 4096;
/// Default retained tail, in code points.
pub const DEFAULT_TAIL_CHARS: usize = 1024;

/// Resolved, validated pruning budgets.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PruneConfig {
    /// Total budget; results at or below it are untouched.
    pub threshold_chars: usize,
    /// Retained head, in code points.
    pub head_chars: usize,
    /// Retained tail, in code points.
    pub tail_chars: usize,
}

impl Default for PruneConfig {
    fn default() -> Self {
        Self {
            threshold_chars: DEFAULT_THRESHOLD_CHARS,
            head_chars: DEFAULT_HEAD_CHARS,
            tail_chars: DEFAULT_TAIL_CHARS,
        }
    }
}

/// Configuration and prune failures.
#[derive(Debug, Error, PartialEq)]
pub enum PruneError {
    /// A config key outside the three known budgets.
    #[error("ToolResultPruneConfig: unknown key \"{key}\" (allowed: thresholdChars, headChars, tailChars)")]
    UnknownKey {
        /// The offending key.
        key: String,
    },
    /// A budget is not a positive integer.
    #[error("ToolResultPruneConfig: {name} ({value}) must be a positive integer")]
    NotPositive {
        /// Offending field name.
        name: &'static str,
        /// Offending value.
        value: u64,
    },
    /// A budget is not a non-negative integer.
    #[error("ToolResultPruneConfig: {name} ({value}) must be a non-negative integer")]
    NotNonNegative {
        /// Offending field name.
        name: &'static str,
        /// Offending value.
        value: i64,
    },
    /// head + marker + tail exceeds the total budget.
    #[error(
        "ToolResultPruneConfig: headChars + marker + tailChars ({emitted}) must be at most thresholdChars ({threshold})"
    )]
    EmittedExceedsThreshold {
        /// Emitted head + marker + tail, in code points.
        emitted: usize,
        /// Configured threshold.
        threshold: usize,
    },
    /// The removed span could not be located — unreachable under a valid
    /// config when the content is over budget.
    #[error("tool-result prune: failed to locate the removed text span")]
    NoRemovedSpan,
}

/// Resolves and validates pruning budgets from raw JSON config keys
/// (`thresholdChars`, `headChars`, `tailChars`; missing keys take the
/// defaults).
///
/// # Errors
/// Returns [`PruneError`] for unknown keys or violated budget invariants.
pub fn resolve_config(raw: &Value) -> Result<PruneConfig, PruneError> {
    if let Some(map) = raw.as_object() {
        for key in map.keys() {
            if !matches!(key.as_str(), "thresholdChars" | "headChars" | "tailChars") {
                return Err(PruneError::UnknownKey { key: key.clone() });
            }
        }
    }
    let get = |name: &str| raw.get(name);
    let threshold = match get("thresholdChars") {
        Some(v) => v
            .as_u64()
            .filter(|n| *n > 0)
            .ok_or(PruneError::NotPositive {
                name: "thresholdChars",
                value: v.as_u64().unwrap_or(0),
            })? as usize,
        None => DEFAULT_THRESHOLD_CHARS,
    };
    let head = match get("headChars") {
        Some(v) => {
            let n = v
                .as_i64()
                .filter(|n| *n >= 0)
                .ok_or(PruneError::NotNonNegative {
                    name: "headChars",
                    value: v.as_i64().unwrap_or(-1),
                })?;
            n as usize
        }
        None => DEFAULT_HEAD_CHARS,
    };
    let tail = match get("tailChars") {
        Some(v) => {
            let n = v
                .as_i64()
                .filter(|n| *n >= 0)
                .ok_or(PruneError::NotNonNegative {
                    name: "tailChars",
                    value: v.as_i64().unwrap_or(-1),
                })?;
            n as usize
        }
        None => DEFAULT_TAIL_CHARS,
    };
    let config = PruneConfig {
        threshold_chars: threshold,
        head_chars: head,
        tail_chars: tail,
    };
    let emitted = config.head_chars + PRUNE_MARKER.chars().count() + config.tail_chars;
    if emitted > config.threshold_chars {
        return Err(PruneError::EmittedExceedsThreshold {
            emitted,
            threshold: config.threshold_chars,
        });
    }
    Ok(config)
}

/// Measures text content in Unicode code points; non-text blocks cost zero.
#[must_use]
pub fn measure_content(content: &Value) -> usize {
    match content {
        Value::String(text) => text.chars().count(),
        Value::Array(blocks) => blocks
            .iter()
            .filter(|b| b.get("type").and_then(Value::as_str) == Some("text"))
            .map(|b| {
                b.get("text")
                    .and_then(Value::as_str)
                    .map_or(0, |t| t.chars().count())
            })
            .sum(),
        _ => 0,
    }
}

/// Replaces an over-budget text middle while retaining rich-block order.
///
/// # Errors
/// Returns [`PruneError::NoRemovedSpan`] when the span cannot be located —
/// a valid config and over-budget content guarantee it exists.
pub fn prune_content(content: &Value, config: &PruneConfig) -> Result<Option<Value>, PruneError> {
    let total_chars = measure_content(content);
    if total_chars <= config.threshold_chars {
        return Ok(None);
    }
    let removed_start = config.head_chars;
    let removed_end = total_chars - config.tail_chars;
    match content {
        Value::String(text) => {
            let head: String = text.chars().take(removed_start).collect();
            let tail: String = text.chars().skip(removed_end).collect();
            let pruned = format!("{head}{PRUNE_MARKER}{tail}");
            assert_invariants(&pruned, total_chars, config)?;
            Ok(Some(Value::String(pruned)))
        }
        Value::Array(blocks) => {
            let mut pruned = Vec::new();
            let mut consumed = 0usize;
            let mut marker_inserted = false;
            for block in blocks {
                let is_text = block.get("type").and_then(Value::as_str) == Some("text");
                if !is_text {
                    pruned.push(block.clone());
                    continue;
                }
                let text = block
                    .get("text")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                let points: Vec<char> = text.chars().collect();
                let block_start = consumed;
                let block_end = block_start + points.len();
                let head_end = points.len().min(removed_start.saturating_sub(block_start));
                let tail_start = points.len().min(removed_end.saturating_sub(block_start));
                let intersects = block_start < removed_end && block_end > removed_start;
                let marker = if intersects && !marker_inserted {
                    marker_inserted = true;
                    PRUNE_MARKER
                } else {
                    ""
                };
                let mut text_out = points[..head_end].iter().collect::<String>();
                text_out.push_str(marker);
                text_out.extend(&points[tail_start..]);
                if !text_out.is_empty() {
                    let mut out = block.clone();
                    if let Some(map) = out.as_object_mut() {
                        map.insert("text".into(), Value::String(text_out));
                    }
                    pruned.push(out);
                }
                consumed = block_end;
            }
            if !marker_inserted {
                return Err(PruneError::NoRemovedSpan);
            }
            let pruned = Value::Array(pruned);
            assert_invariants_value(&pruned, total_chars, config)?;
            Ok(Some(pruned))
        }
        _ => Ok(None), // non-text content costs zero and never exceeds
    }
}

fn assert_invariants(
    pruned: &str,
    total_chars: usize,
    config: &PruneConfig,
) -> Result<(), PruneError> {
    let after = pruned.chars().count();
    if after > config.threshold_chars || after >= total_chars {
        return Err(PruneError::NoRemovedSpan);
    }
    Ok(())
}

fn assert_invariants_value(
    pruned: &Value,
    total_chars: usize,
    config: &PruneConfig,
) -> Result<(), PruneError> {
    let after = measure_content(pruned);
    if after > config.threshold_chars || after >= total_chars {
        return Err(PruneError::NoRemovedSpan);
    }
    Ok(())
}

/// One planned prune: the shadow-price event payload to append immediately
/// before the replacement, plus the replacement event's payload.
#[derive(Debug, Clone, PartialEq)]
pub struct PrunePlan {
    /// Seq of the surface node being pruned.
    pub target_seq: u64,
    /// `compaction/prune` payload: `shadowedSeqs` + `shadowedTokenCount`.
    pub shadow_price_payload: Value,
    /// Replacement `tool/result` payload: the original with pruned
    /// `content`, plus `surfaceOp: "replace"` and `sourceEventSeqs`.
    pub replacement_payload: Value,
}

/// Plans prunes for every over-budget `tool/result` currently on the
/// surface. Pure planning only — appending (in reverse surface order so
/// citations stay valid) is the caller's job.
///
/// # Errors
/// Propagates [`PruneError`] from content pruning.
pub fn plan_prunes(
    events: &[SessionEvent],
    config: &PruneConfig,
) -> Result<Vec<PrunePlan>, PruneError> {
    // Walk the surface the same way derive_messages does to know which
    // tool/result nodes are current.
    let mut surface: Vec<u64> = Vec::new();
    let mut plans = Vec::new();
    for event in events {
        let cited: Vec<u64> = event
            .payload
            .get("sourceEventSeqs")
            .and_then(Value::as_array)
            .map(|items| items.iter().filter_map(Value::as_u64).collect())
            .unwrap_or_default();
        let is_replace = event.payload.get("surfaceOp").and_then(Value::as_str) == Some("replace");
        if is_replace {
            if let Some(insert_at) = surface.iter().position(|s| cited.contains(s)) {
                surface.retain(|s| !cited.contains(s));
                surface.insert(insert_at, event.seq);
            }
            continue;
        }
        if project_message(event).is_some() {
            surface.push(event.seq);
        }
    }
    for seq in &surface {
        let event = &events[*seq as usize];
        if event.event_type != "tool/result" {
            continue;
        }
        let Some(original_content) = event.payload.get("content") else {
            continue;
        };
        let Some(pruned) = prune_content(original_content, config)? else {
            continue;
        };
        let shadowed_tokens = project_message(event).map_or(0, |m| estimate_message(&m));
        let mut replacement = event.payload.clone();
        if let Some(map) = replacement.as_object_mut() {
            map.insert("content".into(), pruned);
            map.insert("surfaceOp".into(), Value::String("replace".into()));
            map.insert(
                "sourceEventSeqs".into(),
                Value::Array(vec![Value::from(*seq)]),
            );
        }
        plans.push(PrunePlan {
            target_seq: *seq,
            shadow_price_payload: serde_json::json!({
                "shadowedSeqs": [*seq],
                "shadowedTokenCount": shadowed_tokens,
            }),
            replacement_payload: replacement,
        });
    }
    Ok(plans)
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
    fn config_defaults_and_validation() {
        assert_eq!(resolve_config(&json!({})).unwrap(), PruneConfig::default());
        assert_eq!(PruneConfig::default().threshold_chars, 8192);

        assert!(matches!(
            resolve_config(&json!({"unknown": 1})).unwrap_err(),
            PruneError::UnknownKey { .. }
        ));
        assert!(matches!(
            resolve_config(&json!({"thresholdChars": 0})).unwrap_err(),
            PruneError::NotPositive { .. }
        ));
        assert!(matches!(
            resolve_config(&json!({"headChars": -1})).unwrap_err(),
            PruneError::NotNonNegative { .. }
        ));
        // head + marker + tail > threshold
        assert!(matches!(
            resolve_config(&json!({"thresholdChars": 100, "headChars": 90,
                                   "tailChars": 90}))
            .unwrap_err(),
            PruneError::EmittedExceedsThreshold { .. }
        ));
    }

    #[test]
    fn within_budget_content_is_untouched() {
        let config = PruneConfig {
            threshold_chars: 10,
            head_chars: 3,
            tail_chars: 3,
        };
        assert_eq!(prune_content(&json!("short"), &config).unwrap(), None);
    }

    #[test]
    fn over_budget_string_gets_head_marker_tail() {
        let config = PruneConfig {
            threshold_chars: 50,
            head_chars: 3,
            tail_chars: 3,
        };
        let text = "a".repeat(60);
        let pruned = prune_content(&json!(text), &config).unwrap().unwrap();
        let expected = format!("aaa{PRUNE_MARKER}aaa");
        assert_eq!(pruned, json!(expected));
        // smaller and within threshold
        assert!(measure_content(&pruned) <= config.threshold_chars);
    }

    #[test]
    fn code_points_never_split_surrogates() {
        let config = PruneConfig {
            threshold_chars: 45,
            head_chars: 1,
            tail_chars: 1,
        };
        // U+1F600 is one code point, two UTF-16 units upstream
        let text = "\u{1F600}".repeat(60);
        let pruned = prune_content(&json!(text), &config).unwrap().unwrap();
        let s = pruned.as_str().unwrap();
        assert!(s.starts_with('\u{1F600}'));
        assert!(s.ends_with('\u{1F600}'));
        assert!(s.contains(PRUNE_MARKER));
        // boundaries land on code points: a Rust &str cannot hold lone
        // surrogates, so parsing to chars already proves the invariant
    }

    #[test]
    fn block_arrays_keep_rich_blocks_and_order() {
        let config = PruneConfig {
            threshold_chars: 50,
            head_chars: 2,
            tail_chars: 2,
        };
        let blocks = json!([
            {"type": "text", "text": "a".repeat(40)},
            {"type": "image", "data": "x"},
            {"type": "text", "text": "k".repeat(40)},
        ]);
        let pruned = prune_content(&blocks, &config).unwrap().unwrap();
        let arr = pruned.as_array().unwrap();
        // middle text replaced by marker in the first intersecting block only
        assert_eq!(arr.len(), 3);
        assert_eq!(arr[1], json!({"type": "image", "data": "x"}));
        let first = arr[0].get("text").and_then(Value::as_str).unwrap();
        assert!(first.starts_with("aa"));
        assert!(first.contains(PRUNE_MARKER));
        assert!(measure_content(&pruned) <= config.threshold_chars);
    }

    #[test]
    fn plan_prunes_cites_targets_with_shadow_prices() {
        let config = PruneConfig {
            threshold_chars: 50,
            head_chars: 1,
            tail_chars: 1,
        };
        let big = "x".repeat(60);
        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(1, "user/message", json!({"content": "q"})),
            event(
                2,
                "tool/result",
                json!({"tool_call_id": "c1", "content": big}),
            ),
            event(
                3,
                "tool/result",
                json!({"tool_call_id": "c2", "content": "tiny"}),
            ),
        ];
        let plans = plan_prunes(&events, &config).unwrap();
        assert_eq!(plans.len(), 1);
        let plan = &plans[0];
        assert_eq!(plan.target_seq, 2);
        assert_eq!(plan.shadow_price_payload["shadowedSeqs"], json!([2]));
        assert!(
            plan.shadow_price_payload["shadowedTokenCount"]
                .as_u64()
                .unwrap()
                > 0
        );
        assert_eq!(plan.replacement_payload["surfaceOp"], "replace");
        assert_eq!(plan.replacement_payload["sourceEventSeqs"], json!([2]));
        assert_eq!(plan.replacement_payload["tool_call_id"], "c1");
        let content = plan.replacement_payload["content"].as_str().unwrap();
        assert!(content.contains(PRUNE_MARKER));
    }

    #[test]
    fn shadowed_targets_are_not_replanned() {
        let config = PruneConfig {
            threshold_chars: 50,
            head_chars: 1,
            tail_chars: 1,
        };
        let big = "x".repeat(60);
        let events = vec![
            event(0, "turn/start", json!({"turn": 1})),
            event(
                1,
                "tool/result",
                json!({"tool_call_id": "c1", "content": big}),
            ),
            event(
                2,
                "tool/result",
                json!({"tool_call_id": "c1", "content": "pruned",
                       "surfaceOp": "replace", "sourceEventSeqs": [1]}),
            ),
        ];
        assert!(plan_prunes(&events, &config).unwrap().is_empty());
    }
}
