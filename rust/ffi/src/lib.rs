//! ffi: the UniFFI interface layer — the only window between the Kotlin
//! behavior kernel and the Rust data spine.
//!
//! Contract rules (ANDROID-PLAN §4.2): everything crossing the boundary is
//! data (records, plain values, JSON strings for opaque payloads); the single
//! callback shape is the coarse-grained [`SseSink`]. UniFFI scaffolding wraps
//! every export in `catch_unwind`, so a Rust panic surfaces as an internal
//! FFI error and never unwinds into the JVM/ART.
//!
//! Deliberately not exported: `session_log`'s JSONL import bridge
//! (`import_jsonl`) — it is a validation-only surface for replaying upstream
//! fixtures and never feeds the product FFI face.

use std::sync::{Arc, Mutex};

use codec::{SseEvent, SseParser};
use compose::{EntryRow, PatchLayer};
use session_log::{LogError, ModelMessage, SessionEvent, SessionHeader, SessionLog};
use store::JsonlStore;

uniffi::setup_scaffolding!();

// ---------------------------------------------------------------------------
// Records (all data, no behavior)
// ---------------------------------------------------------------------------

/// FFI mirror of [`SessionHeader`].
#[derive(uniffi::Record)]
pub struct FfiSessionHeader {
    /// Log format version; must equal the engine's supported version.
    pub format_version: u32,
    /// Opaque session identifier assigned by the host.
    pub session_id: String,
    /// Creation time, milliseconds since the Unix epoch.
    pub created_at_ms: i64,
}

impl From<FfiSessionHeader> for SessionHeader {
    fn from(h: FfiSessionHeader) -> Self {
        SessionHeader {
            format_version: h.format_version,
            session_id: h.session_id,
            created_at_ms: h.created_at_ms,
        }
    }
}

impl From<SessionHeader> for FfiSessionHeader {
    fn from(h: SessionHeader) -> Self {
        FfiSessionHeader {
            format_version: h.format_version,
            session_id: h.session_id,
            created_at_ms: h.created_at_ms,
        }
    }
}

/// FFI mirror of [`SessionEvent`]; the payload crosses as a JSON string.
#[derive(uniffi::Record)]
pub struct FfiSessionEvent {
    /// Zero-based position in the append-only sequence.
    pub seq: u64,
    /// Event discriminant.
    pub event_type: String,
    /// Wall clock at append, milliseconds since the Unix epoch.
    pub time_ms: i64,
    /// Payload serialized as JSON.
    pub payload_json: String,
}

/// One tool call inside an assistant message.
#[derive(uniffi::Record)]
pub struct FfiToolCall {
    /// Provider-assigned call id.
    pub id: String,
    /// Registered tool name.
    pub name: String,
    /// Arguments as a JSON string.
    pub arguments_json: String,
}

/// One message of the derived model history.
#[derive(uniffi::Record)]
pub struct FfiModelMessage {
    /// `user` | `assistant` | `tool` | extension roles.
    pub role: String,
    /// Rendered text content.
    pub content: String,
    /// Tool calls issued by an assistant message.
    pub tool_calls: Vec<FfiToolCall>,
    /// Correlation id on tool messages.
    pub tool_call_id: Option<String>,
    /// Optional speaker name.
    pub name: Option<String>,
}

impl From<ModelMessage> for FfiModelMessage {
    fn from(m: ModelMessage) -> Self {
        FfiModelMessage {
            role: m.role,
            content: m.content,
            tool_calls: m
                .tool_calls
                .into_iter()
                .map(|t| FfiToolCall {
                    id: t.id,
                    name: t.name,
                    arguments_json: t.arguments_json,
                })
                .collect(),
            tool_call_id: m.tool_call_id,
            name: m.name,
        }
    }
}

/// One composed plugin row; `config_json` is the wholesale-replaceable config.
#[derive(uniffi::Record)]
pub struct FfiEntryRow {
    /// Patch-targeting identity.
    pub id: String,
    /// Plugin name in the compile-time index.
    pub name: String,
    /// Config serialized as JSON, when present.
    pub config_json: Option<String>,
    /// Nested-group marker.
    pub group: bool,
    /// Tri-state disable flag.
    pub disabled: Option<bool>,
    /// Entry-level service dependencies.
    pub inject: Vec<String>,
}

