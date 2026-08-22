//! log: the session log engine.
//!
//! Fixed-core component (ANDROID-PLAN §2.1/§4.0). The log is append-only,
//! seq-continuous, and versioned by its header; model history is always a
//! projection of the log, never stored separately ("model-visible ⟺ logged").
//!
//! Projection rules are two-layer (ANDROID-PLAN §4.0):
//! - **closed core**: `user/message`, `assistant/message`, `tool/result` have
//!   fixed projection rules baked into this crate and evolve with the format
//!   version;
//! - **self-describing extension**: events of unknown types are included when
//!   their payload carries a `model_message` object rendered by the emitting
//!   plugin, so Kotlin plugins can add model-visible event types without a
//!   Rust release.
//!
//! Projection walks an ordered surface, and an event carrying
//! `surfaceOp: "replace"` + `sourceEventSeqs` in its payload shadows the
//! cited events' projections and takes the first cited node's position —
//! the lossy-snapshot mechanism upstream uses for compaction summaries and
//! pruned tool results. Surface metadata rides the payload (not the event
//! envelope) because this log promises no on-disk compatibility with upstream.

use std::sync::{Arc, Mutex, MutexGuard};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use thiserror::Error;

use store::{JsonlStore, StoreError};

mod chunk_rows;
mod import_bridge;
mod invariant;
mod request_header;
mod surface_validate;
pub use chunk_rows::{expand_chunk_row, pack_chunk_runs, StorageRecord, MIN_RUN, PACKED_ROW_TAGS};
pub use import_bridge::import_jsonl;
pub use invariant::{validate_log, InvariantFailure, SessionTrace};
pub use request_header::{
    call_config_equals, canonical_header, fold_request_context, fold_request_header, header_equals,
    AdapterDefaults, CallConfig, EpochHeader, RequestContext,
};
pub use surface_validate::{is_surface_eligible_type, validate_surface, SurfaceError};

/// Format version written into every new session header. Loading rejects any
/// other version; there is no compatibility promise while it stays at 0.
pub const FORMAT_VERSION: u32 = 0;

/// Error code of a crash-recovery `tool/result` synthesized for a call whose
/// start was durably recorded: side effects may have landed.
pub const TOOL_OUTCOME_UNKNOWN_CODE: &str = "TOOL_OUTCOME_UNKNOWN";
/// Error code of a crash-recovery `tool/result` synthesized for a call
/// interrupted before its start was recorded: safe to retry as-is.
pub const TOOL_NOT_STARTED_CODE: &str = "TOOL_NOT_STARTED";

/// Session header, the first record of every log file.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct SessionHeader {
    /// Log format version; must equal [`FORMAT_VERSION`] to load.
    pub format_version: u32,
    /// Opaque session identifier assigned by the host.
    pub session_id: String,
    /// Creation time, milliseconds since the Unix epoch.
    pub created_at_ms: i64,
}

impl SessionHeader {
    /// Creates a header stamped with the current [`FORMAT_VERSION`].
    #[must_use]
    pub fn new(session_id: impl Into<String>, created_at_ms: i64) -> Self {
        Self {
            format_version: FORMAT_VERSION,
            session_id: session_id.into(),
            created_at_ms,
        }
    }
}

/// One durable event. `seq` equals the event's index in the log.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct SessionEvent {
    /// Zero-based position in the append-only sequence.
    pub seq: u64,
    /// Discriminant, e.g. `turn/start`, `user/message`, `assistant/message`.
    #[serde(rename = "type")]
    pub event_type: String,
    /// Wall clock at append, milliseconds since the Unix epoch (upstream's `time`
    /// envelope field). Stamped by the engine; loaded events keep theirs.
    #[serde(default)]
    pub time_ms: i64,
    /// Event payload; shape is owned by the event type.
    pub payload: Value,
}

/// One message of the derived model history.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct ModelMessage {
    /// `user` | `assistant` | `tool` | `system` (extension events may add more).
    pub role: String,
    /// Rendered text content.
    pub content: String,
    /// Tool calls issued by an assistant message.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tool_calls: Vec<ToolCall>,
    /// Correlation id on `tool` messages.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    /// Optional speaker name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

/// One tool call inside an assistant message.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct ToolCall {
    /// Provider-assigned call id.
    pub id: String,
    /// Registered tool name.
    pub name: String,
    /// Arguments as a JSON string (opaque to the log).
    pub arguments_json: String,
}

/// Errors of the log engine.
#[derive(Debug, Error)]
pub enum LogError {
    /// The file's header version is not supported by this build.
    #[error("unsupported format_version {found}; this build supports {supported}")]
    UnsupportedFormatVersion {
        /// Version found in the file header.
        found: u64,
        /// Version supported by this build.
        supported: u32,
    },
    /// A loaded record's seq does not continue the sequence.
    #[error("seq discontinuity at record {index}: expected {expected}, found {found}")]
    SeqDiscontinuity {
        /// Index of the offending record in the file (0 = first event).
        index: usize,
        /// Expected seq value.
        expected: u64,
        /// Seq value found.
        found: u64,
    },
    /// A line of the file is not a well-formed log record.
    #[error("malformed record at line {line}: {reason}")]
    MalformedRecord {
        /// One-based line number in the file.
        line: usize,
        /// Why the record is malformed.
        reason: String,
    },
    /// The file does not start with a header record.
    #[error("missing header record")]
    MissingHeader,
    /// Persistence backend failure.
    #[error("store error: {0}")]
    Store(#[from] StoreError),
}

/// On-disk record framing: first line is a header, every later line an event.
#[derive(Serialize, Deserialize)]
#[serde(tag = "record", rename_all = "snake_case")]
enum LogRecord {
    Header { header: SessionHeader },
    Event { event: SessionEvent },
}

/// The session log: fixed-core engine behind a mutex (ANDROID-PLAN §4.2
/// ownership discipline — thread safety is an obligation of the Rust object,
/// not of the caller's dispatcher discipline).
pub struct SessionLog {
    header: SessionHeader,
    inner: Mutex<Inner>,
    /// Torn-tail bytes dropped by [`SessionLog::load`]'s crash repair;
    /// zero when the file opened clean. Immutable after load.
    repaired_torn_tail_bytes: usize,
}

struct Inner {
    events: Vec<SessionEvent>,
    store: Option<JsonlStore>,
}

/// Scan state of one open turn during crash-recovery analysis: the steps
/// and tool calls opened since its `turn/start` that no matching end/result
/// has paired with yet. Frames nest (a `turn/start` inside an open turn),
/// and each frame's leftovers are buried only with that turn.
struct TurnFrame {
    turn_seq: u64,
    open_steps: Vec<u64>,
    open_calls: Vec<PendingCall>,
}

/// One assistant tool call whose result is not durably recorded. `started`
/// distinguishes the two recovery codes (upstream `interruptedTurnClosers`
/// parity): a call whose `tool/call` was logged may have landed side
/// effects, so its outcome is unknown and the synthesized result tells the
/// model to verify before retrying; a call interrupted before the harness
/// recorded its start is safe to retry as-is.
struct PendingCall {
    id: String,
    started: bool,
}

impl SessionLog {
    /// Creates a fresh in-memory log with no persistence.
    ///
    /// # Panics
    /// Panics only if the internal mutex is poisoned.
    #[must_use]
    pub fn in_memory(header: SessionHeader) -> Arc<Self> {
        Arc::new(Self {
            header,
            inner: Mutex::new(Inner {
                events: Vec::new(),
                store: None,
            }),
            repaired_torn_tail_bytes: 0,
        })
    }

