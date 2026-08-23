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
use plugin_core::{
    AccessRule, AuditDrift, CallerIdentity, Capability, CoreError, CrashVerdict, DependencyFailure,
    ExceptionFrame, InstallRequest, IntentFilter, IntentQuery, LocateOutcome, PluginCore,
    PluginRecord, ProviderSpec, SignatureStrategy, StaticReceiver, Verdict,
};
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

// ---------------------------------------------------------------------------
// Plugin framework core (plan-style: every call returns a complete decision)
// ---------------------------------------------------------------------------

/// FFI mirror of [`SignatureStrategy`].
#[derive(uniffi::Enum)]
pub enum FfiSignatureStrategy {
    /// Signature mismatch rejects outright.
    Strict,
    /// Signature mismatch escalates to a user grant.
    UserGrant,
    /// Signature mismatch ignored (development only).
    Insecure,
}

impl From<FfiSignatureStrategy> for SignatureStrategy {
    fn from(s: FfiSignatureStrategy) -> Self {
        match s {
            FfiSignatureStrategy::Strict => SignatureStrategy::Strict,
            FfiSignatureStrategy::UserGrant => SignatureStrategy::UserGrant,
            FfiSignatureStrategy::Insecure => SignatureStrategy::Insecure,
        }
    }
}

/// FFI mirror of [`AccessRule`].
#[derive(uniffi::Enum)]
pub enum FfiAccessRule {
    /// Caller's signature must equal the host's.
    Host,
    /// Caller must be the target plugin itself, or carry the host signature.
    SelfOrHost,
    /// Any installed plugin may call.
    AnyPlugin,
}

impl From<FfiAccessRule> for AccessRule {
    fn from(r: FfiAccessRule) -> Self {
        match r {
            FfiAccessRule::Host => AccessRule::Host,
            FfiAccessRule::SelfOrHost => AccessRule::SelfOrHost,
            FfiAccessRule::AnyPlugin => AccessRule::AnyPlugin,
        }
    }
}

/// FFI mirror of [`CallerIdentity`]; attribution itself stays on the JVM
/// side, only the conclusion crosses.
#[derive(uniffi::Enum)]
pub enum FfiCallerIdentity {
    /// The host application.
    Host,
    /// An installed plugin.
    Plugin {
        /// Plugin id.
        plugin_id: String,
        /// SHA-256 digests of its signing certificates, lowercase hex.
        signature_digests: Vec<String>,
    },
    /// Attribution failed; never silently allowed.
    Unknown,
}

impl From<FfiCallerIdentity> for CallerIdentity {
    fn from(c: FfiCallerIdentity) -> Self {
        match c {
            FfiCallerIdentity::Host => CallerIdentity::Host,
            FfiCallerIdentity::Plugin {
                plugin_id,
                signature_digests,
            } => CallerIdentity::Plugin {
                plugin_id,
                signature_digests,
            },
            FfiCallerIdentity::Unknown => CallerIdentity::Unknown,
        }
    }
}

/// FFI mirror of [`Verdict`].
#[derive(uniffi::Enum)]
pub enum FfiVerdict {
    /// Proceed.
    Allow,
    /// Proceed only after the user grants authorization.
    RequireUserGrant {
        /// Human-readable reason for the escalation.
        reason: String,
    },
    /// Refuse.
    Deny {
        /// Human-readable reason for the refusal.
        reason: String,
    },
}

impl From<Verdict> for FfiVerdict {
    fn from(v: Verdict) -> Self {
        match v {
            Verdict::Allow => FfiVerdict::Allow,
            Verdict::RequireUserGrant { reason } => FfiVerdict::RequireUserGrant { reason },
            Verdict::Deny { reason } => FfiVerdict::Deny { reason },
        }
    }
}

/// FFI mirror of [`Capability`].
#[derive(uniffi::Enum)]
pub enum FfiCapability {
    /// Execute ELF binaries (Proot-style user-space Linux).
    Exec,
    /// GPU / accelerator inference (MNN OpenCL/Vulkan backends).
    Gpu,
    /// Network access (model / rootfs download).
    Network,
    /// Extended storage access (large rootfs payloads).
    Storage,
    /// Camera access.
    Camera,
}