/// Composition result: rows plus non-fatal warnings.
#[derive(uniffi::Record)]
pub struct FfiComposeOutput {
    /// Final row list, in insertion order.
    pub rows: Vec<FfiEntryRow>,
    /// Skip/mismatch diagnostics, labelled by layer.
    pub warnings: Vec<String>,
}

/// One patch layer as (label, YAML document).
#[derive(uniffi::Record)]
pub struct FfiPatchLayerInput {
    /// Diagnostics label (bundle name, profile patch, overlay path...).
    pub label: String,
    /// YAML document: a list of set-patches and inserts.
    pub yaml: String,
}

/// One dispatched SSE event.
#[derive(uniffi::Record)]
pub struct FfiSseEvent {
    /// Value of the `event:` field, when present.
    pub event: Option<String>,
    /// Concatenated `data:` fields.
    pub data: String,
    /// Value of the last `id:` field, when present.
    pub id: Option<String>,
    /// True when `data` equals the configured done sentinel.
    pub is_done: bool,
}

impl From<SseEvent> for FfiSseEvent {
    fn from(e: SseEvent) -> Self {
        FfiSseEvent {
            event: e.event,
            data: e.data,
            id: e.id,
            is_done: e.is_done,
        }
    }
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/// Session-log failures crossing the boundary.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiLogError {
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
        /// Index of the offending record.
        index: u64,
        /// Expected seq.
        expected: u64,
        /// Seq found.
        found: u64,
    },
    /// A line of the file is not a well-formed log record.
    #[error("malformed record at line {line}: {reason}")]
    MalformedRecord {
        /// One-based line number.
        line: u64,
        /// Why the record is malformed.
        reason: String,
    },
    /// The file does not start with a header record.
    #[error("missing header record")]
    MissingHeader,
    /// Persistence backend failure.
    #[error("store error: {0}")]
    Store(String),
    /// An appended payload was not valid JSON.
    #[error("invalid event payload json: {0}")]
    InvalidPayloadJson(String),
}

impl From<LogError> for FfiLogError {
    fn from(e: LogError) -> Self {
        match e {
            LogError::UnsupportedFormatVersion { found, supported } => {
                FfiLogError::UnsupportedFormatVersion { found, supported }
            }
            LogError::SeqDiscontinuity {
                index,
                expected,
                found,
            } => FfiLogError::SeqDiscontinuity {
                index: index as u64,
                expected,
                found,
            },
            LogError::MalformedRecord { line, reason } => FfiLogError::MalformedRecord {
                line: line as u64,
                reason,
            },
            LogError::MissingHeader => FfiLogError::MissingHeader,
            LogError::Store(e) => FfiLogError::Store(e.to_string()),
        }
    }
}

/// Patch-composition failures (load-time; composition itself only warns).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiComposeError {
    /// A YAML layer could not be parsed.
    #[error("invalid patch layer YAML in {label}: {reason}")]
    InvalidYaml {
        /// Layer label.
        label: String,
        /// Parser message.
        reason: String,
    },
}

/// SSE parser failures.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiSseError {
    /// Bytes are not valid UTF-8.
    #[error("invalid UTF-8 in stream")]
    InvalidUtf8,
    /// Stream ended with an unterminated tail.
    #[error("truncated stream: unterminated tail")]
    Truncated,
}

impl From<codec::SseError> for FfiSseError {
    fn from(e: codec::SseError) -> Self {
        match e {
            codec::SseError::InvalidUtf8 => FfiSseError::InvalidUtf8,
            codec::SseError::Truncated => FfiSseError::Truncated,
        }
    }
}

// ---------------------------------------------------------------------------
// Session log object
// ---------------------------------------------------------------------------

/// Handle to the session log engine. Thread-safe; internal mutability is
/// mutex-guarded on the Rust side (ANDROID-PLAN §4.2 ownership discipline).
#[derive(uniffi::Object)]
pub struct SessionLogHandle {
    inner: Arc<SessionLog>,
}

#[uniffi::export]
impl SessionLogHandle {
    /// Creates a fresh in-memory log (tests, ephemeral sessions).
    #[uniffi::constructor]
    pub fn in_memory(header: FfiSessionHeader) -> Arc<Self> {
        Arc::new(Self {
            inner: SessionLog::in_memory(header.into()),
        })
    }