    /// Builds an in-memory log from already-validated header and events
    /// (the upstream import bridge). Seqs must be contiguous from zero — the
    /// importer guarantees it.
    pub(crate) fn from_parts(header: SessionHeader, events: Vec<SessionEvent>) -> Arc<Self> {
        Arc::new(Self {
            header,
            inner: Mutex::new(Inner {
                events,
                store: None,
            }),
            repaired_torn_tail_bytes: 0,
        })
    }

    /// Creates a new log file, writing the header as its first record.
    ///
    /// # Errors
    /// Returns [`LogError::UnsupportedFormatVersion`] if `header` carries a
    /// foreign version, or [`LogError::Store`] if the header write fails.
    pub fn create(store: JsonlStore, header: SessionHeader) -> Result<Arc<Self>, LogError> {
        if header.format_version != FORMAT_VERSION {
            return Err(LogError::UnsupportedFormatVersion {
                found: u64::from(header.format_version),
                supported: FORMAT_VERSION,
            });
        }
        let mut store = store;
        let line = serde_json::to_vec(&LogRecord::Header {
            header: header.clone(),
        })
        .expect("header serialization is infallible");
        store.append_line(&line)?;
        store.flush()?;
        Ok(Arc::new(Self {
            header,
            inner: Mutex::new(Inner {
                events: Vec::new(),
                store: Some(store),
            }),
            repaired_torn_tail_bytes: 0,
        }))
    }

    /// Opens an existing log file: repairs a torn tail from a crash mid-write
    /// (truncate to the committed prefix, upstream's commitRepair equivalent;
    /// the dropped byte count stays visible via
    /// [`SessionLog::repaired_torn_tail_bytes`]), validates the header
    /// version *before* its full structure (a future
    /// format need not satisfy today's structural checks, upstream's rule), then
    /// replays every event checking seq continuity. The store stays attached
    /// so new appends continue the file.
    ///
    /// # Errors
    /// Returns [`LogError::MissingHeader`], [`LogError::UnsupportedFormatVersion`],
    /// [`LogError::SeqDiscontinuity`], [`LogError::MalformedRecord`], or
    /// [`LogError::Store`].
    pub fn load(store: JsonlStore) -> Result<Arc<Self>, LogError> {
        let mut store = store;
        let read = store.read_all()?;
        let repaired_torn_tail_bytes = read.torn_tail.as_ref().map_or(0, Vec::len);
        if read.torn_tail.is_some() {
            // Crash mid-write: the partial record is not parseable by framing
            // contract. Repair by truncating to the committed prefix.
            store.truncate(read.committed_len)?;
        }
        let mut lines = read.lines.iter().enumerate();
        let Some((_, first)) = lines.next() else {
            return Err(LogError::MissingHeader);
        };
        // Version check precedes structural checks: a newer format may have a
        // different header shape and must be rejected as a version mismatch,
        // not as corruption.
        let raw: Value = serde_json::from_slice(first).map_err(|e| LogError::MalformedRecord {
            line: 1,
            reason: e.to_string(),
        })?;
        if raw.get("record").and_then(Value::as_str) != Some("header") {
            return Err(LogError::MalformedRecord {
                line: 1,
                reason: "first record is not a header".into(),
            });
        }
        let found = raw
            .pointer("/header/format_version")
            .and_then(Value::as_u64)
            .ok_or_else(|| LogError::MalformedRecord {
                line: 1,
                reason: "header lacks format_version".into(),
            })?;
        if found != u64::from(FORMAT_VERSION) {
            return Err(LogError::UnsupportedFormatVersion {
                found,
                supported: FORMAT_VERSION,
            });
        }
        let LogRecord::Header { header } = parse_record(first, 1)? else {
            unreachable!("record tag already validated");
        };
        let mut events = Vec::new();
        for (index, line) in lines {
            match parse_record(line, index + 1)? {
                LogRecord::Event { event } => {
                    let expected = events.len() as u64;
                    if event.seq != expected {
                        return Err(LogError::SeqDiscontinuity {
                            index: events.len(),
                            expected,
                            found: event.seq,
                        });
                    }
                    events.push(event);
                }
                LogRecord::Header { .. } => {
                    return Err(LogError::MalformedRecord {
                        line: index + 1,
                        reason: "duplicate header record".into(),
                    });
                }
            }
        }
        Ok(Arc::new(Self {
            header,
            inner: Mutex::new(Inner {
                events,
                store: Some(store),
            }),
            repaired_torn_tail_bytes,
        }))
    }

    /// Torn-tail bytes dropped by [`SessionLog::load`]'s crash repair; zero
    /// when the file opened clean or the log was never loaded from disk.
    /// Field access only — no lock is taken.
    #[must_use]
    pub fn repaired_torn_tail_bytes(&self) -> usize {
        self.repaired_torn_tail_bytes
    }

    /// Header of this log. Field access only — no lock is taken.
    #[must_use]
    pub fn header(&self) -> SessionHeader {
        self.header.clone()
    }

