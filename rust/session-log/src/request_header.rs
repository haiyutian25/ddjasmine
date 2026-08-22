//! Request-header and request-context fold algebra — the interpreter of
//! `request/header` / `request/context` session events (upstream
//! `core/session/src/request-header.ts` + `Session.requestHeader/requestContext`
//! parity). Anyone holding a log reconstructs the header any request was built
//! under by taking the latest canonical snapshot; the loop uses the same
//! equality helper to avoid logging unchanged headers.
//!
//! Divergence, documented: tool-schema equality compares structurally
//! (serde `Value`, object-key order ignored) where upstream compares
//! `JSON.stringify` byte strings (insertion order). Schemas assembled through
//! one path are identical under both; only a hand-crafted pair differing in
//! key order alone would compare equal here and unequal upstream.

use serde::{Deserialize, Serialize};

use crate::SessionEvent;

/// Provider, model, reasoning effort, and sampling scalars of one
/// conversation's requests (upstream `LlmCallConfig`). Serialized camelCase
/// to match the logged `request/header` payload.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CallConfig {
    /// Registered provider route.
    pub provider: String,
    /// Provider-owned model id.
    pub model: String,
    /// Reasoning-effort id, when set.
    pub reasoning_effort: Option<String>,
    /// Sampling temperature, when set.
    pub temperature: Option<f64>,
    /// Response token budget, when set.
    pub max_tokens: Option<f64>,
    /// Stop sequences, when set; compared element-wise in order.
    pub stop: Option<Vec<String>>,
}

/// Effective config fields materialized from the exact adapter rather than
/// proposed by a caller (upstream `LlmCallConfigAdapterDefaults`). A field is
/// present only as the marker `true`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdapterDefaults {
    /// `reasoningEffort` was adapter-supplied.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reasoning_effort: Option<bool>,
    /// `maxTokens` was adapter-supplied.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<bool>,
}

/// Logged request state outside derived history: call config, system prompt,
/// and tools (upstream `EpochHeader`). Canonical form drops empty optional
/// fields; one representation is used for logging, folding, and comparison.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EpochHeader {
    /// The conversation's call configuration.
    pub config: CallConfig,
    /// Adapter-materialized fields, absent when no marker is `true`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub adapter_defaults: Option<AdapterDefaults>,
    /// Rendered system prompt text; absent for a system-less request.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub system: Option<String>,
    /// Assembled tool schemas (opaque JSON); absent for a tool-less request.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<serde_json::Value>>,
}

impl EpochHeader {
    /// Parses a header from a raw `request/header` payload's `header` field.
    ///
    /// # Errors
    /// Returns the deserialize error when the field is not a valid header.
    pub fn from_payload(payload: &serde_json::Value) -> Result<Self, serde_json::Error> {
        serde_json::from_value(
            payload
                .get("header")
                .cloned()
                .unwrap_or(serde_json::Value::Null),
        )
    }
}

/// Registration-bound metadata for one resolved model route (upstream
/// `RequestContext`); the latest `request/context` payload verbatim.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestContext {
    /// Registered provider route the metadata belongs to.
    pub provider: String,
    /// Provider-owned model id the metadata belongs to.
    pub model: String,
    /// Maximum combined request and response context in tokens, when advertised.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_window: Option<u64>,
}

/// Normalizes a header to canonical form: an empty system prompt and empty
/// tool list become absent fields, and `adapterDefaults` stays only when at
/// least one marker is `true` — matching how requests are built.
#[must_use]
pub fn canonical_header(header: EpochHeader) -> EpochHeader {
    let adapter_defaults = header
        .adapter_defaults
        .filter(|a| a.reasoning_effort == Some(true) || a.max_tokens == Some(true));
    EpochHeader {
        adapter_defaults,
        system: header.system.filter(|s| !s.is_empty()),
        tools: header.tools.filter(|t| !t.is_empty()),
        config: header.config,
    }
}

/// Field-wise equality over canonical headers: config scalars (stop
/// element-wise), adapter markers, system text, and tool schemas in order.
#[must_use]
pub fn header_equals(a: &EpochHeader, b: &EpochHeader) -> bool {
    if !call_config_equals(&a.config, &b.config) {
        return false;
    }
    let marker = |h: &Option<AdapterDefaults>, f: fn(&AdapterDefaults) -> Option<bool>| {
        h.as_ref().and_then(f)
    };
    if marker(&a.adapter_defaults, |d| d.reasoning_effort)
        != marker(&b.adapter_defaults, |d| d.reasoning_effort)
        || marker(&a.adapter_defaults, |d| d.max_tokens)
            != marker(&b.adapter_defaults, |d| d.max_tokens)
        || a.system != b.system
    {
        return false;
    }
    a.tools.as_deref().unwrap_or_default().len() == b.tools.as_deref().unwrap_or_default().len()
        && a.tools
            .as_deref()
            .unwrap_or_default()
            .iter()
            .zip(b.tools.as_deref().unwrap_or_default())
            .all(|(x, y)| x == y)
}

/// Field-wise equality over call configs — the comparison that decides whether
/// a proposed configuration is a real change (worth a logged header snapshot)
/// or the held one restated.
#[must_use]
pub fn call_config_equals(a: &CallConfig, b: &CallConfig) -> bool {
    a.provider == b.provider
        && a.model == b.model
        && a.reasoning_effort == b.reasoning_effort
        && a.temperature == b.temperature
        && a.max_tokens == b.max_tokens
        && a.stop == b.stop
}