impl From<FfiCapability> for Capability {
    fn from(c: FfiCapability) -> Self {
        match c {
            FfiCapability::Exec => Capability::Exec,
            FfiCapability::Gpu => Capability::Gpu,
            FfiCapability::Network => Capability::Network,
            FfiCapability::Storage => Capability::Storage,
            FfiCapability::Camera => Capability::Camera,
        }
    }
}

impl From<Capability> for FfiCapability {
    fn from(c: Capability) -> Self {
        match c {
            Capability::Exec => FfiCapability::Exec,
            Capability::Gpu => FfiCapability::Gpu,
            Capability::Network => FfiCapability::Network,
            Capability::Storage => FfiCapability::Storage,
            Capability::Camera => FfiCapability::Camera,
        }
    }
}

/// FFI mirror of [`InstallRequest`].
#[derive(uniffi::Record)]
pub struct FfiInstallRequest {
    /// Plugin id from the package metadata.
    pub plugin_id: String,
    /// Monotonic version code.
    pub version_code: u64,
    /// SHA-256 digests of the package's signing certificates, lowercase hex.
    pub signature_digests: Vec<String>,
    /// SHA-256 digest of the package bytes, lowercase hex.
    pub package_sha256: String,
    /// Digest published by the update channel; verified when present.
    pub expected_sha256: Option<String>,
    /// Skips the downgrade ban only; signature gates still apply.
    pub force_overwrite: bool,
    /// Capabilities the package declares it needs.
    pub capabilities: Vec<FfiCapability>,
}

impl From<FfiInstallRequest> for InstallRequest {
    fn from(r: FfiInstallRequest) -> Self {
        InstallRequest {
            plugin_id: r.plugin_id,
            version_code: r.version_code,
            signature_digests: r.signature_digests,
            package_sha256: r.package_sha256,
            expected_sha256: r.expected_sha256,
            force_overwrite: r.force_overwrite,
            capabilities: r.capabilities.into_iter().map(Into::into).collect(),
        }
    }
}

/// FFI mirror of [`PluginRecord`].
#[derive(uniffi::Record)]
pub struct FfiPluginRecord {
    /// Plugin id from the package metadata.
    pub plugin_id: String,
    /// Display name (application label).
    pub name: String,
    /// Launcher icon resource id, when declared.
    pub icon_res_id: Option<u32>,
    /// Monotonic version code.
    pub version_code: u64,
    /// Human-readable version.
    pub version_name: String,
    /// Fully-qualified entry class name (`plugin.entryClass` meta-data).
    pub entry_class: String,
    /// Free-form description (`plugin.description` meta-data).
    pub description: String,
    /// SHA-256 digests of the signing certificates, lowercase hex.
    pub signature_digests: Vec<String>,
    /// SHA-256 digest of the package bytes, lowercase hex.
    pub package_sha256: String,
    /// Directory the package payload was installed into.
    pub install_path: String,
    /// Disabled plugins stay registered but are skipped at load.
    pub enabled: bool,
    /// Install time, milliseconds since the Unix epoch.
    pub installed_at_ms: i64,
    /// Classes this plugin provides (build-time DEX scan output).
    pub classes: Vec<String>,
    /// Static receivers parsed at install, serialized as JSON.
    pub static_receivers_json: Option<String>,
    /// Content providers parsed at install, serialized as JSON.
    pub providers_json: Option<String>,
    /// Capabilities the package declares it needs.
    pub capabilities: Vec<FfiCapability>,
}