    /// Appends one event, assigning the next seq. The store write commits
    /// before the in-memory projection mutates (durable-then-visible, same
    /// ordering rule as upstream's inbox splice).
    ///
    /// # Errors
    /// Returns [`LogError::Store`] if the durable write fails; the event is
    /// then not visible in memory either.
    pub fn append(&self, event_type: impl Into<String>, payload: Value) -> Result<u64, LogError> {
        let mut inner = self.lock();
        Self::append_locked(&mut inner, event_type.into(), payload)
    }

    fn append_locked(
        inner: &mut Inner,
        event_type: String,
        payload: Value,
    ) -> Result<u64, LogError> {
        Self::append_locked_at(inner, event_type, payload, now_ms())
    }

    /// Append with an explicit event timestamp. Recovery events pass the
    /// last real event's time so a repaired log is byte-identical across
    /// runs (deterministic snapshots) and never invents a future time.
    fn append_locked_at(
        inner: &mut Inner,
        event_type: String,
        payload: Value,
        time_ms: i64,
    ) -> Result<u64, LogError> {
        let seq = inner.events.len() as u64;
        let event = SessionEvent {
            seq,
            event_type,
            time_ms,
            payload,
        };
        if let Some(store) = inner.store.as_mut() {
            let line = serde_json::to_vec(&LogRecord::Event {
                event: event.clone(),
            })
            .expect("event serialization is infallible");
            store.append_line(&line)?;
        }
        inner.events.push(event);
        Ok(seq)
    }

    /// All events in log order.
    #[must_use]
    pub fn events(&self) -> Vec<SessionEvent> {
        self.lock().events.clone()
    }

    /// Number of events appended so far; the next `append` receives this
    /// value as its seq.
    #[must_use]
    pub fn event_count(&self) -> u64 {
        self.lock().events.len() as u64
    }

    /// Latest canonical request header folded from `request/header` events,
    /// or `None` before the first snapshot.
    ///
    /// # Errors
    /// Returns [`LogError::MalformedRecord`] when a `request/header` payload
    /// does not deserialize — the log is corrupt, not merely header-less.
    pub fn request_header(&self) -> Result<Option<EpochHeader>, LogError> {
        fold_request_header(&self.lock().events, None).map_err(|e| LogError::MalformedRecord {
            line: 0,
            reason: format!("request/header payload: {e}"),
        })
    }

    /// Latest route metadata folded from `request/context` events.
    ///
    /// # Errors
    /// Returns [`LogError::MalformedRecord`] when a `request/context` payload
    /// does not deserialize.
    pub fn request_context(&self) -> Result<Option<RequestContext>, LogError> {
        fold_request_context(&self.lock().events).map_err(|e| LogError::MalformedRecord {
            line: 0,
            reason: format!("request/context payload: {e}"),
        })
    }

    /// Projects model history from events with `seq < upto`.
    ///
    /// Closed-core rules cover `user/message`, `assistant/message`,
    /// `tool/result`; any other event type joins the history only through a
    /// self-describing `model_message` payload field (ANDROID-PLAN §4.0).
    /// `assistant/chunk` events are replay fidelity, not history.
    ///
    /// Projection walks an ordered surface (upstream `deriveMessages` parity):
    /// a message-producing event with no `surfaceOp` payload field (or
    /// `"append"`) appends its projection; an event carrying
    /// `surfaceOp: "replace"` plus `sourceEventSeqs: [seq...]` first
    /// shadows the projections of the cited events, then enters the
    /// surface at the first cited node's position (positional replacement
    /// — a compaction summary occupies the replaced range's place, a
    /// pruned tool result keeps its original slot). A replace whose own
    /// payload does not project is inert (logged but skipped here).
    /// Citations that match no live surface node shadow nothing, so the
    /// replace degenerates to an append at the surface end — this is a
    /// projection, not validation — the Kotlin writer owns accept-time
    /// checks, and a failed replacement never silently drops
    /// model-visible content.
    #[must_use]
    pub fn derive_messages(&self, upto: u64) -> Vec<ModelMessage> {
        let inner = self.lock();
        let mut surface: Vec<(u64, ModelMessage)> = Vec::new();
        for event in inner.events.iter().filter(|e| e.seq < upto) {
            let is_replace =
                event.payload.get("surfaceOp").and_then(Value::as_str) == Some("replace");
            if !is_replace {
                if let Some(message) = project_event(event) {
                    surface.push((event.seq, message));
                }
                continue;
            }
            let Some(message) = project_event(event) else {
                continue;
            };
            let cited: Vec<u64> = event
                .payload
                .get("sourceEventSeqs")
                .and_then(Value::as_array)
                .map(|items| items.iter().filter_map(Value::as_u64).collect())
                .unwrap_or_default();
            let insert_at = surface
                .iter()
                .position(|(seq, _)| cited.contains(seq))
                .unwrap_or(surface.len());
            surface.retain(|(seq, _)| !cited.contains(seq));
            surface.insert(insert_at, (event.seq, message));
        }
        surface.into_iter().map(|(_, message)| message).collect()
    }

