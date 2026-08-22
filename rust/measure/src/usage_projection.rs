//! Pure folds for durable provider-reported token usage and context
//! occupancy (upstream `token-meter/src/{usage,breakdown}-projection.ts`
//! parity). All three units ride the O(1) shadow-price fold
//! ([`crate::surface_projection`]); state is a fixed handful of numbers, so
//! a persisted checkpoint stays bounded over the session's life.
//!
//! Canonical-form contract, documented: the Kotlin writer reports usage on
//! `assistant/chunk` as `{"turn", "step", "chunk": {"type": "usage",
//! "usage": {...}}}` and on `assistant/message` as a top-level `usage`
//! object, camelCase keys `inputTokens`, `outputTokens`, `cacheReadTokens`,
//! `cacheWriteTokens` (disjoint counts — `inputTokens` excludes cache
//! traffic). `request/context` carries `contextWindow` as upstream.

use serde_json::Value;

use session_log::{canonical_header, EpochHeader, SessionEvent};

use crate::estimate::{estimate_system_tokens, estimate_tools_tokens};
use crate::surface_projection::{fold_surface_projection, ShadowPriceClaim};

/// Provider-reported token usage with disjoint counts: the billed input is
/// the sum of all four buckets.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct TokenUsage {
    /// Uncached prompt tokens (cache traffic excluded).
    pub input_tokens: u64,
    /// Completion tokens.
    pub output_tokens: u64,
    /// Cache-hit prompt tokens.
    pub cache_read_tokens: u64,
    /// Cache-write prompt tokens.
    pub cache_write_tokens: u64,
}

/// Cumulative usage totals (upstream `TokenUsageProjection`).
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct UsageTotals {
    /// Sum of uncached input samples.
    pub uncached_input_tokens: u64,
    /// Sum of output samples.
    pub output_tokens: u64,
    /// Sum of cache-read samples.
    pub cache_read_tokens: u64,
    /// Sum of cache-write samples.
    pub cache_write_tokens: u64,
}

#[derive(Debug, Clone, PartialEq)]
struct UsageSample {
    turn: u64,
    step: u64,
    buckets: UsageTotals,
}

/// State of the token-usage fold: totals plus the newest sample's slot. The
/// single `last` slot relies on the session-log invariant that usage reports
/// for one turn/step are adjacent.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct TokenUsageState {
    /// Cumulative disjoint-bucket totals.
    pub totals: UsageTotals,
    last: Option<UsageSample>,
}

/// State of the context-occupancy fold: last-wins usage numerator,
/// last-wins `request/context` denominator, and the O(1) running surface
/// total (upstream `ContextPressureState`).
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ContextPressureState {
    /// Latest advertised context window, when any.
    pub context_window: Option<u64>,
    /// Latest prompt-side usage pressure, when any.
    pub pressure_tokens: Option<u64>,
    /// Running heuristic total over the current surface.
    pub surface_tokens: u64,
    /// `surface_tokens` at the newest usage sample; absent until one lands.
    pub sampled_surface_tokens: Option<u64>,
    /// Shadow price armed by the immediately preceding metering event.
    pub claim: Option<ShadowPriceClaim>,
}

/// View of the occupancy fold at one event boundary.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct ContextPressureView {
    /// Latest advertised context window, when any.
    pub context_window: Option<u64>,
    /// Latest prompt-side pressure, when any.
    pub pressure_tokens: Option<u64>,
    /// Pressure projected to the next request: the sample plus the
    /// surface's signed movement since it was taken, floored at zero.
    pub projected_tokens: Option<u64>,
}

/// State of the context-composition fold (upstream `ContextBreakdownState`).
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ContextBreakdownState {
    /// Newest request's system-prompt figure.
    pub system_tokens: u64,
    /// Newest request's tool-schema figure.
    pub tools_tokens: u64,
    /// Running heuristic figure over the live surface.
    pub message_tokens: u64,
    /// Shadow price armed by the immediately preceding metering event.
    pub claim: Option<ShadowPriceClaim>,
}

fn usage_from_value(value: &Value) -> Option<TokenUsage> {
    let get = |key: &str| value.get(key).and_then(Value::as_u64);
    Some(TokenUsage {
        input_tokens: get("inputTokens")?,
        output_tokens: get("outputTokens")?,
        cache_read_tokens: get("cacheReadTokens").unwrap_or(0),
        cache_write_tokens: get("cacheWriteTokens").unwrap_or(0),
    })
}

/// The usage a chunk or finalized message reports for its step, if any.
fn usage_of(event: &SessionEvent) -> Option<TokenUsage> {
    match event.event_type.as_str() {
        "assistant/chunk" => event
            .payload
            .pointer("/chunk/usage")
            .filter(|_value| {
                event.payload.pointer("/chunk/type").and_then(Value::as_str) == Some("usage")
            })
            .and_then(usage_from_value),
        "assistant/message" => event.payload.get("usage").and_then(usage_from_value),
        _ => None,
    }
}

