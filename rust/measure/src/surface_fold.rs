//! The positional priced-surface fold that metering and compaction planning
//! serve (upstream `token-meter/src/surface-fold.ts` parity). The O(1)
//! projection units deliberately do NOT share this fold — they ride the
//! shadow-price protocol in [`crate::surface_projection`] instead; fully
//! metered logs stay in agreement by construction because both price through
//! [`crate::estimate`].
//!
//! Canonical-form adaptation, documented: upstream locates a replacement by
//! its envelope `{start, end}` range; this engine's payload-carried
//! `sourceEventSeqs` names the shadowed node set directly, so the fold
//! removes exactly the cited live nodes and inserts at the first cited
//! position.

use thiserror::Error;

use session_log::{project_message, SessionEvent};

use crate::estimate::estimate_message;

/// One token-priced node in the current ordered session surface.
#[derive(Debug, Clone, PartialEq)]
pub struct TokenSurfaceNode {
    /// Durable sequence number of the surface event.
    pub seq: u64,
    /// Heuristic tokens for the exact message projected by this node.
    pub tokens: u64,
}

/// One surface event's placement and cost against the surface preceding it.
#[derive(Debug, Clone, PartialEq)]
pub struct SurfaceTokenFold {
    /// Heuristic price of the event's own message; 0 when it derives none.
    pub tokens: u64,
    /// The surface after the event, detached from the input.
    pub nodes: Vec<TokenSurfaceNode>,
    /// Signed change in the surface total: `tokens` minus anything shadowed.
    pub delta_tokens: i64,
}

/// Failures of the positional fold.
#[derive(Debug, Error, PartialEq)]
pub enum SurfaceFoldError {
    /// A replacement cites no live surface node — committed logs are
    /// surface-validated at append time, so an unresolvable citation is log
    /// corruption and must fail loud rather than skip the event.
    #[error("token surface: replace at seq {seq} cites no current surface node")]
    InvalidRange {
        /// Seq of the offending replace event.
        seq: u64,
    },
}

/// Folds one surface event onto a priced surface. Total and
/// allocation-fresh: the caller assigns the result rather than mutating in
/// place.
///
/// # Errors
/// Returns [`SurfaceFoldError::InvalidRange`] for a replace citing no live
/// node.
pub fn fold_surface_tokens(
    nodes: &[TokenSurfaceNode],
    event: &SessionEvent,
) -> Result<SurfaceTokenFold, SurfaceFoldError> {
    let message = project_message(event);
    let tokens = message.as_ref().map_or(0, estimate_message);
    let is_replace = event
        .payload
        .get("surfaceOp")
        .and_then(serde_json::Value::as_str)
        == Some("replace");
    if message.is_none() {
        // Off-surface events (including eligible-but-nonprojecting ones)
        // fold with zero delta — upstream receives only surface events here.
        return Ok(SurfaceTokenFold {
            tokens: 0,
            nodes: nodes.to_vec(),
            delta_tokens: 0,
        });
    }
    if !is_replace {
        let mut next = nodes.to_vec();
        next.push(TokenSurfaceNode {
            seq: event.seq,
            tokens,
        });
        return Ok(SurfaceTokenFold {
            tokens,
            nodes: next,
            delta_tokens: tokens as i64,
        });
    }
    let cited: Vec<u64> = event
        .payload
        .get("sourceEventSeqs")
        .and_then(serde_json::Value::as_array)
        .map(|items| items.iter().filter_map(serde_json::Value::as_u64).collect())
        .unwrap_or_default();
    let insert_at = nodes.iter().position(|node| cited.contains(&node.seq));
    let Some(insert_at) = insert_at else {
        return Err(SurfaceFoldError::InvalidRange { seq: event.seq });
    };
    let removed: u64 = nodes
        .iter()
        .filter(|node| cited.contains(&node.seq))
        .map(|node| node.tokens)
        .sum();
    let mut next: Vec<TokenSurfaceNode> = nodes
        .iter()
        .filter(|node| !cited.contains(&node.seq))
        .cloned()
        .collect();
    next.insert(
        insert_at.min(next.len()),
        TokenSurfaceNode {
            seq: event.seq,
            tokens,
        },
    );
    Ok(SurfaceTokenFold {
        tokens,
        nodes: next,
        delta_tokens: tokens as i64 - removed as i64,
    })
}

/// Folds a whole event list into the priced surface.
///
/// # Errors
/// Propagates [`SurfaceFoldError`] from the first offending replace.
pub fn fold_surface(events: &[SessionEvent]) -> Result<Vec<TokenSurfaceNode>, SurfaceFoldError> {
    let mut nodes = Vec::new();
    for event in events {
        let folded = fold_surface_tokens(&nodes, event)?;
        nodes = folded.nodes;
    }
    Ok(nodes)
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

    fn node(seq: u64, tokens: u64) -> TokenSurfaceNode {
        TokenSurfaceNode { seq, tokens }
    }

    #[test]
    fn appends_price_and_accumulate() {
        let events = vec![
            event(0, "user/message", json!({"content": "12345678"})), // 2+4
            event(1, "assistant/message", json!({"content": "1234"})), // 1+4
        ];
        let nodes = fold_surface(&events).unwrap();
        assert_eq!(nodes, vec![node(0, 10), node(1, 9)]);
    }

    #[test]
    fn a_replace_shadows_cited_nodes_and_takes_the_first_position() {
        let events = vec![
            event(0, "user/message", json!({"content": "12345678"})),
            event(1, "assistant/message", json!({"content": "1234"})),
            event(
                2,
                "assistant/message",
                json!({"content": "123456789012",
                "surfaceOp": "replace", "sourceEventSeqs": [0, 1]}),
            ),
        ];
        let nodes = fold_surface(&events).unwrap();
        // summary: ceil(12/4)+4 = 7, replaces both nodes (6+5 removed)
        assert_eq!(nodes, vec![node(2, 11)]);

        let single = fold_surface(&events[..2]).unwrap();
        let folded = fold_surface_tokens(&single, &events[2]).unwrap();
        assert_eq!(folded.delta_tokens, 11 - 10 - 9);
    }

    #[test]
    fn a_replace_citing_no_live_node_fails_loud() {
        let events = vec![
            event(0, "step/start", json!({})),
            event(
                1,
                "assistant/message",
                json!({"content": "x", "surfaceOp": "replace",
                "sourceEventSeqs": [0]}),
            ),
        ];
        assert_eq!(
            fold_surface(&events).unwrap_err(),
            SurfaceFoldError::InvalidRange { seq: 1 }
        );
    }

    #[test]
    fn non_surface_events_fold_with_zero_delta() {
        let mut nodes = vec![node(0, 6)];
        let folded =
            fold_surface_tokens(&nodes, &event(1, "turn/start", json!({"turn": 1}))).unwrap();
        assert_eq!(folded.delta_tokens, 0);
        assert_eq!(folded.nodes, nodes.clone());
        nodes = folded.nodes;
        assert_eq!(nodes.len(), 1);
    }
}
