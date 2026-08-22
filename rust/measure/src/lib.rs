//! measure: fixed-density token estimation and the surface-token folds
//! shared by metering, compaction planning, and pruning (upstream
//! `token-meter` package parity). Pure data in, pure data out — no I/O, no
//! clock, no configuration; the constants are the specification, so a
//! shadow-price event logged by one side replays to identical numbers on
//! the other.
//!
//! Three layers:
//! - [`estimate`] prices single messages and request envelopes;
//! - [`surface_fold`] is the positional priced surface (per-node, O(n)
//!   state) the meter serves and compaction plans against;
//! - [`surface_projection`] is the O(1) shadow-price fold the persisted
//!   projection units ([`usage_projection`]) ride.

pub mod estimate;
pub mod prune;
pub mod surface_fold;
pub mod surface_projection;
pub mod usage_projection;

pub use estimate::{
    estimate_content, estimate_header, estimate_message, estimate_system_tokens,
    estimate_tools_tokens, BLOCK_OVERHEAD, CHARS_PER_TOKEN, ROLE_OVERHEAD,
};
pub use surface_fold::{
    fold_surface, fold_surface_tokens, SurfaceFoldError, SurfaceTokenFold, TokenSurfaceNode,
};
pub use surface_projection::{
    fold_surface_projection, ShadowPriceClaim, SurfaceProjectionError, SurfaceTokensFold,
};
pub use usage_projection::{
    ContextBreakdownState, ContextPressureState, ContextPressureView, TokenUsage, TokenUsageState,
    UsageTotals,
};

pub use prune::{
    plan_prunes, prune_content, resolve_config, PruneConfig, PruneError, PrunePlan,
    DEFAULT_HEAD_CHARS, DEFAULT_TAIL_CHARS, DEFAULT_THRESHOLD_CHARS, PRUNE_MARKER,
};