fn step_of(event: &SessionEvent) -> Option<(u64, u64)> {
    let turn = event.payload.get("turn").and_then(Value::as_u64)?;
    let step = event.payload.get("step").and_then(Value::as_u64)?;
    Some((turn, step))
}

/// Prompt-side pressure of one request: input plus cache traffic, no output.
fn pressure_from(usage: &TokenUsage) -> u64 {
    usage.input_tokens + usage.cache_read_tokens + usage.cache_write_tokens
}

fn buckets_of(usage: &TokenUsage) -> UsageTotals {
    UsageTotals {
        uncached_input_tokens: usage.input_tokens,
        output_tokens: usage.output_tokens,
        cache_read_tokens: usage.cache_read_tokens,
        cache_write_tokens: usage.cache_write_tokens,
    }
}

impl TokenUsageState {
    /// Folds one event: a repeated sample for the same turn/step replaces
    /// that step's earlier value instead of double counting it.
    ///
    /// # Errors
    /// Propagates [`crate::SurfaceProjectionError`] — this unit does not
    /// arm claims, so an armed one is expired untouched.
    pub fn apply(&self, event: &SessionEvent) -> Result<Self, crate::SurfaceProjectionError> {
        let Some(usage) = usage_of(event) else {
            return Ok(self.clone());
        };
        let Some((turn, step)) = step_of(event) else {
            return Ok(self.clone());
        };
        let buckets = buckets_of(&usage);
        let previous = self
            .last
            .as_ref()
            .and_then(|last| (last.turn == turn && last.step == step).then_some(last.buckets));
        if previous == Some(buckets) {
            return Ok(self.clone());
        }
        let add_replacing = |t: u64, p: u64, n: u64| t - p + n;
        let mut totals = UsageTotals::default();
        totals.uncached_input_tokens = add_replacing(
            self.totals.uncached_input_tokens,
            previous.map_or(0, |p| p.uncached_input_tokens),
            buckets.uncached_input_tokens,
        );
        totals.output_tokens = add_replacing(
            self.totals.output_tokens,
            previous.map_or(0, |p| p.output_tokens),
            buckets.output_tokens,
        );
        totals.cache_read_tokens = add_replacing(
            self.totals.cache_read_tokens,
            previous.map_or(0, |p| p.cache_read_tokens),
            buckets.cache_read_tokens,
        );
        totals.cache_write_tokens = add_replacing(
            self.totals.cache_write_tokens,
            previous.map_or(0, |p| p.cache_write_tokens),
            buckets.cache_write_tokens,
        );
        Ok(Self {
            totals,
            last: Some(UsageSample {
                turn,
                step,
                buckets,
            }),
        })
    }
}

impl ContextPressureState {
    /// Folds one event: `request/context` sets the denominator last-wins, a
    /// usage sample stamps the numerator before the same event joins the
    /// surface, and every event rides the shadow-price fold.
    ///
    /// # Errors
    /// Propagates [`crate::SurfaceProjectionError`] from the shadow-price
    /// fold.
    pub fn apply(&self, event: &SessionEvent) -> Result<Self, crate::SurfaceProjectionError> {
        let fold = fold_surface_projection(self.claim.as_ref(), event)?;
        let mut next = self.clone();
        if event.event_type == "request/context" {
            next.context_window = event.payload.get("contextWindow").and_then(Value::as_u64);
        }
        if let Some(usage) = usage_of(event) {
            let pressure_tokens = pressure_from(&usage);
            if next.pressure_tokens != Some(pressure_tokens)
                || next.sampled_surface_tokens != Some(next.surface_tokens)
            {
                next.pressure_tokens = Some(pressure_tokens);
                next.sampled_surface_tokens = Some(next.surface_tokens);
            }
        }
        next.surface_tokens = (next.surface_tokens as i64 + fold.delta_tokens).max(0) as u64;
        next.claim = fold.claim;
        Ok(next)
    }

    /// The occupancy view at this boundary.
    #[must_use]
    pub fn view(&self) -> ContextPressureView {
        ContextPressureView {
            context_window: self.context_window,
            pressure_tokens: self.pressure_tokens,
            projected_tokens: self.pressure_tokens.map(|p| {
                let sampled = self.sampled_surface_tokens.unwrap_or(0);
                (p as i64 + self.surface_tokens as i64 - sampled as i64).max(0) as u64
            }),
        }
    }
}