    /// Creates a new JSONL log file at `path`, writing the header first.
    #[uniffi::constructor]
    pub fn create_jsonl(path: String, header: FfiSessionHeader) -> Result<Arc<Self>, FfiLogError> {
        let store = JsonlStore::open(path).map_err(LogError::from)?;
        Ok(Arc::new(Self {
            inner: SessionLog::create(store, header.into())?,
        }))
    }

    /// Opens an existing JSONL log, validating version and seq continuity.
    /// Call [`SessionLogHandle::close_interrupted_turns`] before starting the
    /// agent loop (ANDROID-PLAN M2 fixed call point).
    #[uniffi::constructor]
    pub fn open_jsonl(path: String) -> Result<Arc<Self>, FfiLogError> {
        let store = JsonlStore::open(path).map_err(LogError::from)?;
        Ok(Arc::new(Self {
            inner: SessionLog::load(store)?,
        }))
    }

    /// Header of this log.
    pub fn header(&self) -> FfiSessionHeader {
        self.inner.header().into()
    }

    /// Torn-tail bytes dropped by `open_jsonl`'s crash repair; zero when the
    /// file opened clean. Lets the Kotlin side surface a repair notice
    /// instead of silently healing the log.
    pub fn repaired_torn_tail_bytes(&self) -> u64 {
        self.inner.repaired_torn_tail_bytes() as u64
    }

    /// Appends one event; returns the assigned seq. The durable write commits
    /// before the in-memory projection mutates.
    pub fn append(&self, event_type: String, payload_json: String) -> Result<u64, FfiLogError> {
        let payload = serde_json::from_str(&payload_json)
            .map_err(|e| FfiLogError::InvalidPayloadJson(e.to_string()))?;
        Ok(self.inner.append(event_type, payload)?)
    }

    /// Number of events appended so far; the next `append` receives this
    /// value as its seq. Cheaper than `events()` for progress polling.
    pub fn event_count(&self) -> u64 {
        self.inner.event_count()
    }

    /// All events in log order (payloads as JSON strings).
    pub fn events(&self) -> Vec<FfiSessionEvent> {
        self.inner
            .events()
            .into_iter()
            .map(|e: SessionEvent| FfiSessionEvent {
                seq: e.seq,
                event_type: e.event_type,
                time_ms: e.time_ms,
                payload_json: serde_json::to_string(&e.payload).unwrap_or_default(),
            })
            .collect()
    }

    /// Projects model history from events with `seq < upto`.
    pub fn derive_messages(&self, upto: u64) -> Vec<FfiModelMessage> {
        self.inner
            .derive_messages(upto)
            .into_iter()
            .map(Into::into)
            .collect()
    }

    /// Closes crash-orphaned turns; returns the seqs of the buried
    /// `turn/start` events.
    pub fn close_interrupted_turns(&self) -> Result<Vec<u64>, FfiLogError> {
        Ok(self.inner.close_interrupted_turns()?)
    }

    /// Latest canonical request header as a JSON string, or `None` before the
    /// first `request/header` snapshot. The loop compares successive headers
    /// with [`epoch_header_equals_json`] to log only real changes.
    pub fn request_header_json(&self) -> Result<Option<String>, FfiLogError> {
        Ok(self
            .inner
            .request_header()?
            .map(|h| serde_json::to_string(&h).unwrap_or_default()))
    }

    /// Latest route metadata from `request/context` events, as a JSON string.
    pub fn request_context_json(&self) -> Result<Option<String>, FfiLogError> {
        Ok(self
            .inner
            .request_context()?
            .map(|c| serde_json::to_string(&c).unwrap_or_default()))
    }

    /// Flushes the store at checkpoint boundaries.
    pub fn flush(&self) -> Result<(), FfiLogError> {
        Ok(self.inner.flush()?)
    }
}

// ---------------------------------------------------------------------------
// Patch composition (pure function)
// ---------------------------------------------------------------------------