/// Folds the header events of a log (or any prefix) into the canonical
/// [`EpochHeader`] in force after the last snapshot. Non-header events are
/// skipped; `from` continues a previously folded state.
///
/// # Errors
/// Returns the deserialize error when a `request/header` payload's `header`
/// field does not deserialize — the log is corrupt, not merely header-less.
pub fn fold_request_header(
    events: &[SessionEvent],
    from: Option<EpochHeader>,
) -> Result<Option<EpochHeader>, serde_json::Error> {
    let mut state = from;
    for event in events {
        if event.event_type == "request/header" {
            state = Some(canonical_header(EpochHeader::from_payload(&event.payload)?));
        }
    }
    Ok(state)
}

/// Folds `request/context` events into the latest route metadata, or `None`
/// before the first such event.
///
/// # Errors
/// Returns the deserialize error when a `request/context` payload does not
/// deserialize.
pub fn fold_request_context(
    events: &[SessionEvent],
) -> Result<Option<RequestContext>, serde_json::Error> {
    let mut state = None;
    for event in events {
        if event.event_type == "request/context" {
            state = Some(serde_json::from_value(event.payload.clone())?);
        }
    }
    Ok(state)
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn config(provider: &str, model: &str) -> CallConfig {
        CallConfig {
            provider: provider.into(),
            model: model.into(),
            reasoning_effort: None,
            temperature: None,
            max_tokens: None,
            stop: None,
        }
    }

    fn event(kind: &str, payload: serde_json::Value) -> SessionEvent {
        SessionEvent {
            seq: 0,
            event_type: kind.into(),
            time_ms: 0,
            payload,
        }
    }

    #[test]
    fn canonical_form_drops_empty_optionals_and_markerless_adapter_defaults() {
        let header = EpochHeader {
            config: config("p", "m"),
            adapter_defaults: Some(AdapterDefaults {
                reasoning_effort: Some(false),
                max_tokens: None,
            }),
            system: Some(String::new()),
            tools: Some(Vec::new()),
        };
        let canonical = canonical_header(header);
        assert_eq!(canonical.adapter_defaults, None);
        assert_eq!(canonical.system, None);
        assert_eq!(canonical.tools, None);

        let kept = canonical_header(EpochHeader {
            config: config("p", "m"),
            adapter_defaults: Some(AdapterDefaults {
                reasoning_effort: None,
                max_tokens: Some(true),
            }),
            system: Some("s".into()),
            tools: Some(vec![json!({"name": "t"})]),
        });
        assert_eq!(
            kept.adapter_defaults.map(|a| a.max_tokens),
            Some(Some(true))
        );
        assert_eq!(kept.system.as_deref(), Some("s"));
        assert_eq!(kept.tools.as_ref().map(Vec::len), Some(1));
    }

    #[test]
    fn header_equality_covers_every_field() {
        let base = || EpochHeader {
            config: config("p", "m"),
            adapter_defaults: None,
            system: Some("s".into()),
            tools: Some(vec![json!({"name": "a"}), json!({"name": "b"})]),
        };
        assert!(header_equals(&base(), &base()));
        let mut other = base();
        other.config.model = "m2".into();
        assert!(!header_equals(&base(), &other));
        let mut other = base();
        other.tools.as_mut().unwrap().swap(0, 1);
        assert!(!header_equals(&base(), &other)); // tool order matters
        let mut other = base();
        other.adapter_defaults = Some(AdapterDefaults {
            reasoning_effort: Some(true),
            max_tokens: None,
        });
        assert!(!header_equals(&base(), &other)); // absent vs Some(true)
        let none = EpochHeader {
            config: config("p", "m"),
            adapter_defaults: None,
            system: None,
            tools: None,
        };
        assert!(!header_equals(&base(), &none));
    }

    #[test]
    fn call_config_equality_is_fieldwise_with_ordered_stop() {
        assert!(call_config_equals(&config("p", "m"), &config("p", "m")));
        let mut a = config("p", "m");
        a.stop = Some(vec!["x".into(), "y".into()]);
        let mut b = config("p", "m");
        b.stop = Some(vec!["y".into(), "x".into()]);
        assert!(!call_config_equals(&a, &b));
        b.stop = None;
        assert!(!call_config_equals(&a, &b)); // set vs absent
    }

    #[test]
    fn fold_takes_the_latest_snapshot_and_continues_from_state() {
        let events = vec![
            event(
                "request/header",
                json!({"header": {"config": {"provider": "p", "model": "m1"},
                   "system": ""},
                 "reason": "initial"}),
            ),
            event("user/message", json!({"content": "hi"})),
            event(
                "request/header",
                json!({"header": {"config": {"provider": "p", "model": "m2"}},
                 "reason": "change"}),
            ),
        ];
        let folded = fold_request_header(&events, None).unwrap().unwrap();
        assert_eq!(folded.config.model, "m2");

        let mut prefix = events.clone();
        prefix.truncate(2);
        let state = fold_request_header(&prefix, None).unwrap().unwrap();
        assert_eq!(state.config.model, "m1");
        let resumed = fold_request_header(&events[2..], Some(state))
            .unwrap()
            .unwrap();
        assert_eq!(resumed.config.model, "m2");
    }

    #[test]
    fn fold_rejects_a_malformed_header_payload_loudly() {
        let events = vec![event("request/header", json!({"header": 42}))];
        assert!(fold_request_header(&events, None).is_err());
    }

    #[test]
    fn fold_request_context_takes_the_latest_event() {
        let events = vec![
            event(
                "request/context",
                json!({"provider": "p", "model": "m", "contextWindow": 65536}),
            ),
            event("user/message", json!({"content": "hi"})),
            event("request/context", json!({"provider": "p", "model": "m2"})),
        ];
        let folded = fold_request_context(&events).unwrap().unwrap();
        assert_eq!(folded.model, "m2");
        assert_eq!(folded.context_window, None);

        assert_eq!(fold_request_context(&[]).unwrap(), None);
    }
}
