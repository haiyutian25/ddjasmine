//! dispatch: pure matching and classification algorithms of the proxy and
//! crash layers.
//!
//! Everything here is data-in/decision-out: the Kotlin proxy layer feeds
//! parsed manifests and intents, the crash hook feeds serialized stack
//! traces, and this module returns complete matches/classifications.
//!
//! Intent-filter matching semantics deliberately **narrow** Android's
//! framework `match` to three fields: exact action, category subset,
//! scheme membership. Host and proxy must agree bit-for-bit on which
//! broadcasts a plugin receiver sees — one deterministic implementation
//! owned here, not two framework-call sites that can drift.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

/// One parsed `<intent-filter>` of a static receiver.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct IntentFilter {
    /// `<action android:name>`.
    pub actions: Vec<String>,
    /// `<category android:name>`.
    pub categories: Vec<String>,
    /// `<data android:scheme>`.
    pub schemes: Vec<String>,
}

/// A static receiver parsed at install time.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct StaticReceiver {
    /// Fully-qualified receiver class name.
    pub class_name: String,
    /// Component-level `android:enabled`.
    pub enabled: bool,
    /// Component-level `android:exported`.
    pub exported: bool,
    /// Declared intent filters.
    pub intent_filters: Vec<IntentFilter>,
}

/// A content provider parsed at install time.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProviderSpec {
    /// Fully-qualified provider class name.
    pub class_name: String,
    /// `android:authorities` split on `;`.
    pub authorities: Vec<String>,
    /// Component-level `android:enabled`.
    pub enabled: bool,
    /// Component-level `android:exported`.
    pub exported: bool,
}

/// A broadcast as the dispatcher sees it.
#[derive(Clone, Debug, Default)]
pub struct IntentQuery {
    /// `Intent.getAction`; a broadcast without an action matches nothing.
    pub action: Option<String>,
    /// `Intent.getCategories` (empty = none).
    pub categories: Vec<String>,
    /// `Intent.getData()?.getScheme`.
    pub scheme: Option<String>,
    /// True when the intent's package equals the host package (internal
    /// broadcast) — gates `exported=false` receivers.
    pub is_internal: bool,
}

/// Registry of static receivers across loaded plugins, plus the
/// authority → provider routing table.
#[derive(Default)]
pub struct ReceiverRegistry {
    /// (plugin id, receiver) in registration order.
    receivers: Vec<(String, StaticReceiver)>,
    /// authority → provider class name.
    authority_map: BTreeMap<String, String>,
    /// provider class name → owning plugin id.
    provider_owners: BTreeMap<String, String>,
}

impl ReceiverRegistry {
    /// An empty registry.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Registers a plugin's static receivers; component-disabled entries
    /// are skipped. Re-registration of the same plugin replaces its
    /// previous entries (load is idempotent per plugin).
    pub fn register_receivers(&mut self, plugin_id: &str, receivers: Vec<StaticReceiver>) {
        self.unregister_plugin(plugin_id);
        for receiver in receivers {
            if receiver.enabled {
                self.receivers.push((plugin_id.to_string(), receiver));
            }
        }
    }

    /// Registers a plugin's providers; component-disabled entries are
    /// skipped.
    pub fn register_providers(&mut self, plugin_id: &str, providers: Vec<ProviderSpec>) {
        for provider in providers {
            if !provider.enabled {
                continue;
            }
            self.provider_owners
                .insert(provider.class_name.clone(), plugin_id.to_string());
            for authority in provider.authorities {
                self.authority_map
                    .insert(authority, provider.class_name.clone());
            }
        }
    }

    /// Drops every receiver, authority route, and provider owner of one
    /// plugin.
    pub fn unregister_plugin(&mut self, plugin_id: &str) {
        self.receivers.retain(|(id, _)| id != plugin_id);
        let stale: Vec<String> = self
            .provider_owners
            .iter()
            .filter(|(_, owner)| owner.as_str() == plugin_id)
            .map(|(class, _)| class.clone())
            .collect();
        for class in &stale {
            self.provider_owners.remove(class);
        }
        if !stale.is_empty() {
            self.authority_map.retain(|_, class| !stale.contains(class));
        }
    }

    /// Matches a broadcast against every registered receiver, in
    /// registration order. Semantics (narrowed Android filter match):
    /// action must be present and listed; the intent's categories must all
    /// be listed (an intent without categories passes any filter); a
    /// filter without schemes passes any scheme, otherwise the intent's
    /// scheme must be listed; `exported=false` receivers only see internal
    /// broadcasts.
    #[must_use]
    pub fn match_receivers(&self, query: &IntentQuery) -> Vec<(&str, &StaticReceiver)> {
        let Some(action) = &query.action else {
            return Vec::new();
        };
        let mut matched = Vec::new();
        'receivers: for (plugin_id, receiver) in &self.receivers {
            if !receiver.exported && !query.is_internal {
                continue;
            }
            for filter in &receiver.intent_filters {
                if !filter.actions.contains(action) {
                    continue;
                }
                if !query
                    .categories
                    .iter()
                    .all(|c| filter.categories.contains(c))
                {
                    continue;
                }
                if let Some(scheme) = &query.scheme {
                    if !filter.schemes.is_empty() && !filter.schemes.contains(scheme) {
                        continue;
                    }
                }
                matched.push((plugin_id.as_str(), receiver));
                continue 'receivers;
            }
        }
        matched
    }

    /// Routes a provider authority to its owning plugin.
    #[must_use]
    pub fn route_authority(&self, authority: &str) -> Option<(&str, &str)> {
        let class = self.authority_map.get(authority)?;
        let owner = self.provider_owners.get(class)?;
        Some((owner.as_str(), class.as_str()))
    }
}