/// Composes ordered YAML patch layers onto the empty root list.
/// Semantics: locate-by-id, per-key shallow override, insert-append,
/// warn-and-skip on missing ids and name mismatches (ANDROID-PLAN §3.4).
#[uniffi::export]
pub fn compose_yaml_layers(
    layers: Vec<FfiPatchLayerInput>,
) -> Result<FfiComposeOutput, FfiComposeError> {
    let mut parsed = Vec::with_capacity(layers.len());
    for layer in layers {
        parsed.push(
            PatchLayer::from_yaml(&layer.label, &layer.yaml).map_err(|e| match e {
                compose::ComposeError::InvalidYaml { label, reason } => {
                    FfiComposeError::InvalidYaml { label, reason }
                }
            })?,
        );
    }
    let out = compose::compose_rows(&parsed);
    Ok(FfiComposeOutput {
        rows: out
            .rows
            .into_iter()
            .map(|r: EntryRow| FfiEntryRow {
                id: r.id,
                name: r.name,
                config_json: r
                    .config
                    .map(|c| serde_json::to_string(&c).unwrap_or_default()),
                group: r.group,
                disabled: r.disabled,
                inject: r.inject.unwrap_or_default(),
            })
            .collect(),
        warnings: out.warnings,
    })
}

// ---------------------------------------------------------------------------
// SSE codec (object + the single coarse-grained callback shape)
// ---------------------------------------------------------------------------

/// Incremental SSE parser handle. Cancellation = stop feeding.
#[derive(uniffi::Object)]
pub struct SseFeeder {
    parser: Mutex<SseParser>,
}

#[uniffi::export]
impl SseFeeder {
    /// Creates a parser; `done_sentinel` (e.g. `[DONE]`) is data supplied by
    /// the provider plugin, not baked into the codec.
    #[uniffi::constructor]
    pub fn new(done_sentinel: Option<String>) -> Arc<Self> {
        Arc::new(Self {
            parser: Mutex::new(SseParser::with_done_sentinel(done_sentinel)),
        })
    }

    /// Feeds one chunk; returns events completed by it.
    pub fn feed(&self, bytes: Vec<u8>) -> Result<Vec<FfiSseEvent>, FfiSseError> {
        let mut parser = self
            .parser
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        Ok(parser.feed(&bytes)?.into_iter().map(Into::into).collect())
    }

    /// Signals end of stream; fails on an unterminated tail.
    pub fn finish(&self) -> Result<Vec<FfiSseEvent>, FfiSseError> {
        let mut parser = self
            .parser
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        Ok(parser.finish()?.into_iter().map(Into::into).collect())
    }
}

/// The single callback shape allowed across the boundary: coarse-grained,
/// one call per dispatched event or terminal error (ANDROID-PLAN §4.2).
#[uniffi::export(with_foreign)]
pub trait SseSink: Send + Sync {
    /// Called once per dispatched SSE event.
    fn on_event(&self, event: FfiSseEvent);
    /// Called at most once, on terminal parse failure.
    fn on_error(&self, message: String);
}

/// Convenience pump for batched sources: feeds `chunks` through a parser and
/// reports to `sink`. Streaming callers should prefer [`SseFeeder`].
#[uniffi::export]
pub fn pump_sse_chunks(
    chunks: Vec<Vec<u8>>,
    done_sentinel: Option<String>,
    sink: Arc<dyn SseSink>,
) {
    let mut parser = SseParser::with_done_sentinel(done_sentinel);
    for chunk in chunks {
        match parser.feed(&chunk) {
            Ok(events) => {
                for event in events {
                    sink.on_event(event.into());
                }
            }
            Err(e) => {
                sink.on_error(e.to_string());
                return;
            }
        }
    }
    match parser.finish() {
        Ok(events) => {
            for event in events {
                sink.on_event(event.into());
            }
        }
        Err(e) => sink.on_error(e.to_string()),
    }
}

// ---------------------------------------------------------------------------
// Content addressing (pure functions)
// ---------------------------------------------------------------------------

/// Content address of a byte payload: `sha256:<hex>`.
#[uniffi::export]
#[must_use]
pub fn content_address(bytes: Vec<u8>) -> String {
    codec::content_address(&bytes)
}

/// Verifies `bytes` against a `sha256:<hex>` content address.
#[uniffi::export]
#[must_use]
pub fn verify_content(address: String, bytes: Vec<u8>) -> bool {
    codec::verify_content(&address, &bytes)
}

// ---------------------------------------------------------------------------
// Request-header algebra (pure functions)
// ---------------------------------------------------------------------------

