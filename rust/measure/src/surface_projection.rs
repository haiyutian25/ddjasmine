//! The O(1) surface-token fold shared by the projection units (upstream
//! `token-meter/src/surface-projection.ts` parity). A projection state must
//! stay bounded, so replacements ride the shadow-price protocol: the
//! metering event immediately before a surface `replace` (`compaction/
//! summary` or `compaction/prune`) states the heuristic price of the exact
//! replaced range, and the fold keeps a running total plus at most one
//! pending claim. Counts are exact by construction when producers derive
//! them from [`crate::estimate`]; a replacement without an armed claim folds
//! with zero delta — bounded state cannot reconstruct the replaced range, so
//! historical replay degrades to drift instead of failing.
//!
//! Canonical-form adaptation, documented: upstream's claim carries the
//! envelope range `{start, end}`; this engine's replace events cite the
//! shadowed seq set in `sourceEventSeqs`, so the claim carries the same seq
//! list (`shadowedSeqs` payload key) and consumption compares it to the
//! replace's citations element-wise.

use serde_json::Value;
use thiserror::Error;

use session_log::{project_message, SessionEvent};

use crate::estimate::estimate_message;

/// One armed shadow price: the heuristic tokens of the surface nodes the
/// IMMEDIATELY following event replaces.
#[derive(Debug, Clone, PartialEq)]
pub struct ShadowPriceClaim {
    /// Seqs of the priced surface nodes, in surface order.
    pub shadowed_seqs: Vec<u64>,
    /// Heuristic tokens of the priced nodes under the fixed estimator.
    pub tokens: u64,
}

/// One event's effect on a running surface-token total.
#[derive(Debug, Clone, PartialEq)]
pub struct SurfaceTokensFold {
    /// Signed change in the surface total; 0 for events off the surface.
    pub delta_tokens: i64,
    /// Claim to carry into the next event; `None` when none survives.
    pub claim: Option<ShadowPriceClaim>,
}

/// Failures of the O(1) fold.
#[derive(Debug, Error, PartialEq)]
pub enum SurfaceProjectionError {
    /// A replacement arrived with an armed claim for a different node set —
    /// the metering event was adjacent, so this is a live producer's
    /// shadow-price contract violation and must fail loud rather than let
    /// the total drift.
    #[error(
        "token surface: replace at seq {seq} cites {cited:?} but the armed claim covers {claimed:?}"
    )]
    ClaimMismatch {
        /// Seq of the offending replace event.
        seq: u64,
        /// Seqs the replace cited.
        cited: Vec<u64>,
        /// Seqs the armed claim covers.
        claimed: Vec<u64>,
    },
}

/// Folds one committed event onto a running surface-token total.
///
/// # Errors
/// Returns [`SurfaceProjectionError::ClaimMismatch`] when a replace arrives
/// with an armed claim for a different node set.
pub fn fold_surface_projection(
    claim: Option<&ShadowPriceClaim>,
    event: &SessionEvent,
) -> Result<SurfaceTokensFold, SurfaceProjectionError> {
    if event.event_type == "compaction/summary" || event.event_type == "compaction/prune" {
        let shadowed_seqs: Vec<u64> = event
            .payload
            .get("shadowedSeqs")
            .and_then(Value::as_array)
            .map(|items| items.iter().filter_map(Value::as_u64).collect())
            .unwrap_or_default();
        let tokens = event
            .payload
            .get("shadowedTokenCount")
            .and_then(Value::as_u64)
            .unwrap_or(0);
        return Ok(SurfaceTokensFold {
            delta_tokens: 0,
            claim: Some(ShadowPriceClaim {
                shadowed_seqs,
                tokens,
            }),
        });
    }
    if !session_log::is_surface_eligible_type(&event.event_type) {
        return Ok(SurfaceTokensFold {
            delta_tokens: 0,
            claim: None,
        });
    }
    let tokens = project_message(event).map_or(0, |m| estimate_message(&m));
    let is_replace = event.payload.get("surfaceOp").and_then(Value::as_str) == Some("replace");
    if !is_replace {
        return Ok(SurfaceTokensFold {
            delta_tokens: tokens as i64,
            claim: None,
        });
    }
    let Some(claim) = claim else {
        // Logs recorded before the shadow-price protocol fold neutrally.
        return Ok(SurfaceTokensFold {
            delta_tokens: 0,
            claim: None,
        });
    };
    let cited: Vec<u64> = event
        .payload
        .get("sourceEventSeqs")
        .and_then(Value::as_array)
        .map(|items| items.iter().filter_map(Value::as_u64).collect())
        .unwrap_or_default();
    if claim.shadowed_seqs != cited {
        return Err(SurfaceProjectionError::ClaimMismatch {
            seq: event.seq,
            cited,
            claimed: claim.shadowed_seqs.clone(),
        });
    }
    Ok(SurfaceTokensFold {
        delta_tokens: tokens as i64 - claim.tokens as i64,
        claim: None,
    })
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

    #[test]
    fn metering_events_arm_claims_and_off_surface_events_expire_them() {
        let arming = event(
            1,
            "compaction/summary",
            json!({"shadowedSeqs": [0], "shadowedTokenCount": 6}),
        );
        let folded = fold_surface_projection(None, &arming).unwrap();
        assert_eq!(folded.delta_tokens, 0);
        let claim = folded.claim.unwrap();
        assert_eq!(claim.shadowed_seqs, vec![0]);
        assert_eq!(claim.tokens, 6);

        // Any other event expires the claim.
        let folded =
            fold_surface_projection(Some(&claim), &event(2, "turn/start", json!({}))).unwrap();
        assert_eq!(folded.claim, None);
    }

    #[test]
    fn a_replace_consumes_the_claim_naming_its_exact_nodes() {
        let claim = ShadowPriceClaim {
            shadowed_seqs: vec![0, 1],
            tokens: 19,
        };
        let replace = event(
            2,
            "assistant/message",
            json!({"content": "123456789012", "surfaceOp": "replace",
                   "sourceEventSeqs": [0, 1]}),
        );
        let folded = fold_surface_projection(Some(&claim), &replace).unwrap();
        assert_eq!(folded.delta_tokens, 11 - 19);
        assert_eq!(folded.claim, None);
    }

    #[test]
    fn a_claim_for_other_nodes_fails_loud() {
        let claim = ShadowPriceClaim {
            shadowed_seqs: vec![0],
            tokens: 6,
        };
        let replace = event(
            2,
            "assistant/message",
            json!({"content": "x", "surfaceOp": "replace", "sourceEventSeqs": [1]}),
        );
        assert!(matches!(
            fold_surface_projection(Some(&claim), &replace).unwrap_err(),
            SurfaceProjectionError::ClaimMismatch { .. }
        ));
    }

    #[test]
    fn appends_price_and_unclaimed_replaces_fold_neutrally() {
        let append = event(0, "user/message", json!({"content": "12345678"}));
        let folded = fold_surface_projection(None, &append).unwrap();
        assert_eq!(folded.delta_tokens, 10);

        let replace = event(
            1,
            "assistant/message",
            json!({"content": "x", "surfaceOp": "replace", "sourceEventSeqs": [0]}),
        );
        let folded = fold_surface_projection(None, &replace).unwrap();
        assert_eq!(folded.delta_tokens, 0);
    }
}