/// Crash category, in evaluation order: the first matching category wins.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CrashKind {
    /// A framework-declared dependency resolution failure (always
    /// classified, even without an attributable plugin — it carries its
    /// own culprit id).
    Dependency,
    /// Class-cast failure (typically a half-updated plugin).
    ClassCast,
    /// Missing plugin resource.
    ResourceNotFound,
    /// Linkage failure against the host (`NoSuchMethodError` /
    /// `NoSuchFieldError` / `AbstractMethodError`): plugin/host ABI skew.
    ApiIncompatible,
    /// Any other exception attributed to a plugin.
    Other,
}

/// The dispatcher's decision for one uncaught exception.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CrashVerdict {
    /// Attributed plugin (first stack-frame hit walking the cause chain),
    /// or the framework-declared culprit for dependency failures.
    pub culprit_plugin_id: Option<String>,
    /// Crash category.
    pub kind: CrashKind,
}

/// One serialized exception in a cause chain (outermost first).
#[derive(Clone, Debug, Default)]
pub struct ExceptionFrame {
    /// JVM class name of the exception.
    pub class_name: String,
    /// Stack frames as fully-qualified class names (top frame first).
    pub stack_classes: Vec<String>,
}

/// Framework-declared dependency failure, extracted on the Kotlin side
/// from the framework exception type itself (Kotlin owns the class).
#[derive(Clone, Debug, Default)]
pub struct DependencyFailure {
    /// The plugin whose class load failed.
    pub culprit_plugin_id: String,
    /// The class that could not be resolved.
    pub missing_class: String,
}