    /// Closes turns left open by a crash, unwinding LIFO (most recent open
    /// turn first). Steps and tool calls are attributed to the turn they
    /// were opened in, so each orphaned turn's burial contains exactly its
    /// own dangling machinery. For each orphaned turn the repair appends,
    /// in order:
    ///
    /// 1. a synthesized error `tool/result` for every tool call with no
    ///    recorded result (so the derived history has no dangling call —
    ///    parity with upstream's `interruptedTurnClosers`), carrying one of two
    ///    codes: `TOOL_OUTCOME_UNKNOWN` for a call whose `tool/call` was
    ///    logged (side effects may have landed — verify before retrying)
    ///    or `TOOL_NOT_STARTED` for a call interrupted before the harness
    ///    recorded its start (safe to retry as-is);
    /// 2. `step/end {kind: "interrupted"}` for every open step;
    /// 3. `turn/end {kind: "interrupted"}`.
    ///
    /// Steps and calls orphaned under a turn that already closed (or logged
    /// outside any turn — both are corruption the loop never produces) are
    /// repaired after all turn burials, in the same component order minus
    /// the `turn/end`.
    ///
    /// All synthesized events reuse the last real event's timestamp: the
    /// repaired log is byte-identical across runs of the same input
    /// (deterministic snapshot replay) and never invents a future time.
    ///
    /// Returns the seqs of the `turn/start` events that were closed. Call
    /// point is fixed (ANDROID-PLAN M2): after log load, before the agent
    /// loop starts, so recovery precedes any new turn.
    ///
    /// # Errors
    /// Returns [`LogError::Store`] if a recovery append fails.
    pub fn close_interrupted_turns(&self) -> Result<Vec<u64>, LogError> {
        let mut inner = self.lock();
        let mut frames: Vec<TurnFrame> = Vec::new();
        let mut stray_steps: Vec<u64> = Vec::new();
        let mut stray_calls: Vec<PendingCall> = Vec::new();
        for event in &inner.events {
            match event.event_type.as_str() {
                "turn/start" => frames.push(TurnFrame {
                    turn_seq: event.seq,
                    open_steps: Vec::new(),
                    open_calls: Vec::new(),
                }),
                "turn/end" => {
                    if let Some(frame) = frames.pop() {
                        // Leftovers under a closed turn cannot be attributed
                        // to a burial of that turn anymore; hand them to the
                        // nearest still-open ancestor, else the session-level
                        // repair bucket.
                        match frames.last_mut() {
                            Some(parent) => {
                                parent.open_steps.extend(frame.open_steps);
                                parent.open_calls.extend(frame.open_calls);
                            }
                            None => {
                                stray_steps.extend(frame.open_steps);
                                stray_calls.extend(frame.open_calls);
                            }
                        }
                    }
                }
                "step/start" => match frames.last_mut() {
                    Some(frame) => frame.open_steps.push(event.seq),
                    None => stray_steps.push(event.seq),
                },
                "step/end" => {
                    // Frames nest, so the globally most recent open step is
                    // the last element of the deepest frame holding any;
                    // fall back to the stray bucket.
                    match frames.iter_mut().rev().find(|f| !f.open_steps.is_empty()) {
                        Some(frame) => {
                            frame.open_steps.pop();
                        }
                        None => {
                            stray_steps.pop();
                        }
                    }
                }
                "assistant/message" => {
                    // The assistant message carries the call blocks; each is
                    // pending (not started) until a `tool/call` event with
                    // the same id is logged.
                    if let Some(calls) = event.payload.get("tool_calls").and_then(Value::as_array) {
                        for id in calls
                            .iter()
                            .filter_map(|c| c.get("id").and_then(Value::as_str))
                        {
                            let pending = PendingCall {
                                id: id.to_string(),
                                started: false,
                            };
                            match frames.last_mut() {
                                Some(frame) => frame.open_calls.push(pending),
                                None => stray_calls.push(pending),
                            }
                        }
                    }
                }
                "tool/call" => {
                    if let Some(id) = event.payload.get("tool_call_id").and_then(Value::as_str) {
                        // Mark the oldest pending call with this id as
                        // started. A call with no registered pending (a log
                        // whose assistant message is absent or corrupt)
                        // registers here as started, so it still gets a
                        // result and no dangling call reaches the model.
                        if !mark_call_started(&mut frames, &mut stray_calls, id) {
                            let pending = PendingCall {
                                id: id.to_string(),
                                started: true,
                            };
                            match frames.last_mut() {
                                Some(frame) => frame.open_calls.push(pending),
                                None => stray_calls.push(pending),
                            }
                        }
                    }
                }
                "tool/result" => {
                    if let Some(id) = event.payload.get("tool_call_id").and_then(Value::as_str) {
                        // Pair with the oldest matching call regardless of
                        // frame, so a recorded result is never re-synthesized.
                        let mut paired = false;
                        for frame in &mut frames {
                            if let Some(pos) = frame.open_calls.iter().position(|c| c.id == id) {
                                frame.open_calls.remove(pos);
                                paired = true;
                                break;
                            }
                        }
                        if !paired {
                            if let Some(pos) = stray_calls.iter().position(|c| c.id == id) {
                                stray_calls.remove(pos);
                            }
                        }
                    }
                }
                _ => {}
            }
        }
        let recovery_time = inner.events.last().map_or(0, |event| event.time_ms);
        let mut closed = Vec::new();
        while let Some(mut frame) = frames.pop() {
            for call in &frame.open_calls {
                append_interrupted_result(&mut inner, call, recovery_time)?;
            }
            while let Some(step_seq) = frame.open_steps.pop() {
                Self::append_locked_at(
                    &mut inner,
                    "step/end".to_string(),
                    serde_json::json!({
                        "kind": "interrupted",
                        "interrupted_step_seq": step_seq,
                    }),
                    recovery_time,
                )?;
            }
            Self::append_locked_at(
                &mut inner,
                "turn/end".to_string(),
                serde_json::json!({
                    "kind": "interrupted",
                    "interrupted_turn_seq": frame.turn_seq,
                }),
                recovery_time,
            )?;
            closed.push(frame.turn_seq);
        }
        for call in &stray_calls {
            append_interrupted_result(&mut inner, call, recovery_time)?;
        }
        while let Some(step_seq) = stray_steps.pop() {
            Self::append_locked_at(
                &mut inner,
                "step/end".to_string(),
                serde_json::json!({
                    "kind": "interrupted",
                    "interrupted_step_seq": step_seq,
                }),
                recovery_time,
            )?;
        }
        closed.sort_unstable();
        Ok(closed)
    }