impl From<FfiPluginRecord> for PluginRecord {
    fn from(r: FfiPluginRecord) -> Self {
        PluginRecord {
            plugin_id: r.plugin_id,
            name: r.name,
            icon_res_id: r.icon_res_id,
            version_code: r.version_code,
            version_name: r.version_name,
            entry_class: r.entry_class,
            description: r.description,
            signature_digests: r.signature_digests,
            package_sha256: r.package_sha256,
            install_path: r.install_path,
            enabled: r.enabled,
            installed_at_ms: r.installed_at_ms,
            classes: r.classes.into_iter().collect(),
            static_receivers_json: r.static_receivers_json,
            providers_json: r.providers_json,
            capabilities: r.capabilities.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<PluginRecord> for FfiPluginRecord {
    fn from(r: PluginRecord) -> Self {
        FfiPluginRecord {
            plugin_id: r.plugin_id,
            name: r.name,
            icon_res_id: r.icon_res_id,
            version_code: r.version_code,
            version_name: r.version_name,
            entry_class: r.entry_class,
            description: r.description,
            signature_digests: r.signature_digests,
            package_sha256: r.package_sha256,
            install_path: r.install_path,
            enabled: r.enabled,
            installed_at_ms: r.installed_at_ms,
            classes: r.classes.into_iter().collect(),
            static_receivers_json: r.static_receivers_json,
            providers_json: r.providers_json,
            capabilities: r.capabilities.into_iter().map(Into::into).collect(),
        }
    }
}

/// FFI mirror of [`LocateOutcome`].
#[derive(uniffi::Enum)]
pub enum FfiLocateOutcome {
    /// The class is provided by this installed plugin.
    Plugin {
        /// Providing plugin id.
        plugin_id: String,
    },
    /// Index missed; try the host class loader before failing the load.
    HostFallback,
}

impl From<LocateOutcome> for FfiLocateOutcome {
    fn from(o: LocateOutcome) -> Self {
        match o {
            LocateOutcome::Plugin { plugin_id } => FfiLocateOutcome::Plugin { plugin_id },
            LocateOutcome::HostFallback => FfiLocateOutcome::HostFallback,
        }
    }
}

/// FFI mirror of `RestartPlan`.
#[derive(uniffi::Record)]
pub struct FfiRestartPlan {
    /// The plugin whose update triggered the plan.
    pub plugin_id: String,
    /// Affected plugins in dependency order (root first) — reload order.
    pub reload_order: Vec<String>,
    /// The exact reverse — unload order.
    pub unload_order: Vec<String>,
}

/// FFI mirror of [`AuditDrift`].
#[derive(uniffi::Enum)]
pub enum FfiAuditDrift {
    /// Index points at a plugin that is not registered.
    IndexTargetsUnknownPlugin {
        /// The indexed class.
        class: String,
        /// The plugin id the index names.
        plugin_id: String,
    },
    /// The owning record no longer lists the indexed class.
    IndexEntryStale {
        /// The indexed class.
        class: String,
        /// The plugin the index names.
        plugin_id: String,
    },
    /// A record lists a class the index does not reflect.
    RecordClassUnindexed {
        /// The owning plugin.
        plugin_id: String,
        /// The missing/mispointed class.
        class: String,
    },
    /// Index points at an installed plugin that is not loaded (informational).
    IndexTargetNotLoaded {
        /// The indexed class.
        class: String,
        /// The plugin the index names.
        plugin_id: String,
    },
}

impl From<AuditDrift> for FfiAuditDrift {
    fn from(d: AuditDrift) -> Self {
        match d {
            AuditDrift::IndexTargetsUnknownPlugin { class, plugin_id } => {
                FfiAuditDrift::IndexTargetsUnknownPlugin { class, plugin_id }
            }
            AuditDrift::IndexEntryStale { class, plugin_id } => {
                FfiAuditDrift::IndexEntryStale { class, plugin_id }
            }
            AuditDrift::RecordClassUnindexed { plugin_id, class } => {
                FfiAuditDrift::RecordClassUnindexed { plugin_id, class }
            }
            AuditDrift::IndexTargetNotLoaded { class, plugin_id } => {
                FfiAuditDrift::IndexTargetNotLoaded { class, plugin_id }
            }
        }
    }
}

/// Three-way reconciliation report (registry ↔ index ↔ loaded set).
#[derive(uniffi::Record)]
pub struct FfiAuditReport {
    /// Every drift found, in deterministic order.
    pub drifts: Vec<FfiAuditDrift>,
    /// True when no actionable drift remains (not-loaded entries excluded).
    pub is_clean: bool,
}

// --- dispatch mirrors ------------------------------------------------------

/// FFI mirror of `IntentFilter`.
#[derive(uniffi::Record)]
pub struct FfiIntentFilter {
    /// `<action android:name>`.
    pub actions: Vec<String>,
    /// `<category android:name>`.
    pub categories: Vec<String>,
    /// `<data android:scheme>`.
    pub schemes: Vec<String>,
}

impl From<FfiIntentFilter> for IntentFilter {
    fn from(f: FfiIntentFilter) -> Self {
        IntentFilter {
            actions: f.actions,
            categories: f.categories,
            schemes: f.schemes,
        }
    }
}

impl From<IntentFilter> for FfiIntentFilter {
    fn from(f: IntentFilter) -> Self {
        FfiIntentFilter {
            actions: f.actions,
            categories: f.categories,
            schemes: f.schemes,
        }
    }
}

/// FFI mirror of `StaticReceiver`.
#[derive(uniffi::Record)]
pub struct FfiStaticReceiver {
    /// Fully-qualified receiver class name.
    pub class_name: String,
    /// Component-level `android:enabled`.
    pub enabled: bool,
    /// Component-level `android:exported`.
    pub exported: bool,
    /// Declared intent filters.
    pub intent_filters: Vec<FfiIntentFilter>,
}

impl From<FfiStaticReceiver> for StaticReceiver {
    fn from(r: FfiStaticReceiver) -> Self {
        StaticReceiver {
            class_name: r.class_name,
            enabled: r.enabled,
            exported: r.exported,
            intent_filters: r.intent_filters.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<StaticReceiver> for FfiStaticReceiver {
    fn from(r: StaticReceiver) -> Self {
        FfiStaticReceiver {
            class_name: r.class_name,
            enabled: r.enabled,
            exported: r.exported,
            intent_filters: r.intent_filters.into_iter().map(Into::into).collect(),
        }
    }
}

/// FFI mirror of `ProviderSpec`.
#[derive(uniffi::Record)]
pub struct FfiProviderSpec {
    /// Fully-qualified provider class name.
    pub class_name: String,
    /// `android:authorities` split on `;`.
    pub authorities: Vec<String>,
    /// Component-level `android:enabled`.
    pub enabled: bool,
    /// Component-level `android:exported`.
    pub exported: bool,
}

impl From<FfiProviderSpec> for ProviderSpec {
    fn from(p: FfiProviderSpec) -> Self {
        ProviderSpec {
            class_name: p.class_name,
            authorities: p.authorities,
            enabled: p.enabled,
            exported: p.exported,
        }
    }
}

/// FFI mirror of `IntentQuery`.
#[derive(uniffi::Record)]
pub struct FfiIntentQuery {
    /// `Intent.getAction`; a broadcast without an action matches nothing.
    pub action: Option<String>,
    /// `Intent.getCategories` (empty = none).
    pub categories: Vec<String>,
    /// `Intent.getData()?.getScheme`.
    pub scheme: Option<String>,
    /// True when the intent's package equals the host package.
    pub is_internal: bool,
}

impl From<FfiIntentQuery> for IntentQuery {
    fn from(q: FfiIntentQuery) -> Self {
        IntentQuery {
            action: q.action,
            categories: q.categories,
            scheme: q.scheme,
            is_internal: q.is_internal,
        }
    }
}

/// One matched receiver with its owning plugin.
#[derive(uniffi::Record)]
pub struct FfiReceiverMatch {
    /// Owning plugin id.
    pub plugin_id: String,
    /// The matched receiver.
    pub receiver: FfiStaticReceiver,
}

/// One serialized exception in a cause chain (outermost first).
#[derive(uniffi::Record)]
pub struct FfiExceptionFrame {
    /// JVM class name of the exception.
    pub class_name: String,
    /// Stack frames as fully-qualified class names (top frame first).
    pub stack_classes: Vec<String>,
}

impl From<FfiExceptionFrame> for ExceptionFrame {
    fn from(f: FfiExceptionFrame) -> Self {
        ExceptionFrame {
            class_name: f.class_name,
            stack_classes: f.stack_classes,
        }
    }
}

/// Framework-declared dependency failure, extracted on the Kotlin side.
#[derive(uniffi::Record)]
pub struct FfiDependencyFailure {
    /// The plugin whose class load failed.
    pub culprit_plugin_id: String,
    /// The class that could not be resolved.
    pub missing_class: String,
}

impl From<FfiDependencyFailure> for DependencyFailure {
    fn from(f: FfiDependencyFailure) -> Self {
        DependencyFailure {
            culprit_plugin_id: f.culprit_plugin_id,
            missing_class: f.missing_class,
        }
    }
}

/// Crash category, in evaluation order.
#[derive(uniffi::Enum)]
pub enum FfiCrashKind {
    /// Framework-declared dependency resolution failure.
    Dependency,
    /// Class-cast failure (typically a half-updated plugin).
    ClassCast,
    /// Missing plugin resource.
    ResourceNotFound,
    /// Linkage failure against the host (plugin/host ABI skew).
    ApiIncompatible,
    /// Any other exception attributed to a plugin.
    Other,
}

/// The dispatcher's decision for one uncaught exception.
#[derive(uniffi::Record)]
pub struct FfiCrashVerdict {
    /// Attributed plugin, when any.
    pub culprit_plugin_id: Option<String>,
    /// Crash category.
    pub kind: FfiCrashKind,
}

impl From<CrashVerdict> for FfiCrashVerdict {
    fn from(v: CrashVerdict) -> Self {
        let kind = match v.kind {
            plugin_core::CrashKind::Dependency => FfiCrashKind::Dependency,
            plugin_core::CrashKind::ClassCast => FfiCrashKind::ClassCast,
            plugin_core::CrashKind::ResourceNotFound => FfiCrashKind::ResourceNotFound,
            plugin_core::CrashKind::ApiIncompatible => FfiCrashKind::ApiIncompatible,
            plugin_core::CrashKind::Other => FfiCrashKind::Other,
        };
        FfiCrashVerdict {
            culprit_plugin_id: v.culprit_plugin_id,
            kind,
        }
    }
}

/// Plugin-core failures crossing the boundary.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiPluginError {
    /// Operation referenced a plugin that is not registered.
    #[error("unknown plugin: {plugin_id}")]
    UnknownPlugin {
        /// The offending plugin id.
        plugin_id: String,
    },
    /// The registry file is not well-formed.
    #[error("corrupt ledger: {reason}")]
    Corrupt {
        /// Why the file is rejected.
        reason: String,
    },
    /// Persistence backend failure.
    #[error("store error: {reason}")]
    Store {
        /// Backend message.
        reason: String,
    },
}

impl From<CoreError> for FfiPluginError {
    fn from(e: CoreError) -> Self {
        match e {
            CoreError::Ledger(plugin_core::LedgerError::UnknownPlugin(plugin_id)) => {
                FfiPluginError::UnknownPlugin { plugin_id }
            }
            CoreError::Ledger(plugin_core::LedgerError::Corrupt(reason)) => {
                FfiPluginError::Corrupt { reason }
            }
            CoreError::Ledger(plugin_core::LedgerError::Store(e)) => FfiPluginError::Store {
                reason: e.to_string(),
            },
        }
    }
}

/// Handle to the plugin framework's decision core. Plan-style: each call
/// returns a complete verdict/plan/report; the Kotlin side executes file
/// and ClassLoader operations. Thread-safe; mutex-guarded on the Rust side.
#[derive(uniffi::Object)]
pub struct PluginCoreHandle {
    inner: Mutex<PluginCore>,
}

impl PluginCoreHandle {
    fn lock(&self) -> std::sync::MutexGuard<'_, PluginCore> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

#[uniffi::export]
impl PluginCoreHandle {
    /// Opens (or initializes) the registry file at `path`, recovering from a
    /// crash mid-rotation via the atomic-write sidecars. Topology and grant
    /// cache start empty — both are session-scoped and rebuild from use.
    #[uniffi::constructor]
    pub fn open(
        path: String,
        strategy: FfiSignatureStrategy,
        host_signature_digests: Vec<String>,
    ) -> Result<Arc<Self>, FfiPluginError> {
        let core = PluginCore::open(path, strategy.into(), host_signature_digests)?;
        Ok(Arc::new(Self {
            inner: Mutex::new(core),
        }))
    }

    /// An unpersisted core (tests, ephemeral sessions).
    #[uniffi::constructor]
    pub fn in_memory(
        strategy: FfiSignatureStrategy,
        host_signature_digests: Vec<String>,
    ) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(PluginCore::in_memory(
                strategy.into(),
                host_signature_digests,
            )),
        })
    }

    /// Adjudicates an install/update before any file lands.
    pub fn adjudicate_install(&self, request: FfiInstallRequest) -> FfiVerdict {
        self.lock().adjudicate_install(&request.into()).into()
    }

    /// Adjudicates a plugin's declared capabilities at install (escalate to
    /// user grant until each is authorized). Signature/version gates run
    /// separately through [`PluginCoreHandle::adjudicate_install`].
    pub fn adjudicate_capabilities(
        &self,
        plugin_id: String,
        capabilities: Vec<FfiCapability>,
    ) -> FfiVerdict {
        let capabilities: Vec<Capability> = capabilities.into_iter().map(Into::into).collect();
        self.lock()
            .adjudicate_capabilities(&plugin_id, &capabilities)
            .into()
    }

    /// Commits an install/update after the files are placed; registry and
    /// index land together, persisted before returning.
    pub fn commit_install(&self, record: FfiPluginRecord) -> Result<(), FfiPluginError> {
        Ok(self.lock().commit_install(record.into())?)
    }

    /// Commits an uninstall: registry entry, graph edges, instance
    /// registrations, and cached grants all leave together. Returns the
    /// removed record (the Kotlin side needs its install path).
    pub fn commit_uninstall(&self, plugin_id: String) -> Result<FfiPluginRecord, FfiPluginError> {
        Ok(self.lock().commit_uninstall(&plugin_id)?.into())
    }

    /// Enables or disables a plugin (crash-guided disable uses this).
    pub fn set_enabled(&self, plugin_id: String, enabled: bool) -> Result<(), FfiPluginError> {
        Ok(self.lock().set_enabled(&plugin_id, enabled)?)
    }

    /// Runtime unload (the plugin stays installed): receiver/provider
    /// routes, borrow edges, and pooled-service instances leave; the
    /// registry entry and cached grants stay.
    pub fn plugin_unloaded(&self, plugin_id: String) {
        self.lock().plugin_unloaded(&plugin_id);
    }

    /// Locates a class; on an index hit the borrow edge is recorded when
    /// `borrower` names another plugin, on a miss the host-fallback outcome
    /// tells the caller to try the host class loader.
    pub fn locate_class(&self, class: String, borrower: Option<String>) -> FfiLocateOutcome {
        self.lock().locate_class(&class, borrower.as_deref()).into()
    }

    /// The deterministic chained-restart plan for updating `plugin_id`.
    pub fn restart_plan(&self, plugin_id: String) -> FfiRestartPlan {
        let plan = self.lock().restart_plan(&plugin_id);
        FfiRestartPlan {
            plugin_id: plan.plugin_id,
            reload_order: plan.reload_order,
            unload_order: plan.unload_order,
        }
    }

    /// Everything that depends on `plugin_id`, transitively (root first,
    /// deterministic order).
    pub fn dependents_chain(&self, plugin_id: String) -> Vec<String> {
        self.lock().dependents_chain(&plugin_id)
    }

    /// Everything `plugin_id` depends on, transitively (root first,
    /// deterministic order).
    pub fn dependencies_chain(&self, plugin_id: String) -> Vec<String> {
        self.lock().dependencies_chain(&plugin_id)
    }

    /// Three-way reconciliation: registry ↔ index ↔ loaded set.
    pub fn audit(&self, loaded_plugin_ids: Vec<String>) -> FfiAuditReport {
        let report = self.lock().audit(&loaded_plugin_ids);
        FfiAuditReport {
            is_clean: report.is_clean(),
            drifts: report.drifts.into_iter().map(Into::into).collect(),
        }
    }

    /// Fixes index drift on sight; returns what was repaired.
    pub fn repair(&self) -> Result<FfiAuditReport, FfiPluginError> {
        let report = self.lock().repair()?;
        Ok(FfiAuditReport {
            is_clean: report.is_clean(),
            drifts: report.drifts.into_iter().map(Into::into).collect(),
        })
    }

    /// Evaluates an access rule for one sensitive-API call.
    pub fn check_api_access(
        &self,
        rule: FfiAccessRule,
        hard_fail: bool,
        caller: FfiCallerIdentity,
        target_plugin_id: String,
        permission_key: String,
    ) -> FfiVerdict {
        self.lock()
            .check_api_access(
                rule.into(),
                hard_fail,
                &caller.into(),
                &target_plugin_id,
                &permission_key,
            )
            .into()
    }

    /// Records the user's answer to an authorization prompt.
    pub fn record_grant(&self, plugin_id: String, permission_key: String, granted: bool) {
        self.lock()
            .record_grant(&plugin_id, &permission_key, granted);
    }

    /// One registered plugin record.
    pub fn plugin_record(&self, plugin_id: String) -> Option<FfiPluginRecord> {
        self.lock()
            .ledger()
            .record(&plugin_id)
            .cloned()
            .map(Into::into)
    }

    /// All registered plugin records, ordered by plugin id.
    pub fn all_records(&self) -> Vec<FfiPluginRecord> {
        self.lock()
            .ledger()
            .records()
            .into_iter()
            .cloned()
            .map(Into::into)
            .collect()
    }

    /// Registers a pooled-service instance (`"className:taskN"`).
    pub fn register_instance(&self, instance_id: String, plugin_id: String) {
        self.lock().register_instance(&instance_id, &plugin_id);
    }

    /// Drops one pooled-service instance registration.
    pub fn unregister_instance(&self, instance_id: String) {
        self.lock().unregister_instance(&instance_id);
    }

    /// Registers a plugin's static receivers at load (component-disabled
    /// entries are skipped).
    pub fn register_receivers(&self, plugin_id: String, receivers: Vec<FfiStaticReceiver>) {
        self.lock()
            .register_receivers(&plugin_id, receivers.into_iter().map(Into::into).collect());
    }

    /// Registers a plugin's content providers at load.
    pub fn register_providers(&self, plugin_id: String, providers: Vec<FfiProviderSpec>) {
        self.lock()
            .register_providers(&plugin_id, providers.into_iter().map(Into::into).collect());
    }

    /// Matches a broadcast against every registered receiver (narrowed
    /// Android filter semantics, owned here so host and proxy agree).
    pub fn match_receivers(&self, query: FfiIntentQuery) -> Vec<FfiReceiverMatch> {
        self.lock()
            .match_receivers(&query.into())
            .into_iter()
            .map(|(plugin_id, receiver)| FfiReceiverMatch {
                plugin_id,
                receiver: receiver.into(),
            })
            .collect()
    }

    /// Routes a provider authority to `(owning plugin, provider class)`.
    pub fn route_authority(&self, authority: String) -> Option<Vec<String>> {
        self.lock()
            .route_authority(&authority)
            .map(|(owner, class)| vec![owner, class])
    }

    /// Classifies one uncaught exception: cause-chain attribution plus
    /// category precedence. `None` means the crash is not plugin-attributable
    /// and should fall through to the default handler.
    pub fn classify_crash(
        &self,
        chain: Vec<FfiExceptionFrame>,
        dependency_failure: Option<FfiDependencyFailure>,
    ) -> Option<FfiCrashVerdict> {
        let chain: Vec<ExceptionFrame> = chain.into_iter().map(Into::into).collect();
        let failure: Option<DependencyFailure> = dependency_failure.map(Into::into);
        self.lock()
            .classify_crash(&chain, failure.as_ref())
            .map(Into::into)
    }
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

    #[test]
    fn plugin_core_plan_cycle_crosses_the_boundary() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("plugins.json");
        let core = PluginCoreHandle::open(
            path.to_string_lossy().into_owned(),
            FfiSignatureStrategy::UserGrant,
            vec!["sig".to_string()],
        )
        .unwrap();

        // Adjudicate → commit → locate (edge recorded) → restart plan.
        let verdict = core.adjudicate_install(FfiInstallRequest {
            plugin_id: "a".into(),
            version_code: 1,
            signature_digests: vec!["sig".into()],
            package_sha256: "pkg".into(),
            expected_sha256: Some("pkg".into()),
            force_overwrite: false,
            capabilities: vec![],
        });
        assert!(matches!(verdict, FfiVerdict::Allow));

        let record = |id: &str, classes: Vec<String>| FfiPluginRecord {
            plugin_id: id.into(),
            name: id.to_uppercase(),
            icon_res_id: None,
            version_code: 1,
            version_name: "1.0".into(),
            entry_class: format!("{id}.Entry"),
            description: String::new(),
            signature_digests: vec!["sig".into()],
            package_sha256: "pkg".into(),
            install_path: format!("/plugins/{id}"),
            enabled: true,
            installed_at_ms: 0,
            classes,
            static_receivers_json: None,
            providers_json: None,
            capabilities: vec![],
        };
        core.commit_install(record("a", vec!["a.Foo".into()]))
            .unwrap();
        core.commit_install(record("b", vec![])).unwrap();

        match core.locate_class("a.Foo".into(), Some("b".into())) {
            FfiLocateOutcome::Plugin { plugin_id } => assert_eq!(plugin_id, "a"),
            FfiLocateOutcome::HostFallback => panic!("expected plugin hit"),
        }
        assert!(matches!(
            core.locate_class("host.Thing".into(), Some("b".into())),
            FfiLocateOutcome::HostFallback
        ));

        let plan = core.restart_plan("a".into());
        assert_eq!(plan.reload_order, vec!["a", "b"]);
        assert_eq!(plan.unload_order, vec!["b", "a"]);
        // Chain queries exclude the queried plugin itself.
        assert_eq!(core.dependents_chain("a".into()), vec!["b"]);
        assert_eq!(core.dependencies_chain("b".into()), vec!["a"]);

        let report = core.audit(vec!["a".into(), "b".into()]);
        assert!(report.is_clean);

        // Dispatch: receiver registration, matching, and crash classification.
        core.register_receivers(
            "a".into(),
            vec![FfiStaticReceiver {
                class_name: "a.R".into(),
                enabled: true,
                exported: false,
                intent_filters: vec![FfiIntentFilter {
                    actions: vec!["A".into()],
                    categories: vec![],
                    schemes: vec![],
                }],
            }],
        );
        assert!(core
            .match_receivers(FfiIntentQuery {
                action: Some("A".into()),
                categories: vec![],
                scheme: None,
                is_internal: false,
            })
            .is_empty());
        assert_eq!(
            core.match_receivers(FfiIntentQuery {
                action: Some("A".into()),
                categories: vec![],
                scheme: None,
                is_internal: true,
            })
            .len(),
            1
        );
        let verdict = core
            .classify_crash(
                vec![FfiExceptionFrame {
                    class_name: "java.lang.ClassCastException".into(),
                    stack_classes: vec!["a.Foo".into()],
                }],
                None,
            )
            .unwrap();
        assert!(matches!(verdict.kind, FfiCrashKind::ClassCast));
        assert_eq!(verdict.culprit_plugin_id.as_deref(), Some("a"));

        // Persistence: a fresh handle sees the same registry.
        let reopened = PluginCoreHandle::open(
            path.to_string_lossy().into_owned(),
            FfiSignatureStrategy::UserGrant,
            vec!["sig".to_string()],
        )
        .unwrap();
        assert_eq!(reopened.all_records().len(), 2);

        // Uninstall cascades and persists.
        let removed = reopened.commit_uninstall("a".into()).unwrap();
        assert_eq!(removed.install_path, "/plugins/a");
        assert!(matches!(
            reopened.locate_class("a.Foo".into(), Some("b".into())),
            FfiLocateOutcome::HostFallback
        ));
    }
}