/// Canonicalizes one epoch-header JSON (drops empty system/tools and
/// markerless adapterDefaults) — the representation the log folds and stores.
#[uniffi::export]
pub fn canonicalize_header_json(header_json: String) -> Result<String, FfiLogError> {
    let header: session_log::EpochHeader = serde_json::from_str(&header_json)
        .map_err(|e| FfiLogError::InvalidPayloadJson(e.to_string()))?;
    Ok(serde_json::to_string(&session_log::canonical_header(header)).unwrap_or_default())
}

/// Field-wise equality over two epoch-header JSONs (config scalars, adapter
/// markers, system text, tool schemas in order).
#[uniffi::export]
pub fn epoch_header_equals_json(a_json: String, b_json: String) -> Result<bool, FfiLogError> {
    let parse = |s: &str| -> Result<session_log::EpochHeader, FfiLogError> {
        serde_json::from_str(s).map_err(|e| FfiLogError::InvalidPayloadJson(e.to_string()))
    };
    Ok(session_log::header_equals(
        &parse(&a_json)?,
        &parse(&b_json)?,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header() -> FfiSessionHeader {
        FfiSessionHeader {
            format_version: session_log::FORMAT_VERSION,
            session_id: "ffi-test".into(),
            created_at_ms: 0,
        }
    }

    #[test]
    fn log_handle_round_trip_through_jsonl() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        let path_str = path.to_string_lossy().into_owned();
        {
            let log = SessionLogHandle::create_jsonl(path_str.clone(), header()).unwrap();
            log.append("user/message".into(), r#"{"content":"hi"}"#.into())
                .unwrap();
            log.flush().unwrap();
        }
        let log = SessionLogHandle::open_jsonl(path_str).unwrap();
        assert_eq!(log.events().len(), 1);
        let messages = log.derive_messages(u64::MAX);
        assert_eq!(messages.len(), 1);
        assert_eq!(messages[0].role, "user");
        assert_eq!(messages[0].content, "hi");
    }

    #[test]
    fn append_rejects_invalid_payload_json() {
        let log = SessionLogHandle::in_memory(header());
        let err = log
            .append("user/message".into(), "not json".into())
            .unwrap_err();
        assert!(matches!(err, FfiLogError::InvalidPayloadJson(_)));
    }

    #[test]
    fn compose_yaml_layers_exposes_rows_and_warnings() {
        let out = compose_yaml_layers(vec![
            FfiPatchLayerInput {
                label: "bundle".into(),
                yaml: "- insert:\n  - id: llm\n    name: llm\n".into(),
            },
            FfiPatchLayerInput {
                label: "profile".into(),
                yaml: "- id: missing\n  disabled: true\n".into(),
            },
        ])
        .unwrap();
        assert_eq!(out.rows.len(), 1);
        assert_eq!(out.rows[0].id, "llm");
        assert_eq!(out.warnings.len(), 1);
    }

    #[test]
    fn sse_feeder_and_pump_agree() {
        let feeder = SseFeeder::new(Some("[DONE]".into()));
        let events = feeder
            .feed(b"data: a\n\ndata: [DONE]\n\n".to_vec())
            .unwrap();
        assert_eq!(events.len(), 2);
        assert!(events[1].is_done);
        assert_eq!(feeder.finish().unwrap().len(), 0);

        struct Collect(std::sync::Mutex<Vec<String>>);
        impl SseSink for Collect {
            fn on_event(&self, event: FfiSseEvent) {
                self.0.lock().unwrap().push(event.data);
            }
            fn on_error(&self, message: String) {
                panic!("unexpected error: {message}");
            }
        }
        let sink = Arc::new(Collect(std::sync::Mutex::new(Vec::new())));
        pump_sse_chunks(
            vec![b"data: a\n\n".to_vec(), b"data: [DONE]\n\n".to_vec()],
            Some("[DONE]".into()),
            sink.clone(),
        );
        assert_eq!(sink.0.lock().unwrap().as_slice(), ["a", "[DONE]"]);
    }

    #[test]
    fn content_addressing_crosses_the_boundary() {
        let addr = content_address(b"abc".to_vec());
        assert!(verify_content(addr, b"abc".to_vec()));
    }
}