    /// Flushes the store at checkpoint boundaries (durable before every model
    /// request). No-op on an in-memory log.
    ///
    /// # Errors
    /// Returns [`LogError::Store`] if the fsync fails.
    pub fn flush(&self) -> Result<(), LogError> {
        let mut inner = self.lock();
        if let Some(store) = inner.store.as_mut() {
            store.flush()?;
        }
        Ok(())
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

/// Marks the oldest pending call with `id` as started, searching frames in
/// order (oldest first) and then the stray bucket. Returns whether a
/// pending call was found.
fn mark_call_started(frames: &mut [TurnFrame], stray_calls: &mut [PendingCall], id: &str) -> bool {
    for frame in frames {
        if let Some(pending) = frame.open_calls.iter_mut().find(|c| c.id == id) {
            pending.started = true;
            return true;
        }
    }
    if let Some(pending) = stray_calls.iter_mut().find(|c| c.id == id) {
        pending.started = true;
        return true;
    }
    false
}

/// Appends the synthesized `tool/result` for one interrupted call. The
/// error code and model-facing guidance depend on whether the harness
/// recorded the call's start (upstream `interruptedTurnClosers` texts).
fn append_interrupted_result(
    inner: &mut Inner,
    call: &PendingCall,
    time_ms: i64,
) -> Result<(), LogError> {
    let (code, message, content) = if call.started {
        (
            "TOOL_OUTCOME_UNKNOWN",
            "session interrupted after the tool call was recorded; outcome unknown",
            "The tool call was interrupted after it was recorded, but no result was durably recorded. Its outcome is unknown. Decide whether to retry from the tool semantics: retry only if the operation is read-only or idempotent; if it may have side effects, first verify external state or ask the user. Do not retry blindly.",
        )
    } else {
        (
            "TOOL_NOT_STARTED",
            "session interrupted before the tool call was recorded as started",
            "The tool call was interrupted before the Harness recorded it as started. Retry it if it is still needed.",
        )
    };
    SessionLog::append_locked_at(
        inner,
        "tool/result".to_string(),
        serde_json::json!({
            "tool_call_id": call.id,
            "content": content,
            "error": {
                "code": code,
                "message": message,
            },
        }),
        time_ms,
    )?;
    Ok(())
}

/// Closed-core plus self-describing projection of a single event — the
/// per-event step of [`SessionLog::derive_messages`], shared with pricing
/// folds that need one event's message without folding the whole surface.
#[must_use]
pub fn project_message(event: &SessionEvent) -> Option<ModelMessage> {
    project_event(event)
}

/// Closed-core plus self-describing projection of a single event.
fn project_event(event: &SessionEvent) -> Option<ModelMessage> {
    match event.event_type.as_str() {
        "user/message" => Some(ModelMessage {
            role: "user".to_string(),
            content: string_or_json(event.payload.get("content")?),
            tool_calls: Vec::new(),
            tool_call_id: None,
            name: event
                .payload
                .get("name")
                .and_then(Value::as_str)
                .map(str::to_string),
        }),
        "assistant/message" => Some(ModelMessage {
            role: "assistant".to_string(),
            content: string_or_json(event.payload.get("content")?),
            tool_calls: parse_tool_calls(event.payload.get("tool_calls")),
            tool_call_id: None,
            name: None,
        }),
        "tool/result" => Some(ModelMessage {
            role: "tool".to_string(),
            content: string_or_json(event.payload.get("content")?),
            tool_calls: Vec::new(),
            tool_call_id: event
                .payload
                .get("tool_call_id")
                .and_then(Value::as_str)
                .map(str::to_string),
            name: event
                .payload
                .get("name")
                .and_then(Value::as_str)
                .map(str::to_string),
        }),
        _ => {
            let rendered = event.payload.get("model_message")?;
            serde_json::from_value(rendered.clone()).ok()
        }
    }
}

fn parse_tool_calls(value: Option<&Value>) -> Vec<ToolCall> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };
    items
        .iter()
        .filter_map(|item| {
            Some(ToolCall {
                id: item.get("id")?.as_str()?.to_string(),
                name: item.get("name")?.as_str()?.to_string(),
                arguments_json: item
                    .get("arguments_json")
                    .or_else(|| item.get("arguments"))
                    .map(string_or_json)?,
            })
        })
        .collect()
}

fn string_or_json(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        other => serde_json::to_string(other).unwrap_or_default(),
    }
}