impl ContextBreakdownState {
    /// Folds one event: envelope figures are last-wins per `request/header`
    /// and the message figure rides the shadow-price fold.
    ///
    /// # Errors
    /// Propagates [`crate::SurfaceProjectionError`] from the shadow-price
    /// fold.
    pub fn apply(&self, event: &SessionEvent) -> Result<Self, crate::SurfaceProjectionError> {
        let fold = fold_surface_projection(self.claim.as_ref(), event)?;
        let mut system_tokens = self.system_tokens;
        let mut tools_tokens = self.tools_tokens;
        if event.event_type == "request/header" {
            if let Ok(header) = serde_json::from_value::<EpochHeader>(
                event.payload.get("header").cloned().unwrap_or(Value::Null),
            ) {
                let canonical = canonical_header(header);
                system_tokens = estimate_system_tokens(&canonical);
                tools_tokens = estimate_tools_tokens(&canonical);
            }
        }
        Ok(Self {
            system_tokens,
            tools_tokens,
            message_tokens: (self.message_tokens as i64 + fold.delta_tokens).max(0) as u64,
            claim: fold.claim,
        })
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn event(seq: u64, kind: &str, payload: serde_json::Value) -> SessionEvent {
        SessionEvent {
            seq,
            event_type: kind.into(),
            time_ms: 0,
            payload,
        }
    }

    fn usage_json(input: u64, output: u64, read: u64, write: u64) -> Value {
        json!({
            "inputTokens": input, "outputTokens": output,
            "cacheReadTokens": read, "cacheWriteTokens": write
        })
    }

    #[test]
    fn usage_samples_accumulate_and_same_step_replaces() {
        let mut state = TokenUsageState::default();
        state = state
            .apply(&event(
                0,
                "assistant/chunk",
                json!({"turn": 1, "step": 1,
                       "chunk": {"type": "usage", "usage": usage_json(10, 5, 0, 0)}}),
            ))
            .unwrap();
        // early sample for the same step
        state = state
            .apply(&event(
                1,
                "assistant/message",
                json!({"turn": 1, "step": 1, "content": "a",
                       "usage": usage_json(12, 6, 3, 1)}),
            ))
            .unwrap();
        assert_eq!(state.totals.uncached_input_tokens, 12);
        assert_eq!(state.totals.cache_read_tokens, 3);
        assert_eq!(state.totals.cache_write_tokens, 1);
        // a later step adds on top
        state = state
            .apply(&event(
                2,
                "assistant/message",
                json!({"turn": 1, "step": 2, "content": "b",
                       "usage": usage_json(20, 7, 0, 0)}),
            ))
            .unwrap();
        assert_eq!(state.totals.uncached_input_tokens, 32);
        assert_eq!(state.totals.output_tokens, 13);
    }

    #[test]
    fn context_pressure_tracks_window_pressure_and_projection() {
        let mut state = ContextPressureState::default();
        state = state
            .apply(&event(
                0,
                "request/context",
                json!({"provider": "p", "model": "m", "contextWindow": 65536}),
            ))
            .unwrap();
        assert_eq!(state.context_window, Some(65536));
        state = state
            .apply(&event(1, "user/message", json!({"content": "12345678"})))
            .unwrap();
        state = state
            .apply(&event(
                2,
                "assistant/message",
                json!({"turn": 1, "step": 1, "content": "1234",
                       "usage": usage_json(100, 0, 50, 25)}),
            ))
            .unwrap();
        assert_eq!(state.pressure_tokens, Some(175));
        assert_eq!(state.surface_tokens, 19); // 10 + 9
        let view = state.view();
        assert_eq!(view.projected_tokens, Some(184)); // 175 + (19 - 10)
                                                      // a shadow-priced compaction shrinks the projection
        state = state
            .apply(&event(
                3,
                "compaction/summary",
                json!({"shadowedSeqs": [1], "shadowedTokenCount": 6}),
            ))
            .unwrap();
        state = state
            .apply(&event(
                4,
                "assistant/message",
                json!({"content": "123456789012", "surfaceOp": "replace",
                       "sourceEventSeqs": [1]}),
            ))
            .unwrap();
        assert_eq!(state.surface_tokens, 24); // 19 - 6 + 11
        assert_eq!(state.view().projected_tokens, Some(189)); // 175 + (24 - 10)
    }

    #[test]
    fn context_breakdown_prices_envelope_and_surface() {
        let mut state = ContextBreakdownState::default();
        state = state
            .apply(&event(
                0,
                "request/header",
                json!({"header": {"config": {"provider": "p", "model": "m"},
                        "system": "1234567890123456789012345678901234"},
                       "reason": "initial"}),
            ))
            .unwrap();
        assert_eq!(state.system_tokens, 9 + 4); // ceil(34/4) + ROLE_OVERHEAD
        assert_eq!(state.tools_tokens, 0);
        state = state
            .apply(&event(1, "user/message", json!({"content": "1234"})))
            .unwrap();
        assert_eq!(state.message_tokens, 9);
    }
}