/// Classifies one uncaught exception: walks the cause chain for
/// attribution (first stack frame whose class is indexed), and applies
/// the category precedence — dependency first (even unattributed), then
/// class-cast / resource / linkage / other (only when attributed).
///
/// `lookup` maps a class name to its owning plugin (the class index);
/// `dependency_failure` is present when the chain carries the framework's
/// dependency exception.
#[must_use]
pub fn classify_crash(
    chain: &[ExceptionFrame],
    dependency_failure: Option<&DependencyFailure>,
    lookup: impl Fn(&str) -> Option<String>,
) -> Option<CrashVerdict> {
    if let Some(failure) = dependency_failure {
        return Some(CrashVerdict {
            culprit_plugin_id: Some(failure.culprit_plugin_id.clone()),
            kind: CrashKind::Dependency,
        });
    }
    let culprit = chain
        .iter()
        .flat_map(|frame| frame.stack_classes.iter())
        .find_map(|class| lookup(class))?;
    let classes: BTreeSet<&str> = chain
        .iter()
        .map(|frame| frame.class_name.as_str())
        .collect();
    let has = |suffix: &str| {
        classes.iter().any(|c| {
            *c == suffix || c.ends_with(&format!(".{suffix}")) || c.ends_with(&format!("${suffix}"))
        })
    };
    let kind = if has("ClassCastException") {
        CrashKind::ClassCast
    } else if classes
        .iter()
        .any(|c| *c == "android.content.res.Resources$NotFoundException")
    {
        CrashKind::ResourceNotFound
    } else if has("NoSuchMethodError") || has("NoSuchFieldError") || has("AbstractMethodError") {
        CrashKind::ApiIncompatible
    } else {
        CrashKind::Other
    };
    Some(CrashVerdict {
        culprit_plugin_id: Some(culprit),
        kind,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn receiver(class: &str, exported: bool, filters: Vec<IntentFilter>) -> StaticReceiver {
        StaticReceiver {
            class_name: class.to_string(),
            enabled: true,
            exported,
            intent_filters: filters,
        }
    }

    fn filter(actions: &[&str], categories: &[&str], schemes: &[&str]) -> IntentFilter {
        IntentFilter {
            actions: actions.iter().map(ToString::to_string).collect(),
            categories: categories.iter().map(ToString::to_string).collect(),
            schemes: schemes.iter().map(ToString::to_string).collect(),
        }
    }

    #[test]
    fn receiver_matching_action_category_scheme_and_exported_gate() {
        let mut reg = ReceiverRegistry::new();
        reg.register_receivers(
            "p",
            vec![
                receiver("p.Public", true, vec![filter(&["A"], &[], &["http"])]),
                receiver("p.Internal", false, vec![filter(&["A"], &[], &[])]),
            ],
        );

        // Exported receiver: action + scheme must both fit.
        let hits = reg.match_receivers(&IntentQuery {
            action: Some("A".into()),
            scheme: Some("http".into()),
            ..IntentQuery::default()
        });
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].1.class_name, "p.Public");

        // Non-exported receiver only sees internal broadcasts.
        let internal = reg.match_receivers(&IntentQuery {
            action: Some("A".into()),
            scheme: Some("ftp".into()),
            is_internal: true,
            ..IntentQuery::default()
        });
        assert_eq!(internal.len(), 1);
        assert_eq!(internal[0].1.class_name, "p.Internal");

        // Empty filter schemes = wildcard for scheme-less intents.
        let no_scheme = reg.match_receivers(&IntentQuery {
            action: Some("A".into()),
            scheme: None,
            ..IntentQuery::default()
        });
        assert_eq!(no_scheme.len(), 1);

        // No action matches nothing.
        assert!(reg.match_receivers(&IntentQuery::default()).is_empty());

        // Intent categories must be a subset of the filter's.
        let mut reg2 = ReceiverRegistry::new();
        reg2.register_receivers(
            "p",
            vec![receiver(
                "p.Cat",
                true,
                vec![filter(&["A"], &["DEFAULT"], &[])],
            )],
        );
        assert_eq!(
            reg2.match_receivers(&IntentQuery {
                action: Some("A".into()),
                categories: vec!["DEFAULT".into()],
                ..IntentQuery::default()
            })
            .len(),
            1
        );
        assert!(reg2
            .match_receivers(&IntentQuery {
                action: Some("A".into()),
                categories: vec!["UNKNOWN".into()],
                ..IntentQuery::default()
            })
            .is_empty());
    }

    #[test]
    fn unregister_drops_receivers_and_authority_routes() {
        let mut reg = ReceiverRegistry::new();
        reg.register_receivers(
            "p",
            vec![receiver("p.R", true, vec![filter(&["A"], &[], &[])])],
        );
        reg.register_providers(
            "p",
            vec![ProviderSpec {
                class_name: "p.Provider".into(),
                authorities: vec!["p.books".into()],
                enabled: true,
                exported: true,
            }],
        );
        assert_eq!(reg.route_authority("p.books"), Some(("p", "p.Provider")));

        reg.unregister_plugin("p");
        assert!(reg
            .match_receivers(&IntentQuery {
                action: Some("A".into()),
                ..IntentQuery::default()
            })
            .is_empty());
        assert_eq!(reg.route_authority("p.books"), None);
    }

    fn frames(classes: &[&str], stacks: &[&str]) -> ExceptionFrame {
        ExceptionFrame {
            class_name: classes.first().unwrap_or(&"").to_string(),
            stack_classes: stacks.iter().map(ToString::to_string).collect(),
        }
    }

    #[test]
    fn crash_classification_walks_cause_chain_and_ranks_categories() {
        let index = |class: &str| -> Option<String> {
            if class.starts_with("plugin.a.") {
                Some("a".to_string())
            } else {
                None
            }
        };

        // Dependency failure classifies even without stack attribution.
        let verdict = classify_crash(
            &[frames(&["java.lang.RuntimeException"], &[])],
            Some(&DependencyFailure {
                culprit_plugin_id: "b".into(),
                missing_class: "b.Missing".into(),
            }),
            &index,
        )
        .unwrap();
        assert_eq!(verdict.kind, CrashKind::Dependency);
        assert_eq!(verdict.culprit_plugin_id.as_deref(), Some("b"));

        // Attribution walks the cause chain into the plugin's frames.
        let chain = vec![
            frames(&["java.lang.RuntimeException"], &["host.Main"]),
            frames(
                &["java.lang.ClassCastException"],
                &["host.Proxy", "plugin.a.Broken"],
            ),
        ];
        let verdict = classify_crash(&chain, None, &index).unwrap();
        assert_eq!(verdict.kind, CrashKind::ClassCast);
        assert_eq!(verdict.culprit_plugin_id.as_deref(), Some("a"));

        // Linkage errors classify as API incompatibility.
        let chain = vec![frames(&["java.lang.NoSuchMethodError"], &["plugin.a.Old"])];
        assert_eq!(
            classify_crash(&chain, None, &index).unwrap().kind,
            CrashKind::ApiIncompatible
        );

        // NotFoundException matches by exact binary name.
        let chain = vec![frames(
            &["android.content.res.Resources$NotFoundException"],
            &["plugin.a.Ui"],
        )];
        assert_eq!(
            classify_crash(&chain, None, &index).unwrap().kind,
            CrashKind::ResourceNotFound
        );

        // Generic plugin exception → Other; unattributed → no verdict.
        let chain = vec![frames(
            &["java.lang.IllegalStateException"],
            &["plugin.a.X"],
        )];
        assert_eq!(
            classify_crash(&chain, None, &index).unwrap().kind,
            CrashKind::Other
        );
        let chain = vec![frames(&["java.lang.IllegalStateException"], &["host.Only"])];
        assert!(classify_crash(&chain, None, &index).is_none());
    }
}