fn parse_record(bytes: &[u8], line: usize) -> Result<LogRecord, LogError> {
    serde_json::from_slice(bytes).map_err(|e| LogError::MalformedRecord {
        line,
        reason: e.to_string(),
    })
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn header() -> SessionHeader {
        SessionHeader::new("s-1", 1_750_000_000_000)
    }

    #[test]
    fn append_assigns_contiguous_seqs_and_projects_core_messages() {
        let log = SessionLog::in_memory(header());
        let s0 = log.append("turn/start", json!({})).unwrap();
        let s1 = log.append("step/start", json!({})).unwrap();
        let s2 = log
            .append("user/message", json!({"content": "hi"}))
            .unwrap();
        let s3 = log
            .append(
                "assistant/message",
                json!({
                    "content": "calling tool",
                    "tool_calls": [{"id": "c1", "name": "fs_read", "arguments": {"path": "/a"}}],
                }),
            )
            .unwrap();
        let s4 = log
            .append(
                "tool/result",
                json!({"tool_call_id": "c1", "content": "file body"}),
            )
            .unwrap();
        assert_eq!((s0, s1, s2, s3, s4), (0, 1, 2, 3, 4));

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 3);
        assert_eq!(messages[0].role, "user");
        assert_eq!(messages[0].content, "hi");
        assert_eq!(messages[1].role, "assistant");
        assert_eq!(
            messages[1].tool_calls,
            vec![ToolCall {
                id: "c1".into(),
                name: "fs_read".into(),
                arguments_json: r#"{"path":"/a"}"#.into(),
            }]
        );
        assert_eq!(messages[2].role, "tool");
        assert_eq!(messages[2].tool_call_id.as_deref(), Some("c1"));
    }

    #[test]
    fn derive_messages_honours_upto_boundary() {
        let log = SessionLog::in_memory(header());
        log.append("user/message", json!({"content": "one"}))
            .unwrap();
        log.append("assistant/message", json!({"content": "two"}))
            .unwrap();
        assert_eq!(log.derive_messages(1).len(), 1);
        assert_eq!(log.derive_messages(2).len(), 2);
    }

    #[test]
    fn chunks_do_not_enter_history_but_self_describing_events_do() {
        let log = SessionLog::in_memory(header());
        log.append("assistant/chunk", json!({"text": "partial"}))
            .unwrap();
        log.append(
            "context/time",
            json!({
                "model_message": {"role": "system", "content": "it is noon"},
            }),
        )
        .unwrap();
        log.append("mystery/event", json!({"foo": 1})).unwrap();

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].role, "system");
        assert_eq!(messages[0].content, "it is noon");
    }

    #[test]
    fn close_interrupted_turns_buries_orphans_and_appends_recovery_events() {
        let log = SessionLog::in_memory(header());
        log.append("turn/start", json!({})).unwrap();
        log.append("turn/end", json!({"kind": "completed"}))
            .unwrap();
        let orphan_a = log.append("turn/start", json!({})).unwrap();
        let orphan_b = log.append("turn/start", json!({})).unwrap();

        let closed = log.close_interrupted_turns().unwrap();
        assert_eq!(closed, vec![orphan_a, orphan_b]);

        let events = log.events();
        let recovery: Vec<&SessionEvent> = events
            .iter()
            .filter(|e| e.event_type == "turn/end" && e.payload["kind"] == "interrupted")
            .collect();
        assert_eq!(recovery.len(), 2);
        // Orphans unwind LIFO: the most recent open turn is buried first.
        assert_eq!(recovery[0].payload["interrupted_turn_seq"], orphan_b);
        assert_eq!(recovery[1].payload["interrupted_turn_seq"], orphan_a);

        // Recovery is idempotent: everything is now closed.
        assert!(log.close_interrupted_turns().unwrap().is_empty());
    }

    #[test]
    fn recovery_synthesizes_tool_results_and_step_boundaries() {
        let log = SessionLog::in_memory(header());
        log.append("turn/start", json!({})).unwrap();
        let step = log.append("step/start", json!({})).unwrap();
        log.append(
            "tool/call",
            json!({"tool_call_id": "c9", "name": "fs_read"}),
        )
        .unwrap();

        let closed = log.close_interrupted_turns().unwrap();
        assert_eq!(closed.len(), 1);

        let events = log.events();
        let repair_types: Vec<&str> = events[3..].iter().map(|e| e.event_type.as_str()).collect();
        assert_eq!(repair_types, ["tool/result", "step/end", "turn/end"]);
        let result = &events[3];
        assert_eq!(result.payload["tool_call_id"], "c9");
        assert_eq!(result.payload["error"]["code"], "TOOL_OUTCOME_UNKNOWN");
        assert_eq!(events[4].payload["interrupted_step_seq"], step);
        // The synthesized result enters derived history: no dangling call.
        let messages = log.derive_messages(u64::MAX);
        assert!(messages
            .iter()
            .any(|m| m.role == "tool" && m.tool_call_id.as_deref() == Some("c9")));
        // Paired calls are not re-synthesized; recovery stays idempotent.
        assert!(log.close_interrupted_turns().unwrap().is_empty());
    }

    #[test]
    fn multiple_orphan_turns_bury_their_own_steps_and_calls() {
        let log = SessionLog::in_memory(header());
        let turn_a = log.append("turn/start", json!({})).unwrap();
        let step_a = log.append("step/start", json!({})).unwrap();
        log.append(
            "tool/call",
            json!({"tool_call_id": "cA", "name": "fs_read"}),
        )
        .unwrap();
        let turn_b = log.append("turn/start", json!({})).unwrap();
        let step_b = log.append("step/start", json!({})).unwrap();
        log.append(
            "tool/call",
            json!({"tool_call_id": "cB", "name": "fs_read"}),
        )
        .unwrap();
        assert_eq!(
            (turn_a, step_a, turn_b, step_b),
            (0, 1, 3, 4),
            "fixture: two orphaned turns, each with its own step and call"
        );

        let closed = log.close_interrupted_turns().unwrap();
        assert_eq!(closed, vec![turn_a, turn_b]);

        // LIFO burial, each turn's repair naming only its own machinery.
        let events = log.events();
        let repair_types: Vec<&str> = events[6..].iter().map(|e| e.event_type.as_str()).collect();
        assert_eq!(
            repair_types,
            [
                "tool/result",
                "step/end",
                "turn/end",
                "tool/result",
                "step/end",
                "turn/end"
            ]
        );
        assert_eq!(events[6].payload["tool_call_id"], "cB");
        assert_eq!(events[7].payload["interrupted_step_seq"], step_b);
        assert_eq!(events[8].payload["interrupted_turn_seq"], turn_b);
        assert_eq!(events[9].payload["tool_call_id"], "cA");
        assert_eq!(events[10].payload["interrupted_step_seq"], step_a);
        assert_eq!(events[11].payload["interrupted_turn_seq"], turn_a);

        // Both calls are paired in the derived history: no dangling call.
        let messages = log.derive_messages(u64::MAX);
        for id in ["cA", "cB"] {
            assert!(
                messages
                    .iter()
                    .any(|m| m.role == "tool" && m.tool_call_id.as_deref() == Some(id)),
                "call {id} must have a synthesized result in history"
            );
        }

        // Idempotent: recovery events pair everything, second pass is a no-op.
        assert!(log.close_interrupted_turns().unwrap().is_empty());
        assert_eq!(log.events().len(), 12);
    }

    #[test]
    fn leftovers_under_a_closed_turn_get_session_level_repair() {
        let log = SessionLog::in_memory(header());
        log.append("turn/start", json!({})).unwrap(); // turn A (seq 0)
        let step_a = log.append("step/start", json!({})).unwrap(); // seq 1
        log.append(
            "tool/call",
            json!({"tool_call_id": "cA", "name": "fs_read"}),
        )
        .unwrap(); // seq 2
        log.append("turn/end", json!({"kind": "completed"}))
            .unwrap(); // A closes with step/call dangling (corruption)
        let turn_b = log.append("turn/start", json!({})).unwrap(); // orphan, seq 4
        let step_b = log.append("step/start", json!({})).unwrap(); // seq 5
        log.append(
            "tool/call",
            json!({"tool_call_id": "cB", "name": "fs_read"}),
        )
        .unwrap(); // seq 6

        let closed = log.close_interrupted_turns().unwrap();
        // Only B is an orphaned turn; A already closed and must not gain a
        // second turn/end. A's leftovers are repaired after B's burial.
        assert_eq!(closed, vec![turn_b]);

        let events = log.events();
        let repair_types: Vec<&str> = events[7..].iter().map(|e| e.event_type.as_str()).collect();
        assert_eq!(
            repair_types,
            [
                "tool/result",
                "step/end",
                "turn/end",
                "tool/result",
                "step/end"
            ]
        );
        assert_eq!(events[7].payload["tool_call_id"], "cB");
        assert_eq!(events[8].payload["interrupted_step_seq"], step_b);
        assert_eq!(events[9].payload["interrupted_turn_seq"], turn_b);
        assert_eq!(events[10].payload["tool_call_id"], "cA");
        assert_eq!(events[11].payload["interrupted_step_seq"], step_a);

        // No dangling call even for the closed turn's leftover.
        let messages = log.derive_messages(u64::MAX);
        for id in ["cA", "cB"] {
            assert!(
                messages
                    .iter()
                    .any(|m| m.role == "tool" && m.tool_call_id.as_deref() == Some(id)),
                "call {id} must have a synthesized result in history"
            );
        }

        assert!(log.close_interrupted_turns().unwrap().is_empty());
        assert_eq!(log.events().len(), 12);
    }

    #[test]
    fn recovery_distinguishes_not_started_from_outcome_unknown_calls() {
        let log = SessionLog::in_memory(header());
        log.append("turn/start", json!({})).unwrap();
        log.append("step/start", json!({})).unwrap();
        // Both calls hang off one assistant message. c1 crashes before its
        // `tool/call` is logged (never started); c2 is started but its
        // result never lands.
        log.append(
            "assistant/message",
            json!({
                "content": "calling tools",
                "tool_calls": [
                    {"id": "c1", "name": "fs_read", "arguments": {}},
                    {"id": "c2", "name": "fs_read", "arguments": {}},
                ],
            }),
        )
        .unwrap();
        log.append(
            "tool/call",
            json!({"tool_call_id": "c2", "name": "fs_read"}),
        )
        .unwrap();

        log.close_interrupted_turns().unwrap();

        let events = log.events();
        let repair_types: Vec<&str> = events[4..].iter().map(|e| e.event_type.as_str()).collect();
        assert_eq!(
            repair_types,
            ["tool/result", "tool/result", "step/end", "turn/end"]
        );
        // c1 first (registration order), with the retry-safe code and text.
        assert_eq!(events[4].payload["tool_call_id"], "c1");
        assert_eq!(events[4].payload["error"]["code"], "TOOL_NOT_STARTED");
        assert_eq!(
            events[4].payload["content"],
            "The tool call was interrupted before the Harness recorded it as started. Retry it if it is still needed."
        );
        // c2 second, with the verify-before-retry code and text.
        assert_eq!(events[5].payload["tool_call_id"], "c2");
        assert_eq!(events[5].payload["error"]["code"], "TOOL_OUTCOME_UNKNOWN");
        assert!(events[5].payload["content"]
            .as_str()
            .expect("content is a string")
            .starts_with("The tool call was interrupted after it was recorded"));

        // Both calls pair in history: no dangling call reaches the model.
        let messages = log.derive_messages(u64::MAX);
        for id in ["c1", "c2"] {
            assert!(
                messages
                    .iter()
                    .any(|m| m.role == "tool" && m.tool_call_id.as_deref() == Some(id)),
                "call {id} must have a synthesized result in history"
            );
        }
        assert!(log.close_interrupted_turns().unwrap().is_empty());
    }

    #[test]
    fn recovery_events_reuse_the_last_real_events_timestamp() {
        let log = SessionLog::in_memory(header());
        log.append("turn/start", json!({})).unwrap();
        let step = log.append("step/start", json!({})).unwrap();
        log.append(
            "tool/call",
            json!({"tool_call_id": "c7", "name": "fs_read"}),
        )
        .unwrap();
        let last_time = log.events().last().expect("events exist").time_ms;

        // Guarantee the wall clock ticks past `last_time` before recovery
        // runs, so a fresh `now_ms()` stamp would visibly differ.
        std::thread::sleep(std::time::Duration::from_millis(5));
        log.close_interrupted_turns().unwrap();

        let events = log.events();
        assert_eq!(events.len(), 6);
        for event in &events[3..] {
            assert_eq!(
                event.time_ms, last_time,
                "recovery event {} must reuse the last real event's time",
                event.seq
            );
        }
        assert_eq!(events[3].payload["tool_call_id"], "c7");
        assert_eq!(events[4].payload["interrupted_step_seq"], step);
    }

    #[test]
    fn surface_replace_shadows_the_cited_tool_result() {
        let log = SessionLog::in_memory(header());
        log.append("user/message", json!({"content": "read the file"}))
            .unwrap();
        log.append(
            "assistant/message",
            json!({
                "content": "reading",
                "tool_calls": [{"id": "c1", "name": "fs_read", "arguments": {}}],
            }),
        )
        .unwrap();
        log.append(
            "tool/call",
            json!({"tool_call_id": "c1", "name": "fs_read"}),
        )
        .unwrap();
        let original = log
            .append(
                "tool/result",
                json!({"tool_call_id": "c1", "content": "huge output"}),
            )
            .unwrap();
        // A result pruner replaces the oversized content, citing the
        // original result event.
        log.append(
            "tool/result",
            json!({
                "tool_call_id": "c1",
                "content": "[pruned: original exceeded the budget]",
                "surfaceOp": "replace",
                "sourceEventSeqs": [original],
            }),
        )
        .unwrap();

        let messages = log.derive_messages(u64::MAX);
        let tool_messages: Vec<&ModelMessage> =
            messages.iter().filter(|m| m.role == "tool").collect();
        assert_eq!(tool_messages.len(), 1, "original result is shadowed");
        assert_eq!(
            tool_messages[0].content, "[pruned: original exceeded the budget]",
            "the replacement's content is what the model sees"
        );
        // Positional replacement: the pruned result keeps the original's
        // slot, after the assistant message that carries the call.
        assert_eq!(messages.len(), 3);
        assert_eq!(messages[0].role, "user");
        assert_eq!(messages[1].role, "assistant");
        assert_eq!(messages[2].role, "tool");
        assert_eq!(messages[2].tool_call_id.as_deref(), Some("c1"));
    }

    #[test]
    fn compaction_replace_shadows_a_cited_range_in_place() {
        let log = SessionLog::in_memory(header());
        let q1 = log
            .append("user/message", json!({"content": "q1"}))
            .unwrap();
        let a1 = log
            .append("assistant/message", json!({"content": "a1"}))
            .unwrap();
        let q2 = log
            .append("user/message", json!({"content": "q2"}))
            .unwrap();
        log.append("assistant/message", json!({"content": "a2"}))
            .unwrap();

        // A compaction summary replaces the first three nodes.
        log.append(
            "assistant/message",
            json!({
                "content": "summary of the replaced range",
                "surfaceOp": "replace",
                "sourceEventSeqs": [q1, a1, q2],
            }),
        )
        .unwrap();

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 2);
        assert_eq!(messages[0].content, "summary of the replaced range");
        assert_eq!(messages[1].content, "a2");
    }

    #[test]
    fn a_replace_beyond_upto_does_not_shadow_below_it() {
        let log = SessionLog::in_memory(header());
        let original = log
            .append(
                "tool/result",
                json!({"tool_call_id": "c1", "content": "original"}),
            )
            .unwrap();
        let replace_seq = log
            .append(
                "tool/result",
                json!({
                    "tool_call_id": "c1",
                    "content": "replaced",
                    "surfaceOp": "replace",
                    "sourceEventSeqs": [original],
                }),
            )
            .unwrap();

        // Reading up to the replace event keeps the original content
        // visible (a `seq < upto` filter applies to replaces too).
        let before = log.derive_messages(replace_seq);
        assert_eq!(before.len(), 1);
        assert_eq!(before[0].content, "original");

        let after = log.derive_messages(u64::MAX);
        assert_eq!(after.len(), 1);
        assert_eq!(after[0].content, "replaced");
    }

    #[test]
    fn a_later_replace_can_shadow_a_replacement() {
        let log = SessionLog::in_memory(header());
        let user = log.append("user/message", json!({"content": "q"})).unwrap();
        let first = log
            .append(
                "assistant/message",
                json!({
                    "content": "summary v1",
                    "surfaceOp": "replace",
                    "sourceEventSeqs": [user],
                }),
            )
            .unwrap();
        log.append(
            "assistant/message",
            json!({
                "content": "summary v2",
                "surfaceOp": "replace",
                "sourceEventSeqs": [first],
            }),
        )
        .unwrap();

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].content, "summary v2");
    }

    #[test]
    fn a_replace_citing_no_live_node_degenerates_to_an_append() {
        let log = SessionLog::in_memory(header());
        let _user = log.append("user/message", json!({"content": "q"})).unwrap();
        log.append(
            "assistant/message",
            json!({
                "content": "summary",
                "surfaceOp": "replace",
                "sourceEventSeqs": [42], // cites nothing on the surface
            }),
        )
        .unwrap();

        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 2);
        assert_eq!(messages[0].content, "q");
        assert_eq!(messages[1].content, "summary");
    }

    #[test]
    fn load_tolerates_and_repairs_a_torn_tail_from_crash_mid_write() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        {
            let log = SessionLog::create(JsonlStore::open(&path).unwrap(), header()).unwrap();
            log.append("user/message", json!({"content": "kept"}))
                .unwrap();
            log.flush().unwrap();
        }
        // Crash mid-write: partial record bytes after the last newline.
        // Byte-level rewrite avoids holding a second append-only handle,
        // which would block the repair truncate on Windows.
        let mut bytes = std::fs::read(&path).unwrap();
        bytes.extend_from_slice(b"{\"record\":\"ev");
        std::fs::write(&path, bytes).unwrap();

        let log = SessionLog::load(JsonlStore::open(&path).unwrap()).unwrap();
        assert_eq!(log.events().len(), 1);
        // The repair is visible, not silent: exactly the torn residue is reported.
        assert_eq!(log.repaired_torn_tail_bytes(), b"{\"record\":\"ev".len());
        // The file was repaired: torn bytes are gone, appends continue cleanly.
        let seq = log
            .append("assistant/message", json!({"content": "after repair"}))
            .unwrap();
        assert_eq!(seq, 1);
        let reloaded = SessionLog::load(JsonlStore::open(&path).unwrap()).unwrap();
        assert_eq!(reloaded.events().len(), 2);
        assert_eq!(reloaded.repaired_torn_tail_bytes(), 0);
    }

    #[test]
    fn persistence_round_trip_and_append_after_load() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        {
            let log = SessionLog::create(JsonlStore::open(&path).unwrap(), header()).unwrap();
            log.append("user/message", json!({"content": "persisted"}))
                .unwrap();
            log.flush().unwrap();
        }
        let log = SessionLog::load(JsonlStore::open(&path).unwrap()).unwrap();
        assert_eq!(log.header(), header());
        assert_eq!(log.events().len(), 1);
        let seq = log
            .append("assistant/message", json!({"content": "continued"}))
            .unwrap();
        assert_eq!(seq, 1);
        assert_eq!(log.derive_messages(u64::MAX).len(), 2);
    }

    #[test]
    fn load_rejects_foreign_format_version() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        std::fs::write(
            &path,
            "{\"record\":\"header\",\"header\":{\"format_version\":99,\"session_id\":\"x\",\"created_at_ms\":0}}\n",
        )
        .unwrap();
        let result = SessionLog::load(JsonlStore::open(&path).unwrap());
        assert!(matches!(
            result.map(|_| ()),
            Err(LogError::UnsupportedFormatVersion {
                found: 99,
                supported: FORMAT_VERSION
            })
        ));
    }

    #[test]
    fn load_rejects_seq_gap() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        let mut file = String::from(
            "{\"record\":\"header\",\"header\":{\"format_version\":0,\"session_id\":\"x\",\"created_at_ms\":0}}\n",
        );
        file.push_str(
            "{\"record\":\"event\",\"event\":{\"seq\":0,\"type\":\"turn/start\",\"payload\":{}}}\n",
        );
        file.push_str(
            "{\"record\":\"event\",\"event\":{\"seq\":7,\"type\":\"turn/end\",\"payload\":{}}}\n",
        );
        std::fs::write(&path, file).unwrap();
        let result = SessionLog::load(JsonlStore::open(&path).unwrap());
        assert!(matches!(
            result.map(|_| ()),
            Err(LogError::SeqDiscontinuity {
                index: 1,
                expected: 1,
                found: 7
            })
        ));
    }

    #[test]
    fn load_rejects_missing_header_and_duplicate_header() {
        let dir = tempfile::tempdir().unwrap();
        let empty = dir.path().join("empty.jsonl");
        std::fs::write(&empty, "").unwrap();
        assert!(matches!(
            SessionLog::load(JsonlStore::open(&empty).unwrap()),
            Err(LogError::MissingHeader)
        ));

        let dup = dir.path().join("dup.jsonl");
        let line =
            "{\"record\":\"header\",\"header\":{\"format_version\":0,\"session_id\":\"x\",\"created_at_ms\":0}}\n";
        std::fs::write(&dup, format!("{line}{line}")).unwrap();
        assert!(matches!(
            SessionLog::load(JsonlStore::open(&dup).unwrap()),
            Err(LogError::MalformedRecord { line: 2, .. })
        ));
    }
}
