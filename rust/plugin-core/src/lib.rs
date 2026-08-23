//! plugin-core: the dynamic plugin framework's decision core.
//!
//! Three modules, one facade:
//! - [`ledger`]: installed-plugin registry — transactional commits,
//!   bidirectional class index, three-way audit, atomic persistence;
//! - [`topology`]: dependency graph — borrow-on-first-use edges,
//!   deterministic chained-restart plans, exact-match instance registry;
//! - [`charter`]: permission charter — install adjudication (digest,
//!   version monotonicity, signature strategy), access rules, grant cache.
//!
//! The facade [`PluginCore`] is plan-style: every call returns a complete
//! decision (verdict, plan, report); the Kotlin side executes file and
//! ClassLoader operations. No Android API, no uniffi dependency — the
//! `ffi` crate owns the boundary.

mod charter;
mod dispatch;
mod ledger;
mod topology;

use std::collections::BTreeSet;
use std::path::Path;

use thiserror::Error;

pub use charter::{
    AccessRule, CallerIdentity, Capability, Charter, ExistingInstall, InstallRequest,
    PermissionLevel, SignatureStrategy, Verdict,
};
pub use dispatch::{
    classify_crash, CrashKind, CrashVerdict, DependencyFailure, ExceptionFrame, IntentFilter,
    IntentQuery, ProviderSpec, ReceiverRegistry, StaticReceiver,
};
pub use ledger::{AuditDrift, AuditReport, Ledger, LedgerError, PluginRecord};
pub use topology::{BorrowEdge, RestartPlan, Topology};

/// Errors of the combined facade.
#[derive(Debug, Error)]
pub enum CoreError {
    /// Registry failure.
    #[error(transparent)]
    Ledger(#[from] LedgerError),
}

/// Where a class lookup resolved.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LocateOutcome {
    /// The class is provided by this installed plugin.
    Plugin {
        /// Providing plugin id.
        plugin_id: String,
    },
    /// The index missed; the caller should try the host class loader
    /// (host-fallback semantics) before failing the load.
    HostFallback,
}

/// The framework's decision core: ledger + topology + charter + dispatch
/// behind one plan-style API.
pub struct PluginCore {
    ledger: Ledger,
    topology: Topology,
    charter: Charter,
    receivers: ReceiverRegistry,
}

impl PluginCore {
    /// An unpersisted core (tests, ephemeral sessions).
    #[must_use]
    pub fn in_memory(strategy: SignatureStrategy, host_signature_digests: Vec<String>) -> Self {
        Self {
            ledger: Ledger::in_memory(),
            topology: Topology::new(),
            charter: Charter::new(strategy, host_signature_digests),
            receivers: ReceiverRegistry::new(),
        }
    }

    /// Opens the registry file at `path` (recovering crash-interrupted
    /// rotations) with a fresh topology and grant cache — both rebuild from
    /// use, matching their session scope.
    ///
    /// # Errors
    /// Returns [`CoreError::Ledger`] when the file is corrupt or unreadable.
    pub fn open(
        path: impl AsRef<Path>,
        strategy: SignatureStrategy,
        host_signature_digests: Vec<String>,
    ) -> Result<Self, CoreError> {
        Ok(Self {
            ledger: Ledger::open(path)?,
            topology: Topology::new(),
            charter: Charter::new(strategy, host_signature_digests),
            receivers: ReceiverRegistry::new(),
        })
    }

    /// Adjudicates an install/update against the registry's current record
    /// for the same plugin id.
    #[must_use]
    pub fn adjudicate_install(&self, request: &InstallRequest) -> Verdict {
        let existing = self
            .ledger
            .record(&request.plugin_id)
            .map(|r| ExistingInstall {
                version_code: r.version_code,
                signature_digests: r.signature_digests.clone(),
            });
        self.charter.adjudicate_install(request, existing.as_ref())
    }

    /// Adjudicates a plugin's declared capabilities at install (escalate to
    /// user grant until each is authorized). Signature/version gates run
    /// separately through [`PluginCore::adjudicate_install`].
    #[must_use]
    pub fn adjudicate_capabilities(&self, plugin_id: &str, capabilities: &[Capability]) -> Verdict {
        self.charter.adjudicate_capabilities(plugin_id, capabilities)
    }

    /// Commits an install/update after the Kotlin side has placed the
    /// files. See [`Ledger::commit_install`].
    ///
    /// # Errors
    /// Returns [`CoreError::Ledger`] on persistence failure (rolled back).
    pub fn commit_install(&mut self, record: PluginRecord) -> Result<(), CoreError> {
        Ok(self.ledger.commit_install(record)?)
    }

    /// Commits an uninstall: registry entry, graph edges, instance
    /// registrations, and cached grants all leave together.
    ///
    /// # Errors
    /// Returns [`CoreError::Ledger`] when the plugin is unknown or
    /// persistence fails.
    pub fn commit_uninstall(&mut self, plugin_id: &str) -> Result<PluginRecord, CoreError> {
        let record = self.ledger.commit_uninstall(plugin_id)?;
        self.topology.remove_plugin(plugin_id);
        self.charter.drop_plugin(plugin_id);
        self.receivers.unregister_plugin(plugin_id);
        Ok(record)
    }

    /// Enables or disables a plugin (crash-guided disable uses this).
    ///
    /// # Errors
    /// Returns [`CoreError::Ledger`] when the plugin is unknown or
    /// persistence fails.
    pub fn set_enabled(&mut self, plugin_id: &str, enabled: bool) -> Result<(), CoreError> {
        Ok(self.ledger.set_enabled(plugin_id, enabled)?)
    }

    /// Runtime unload (the plugin stays installed): receiver/provider
    /// routes, borrow edges, and pooled-service instances leave — they are
    /// all session state that rebuilds on the next load. The registry entry
    /// and cached grants stay.
    pub fn plugin_unloaded(&mut self, plugin_id: &str) {
        self.receivers.unregister_plugin(plugin_id);
        self.topology.remove_plugin(plugin_id);
    }

    /// Locates a class. On an index hit the borrow edge is recorded (when
    /// the borrower is another plugin); on a miss the host-fallback outcome
    /// is returned — the Kotlin side tries the host class loader and only
    /// then fails the load. A class owned by a **disabled** plugin is
    /// unreachable (the reference design never indexes unloaded plugins;
    /// our persisted index gates on `enabled` instead).
    #[must_use]
    pub fn locate_class(&mut self, class: &str, borrower: Option<&str>) -> LocateOutcome {
        match self.ledger.lookup_class(class) {
            Some(plugin_id) => {
                let reachable = self.ledger.record(plugin_id).is_some_and(|r| r.enabled);
                if !reachable {
                    return LocateOutcome::HostFallback;
                }
                if let Some(borrower) = borrower {
                    self.topology.record_borrow(borrower, plugin_id);
                }
                LocateOutcome::Plugin {
                    plugin_id: plugin_id.to_string(),
                }
            }
            None => LocateOutcome::HostFallback,
        }
    }

    /// The deterministic chained-restart plan for updating `plugin_id`.
    #[must_use]
    pub fn restart_plan(&self, plugin_id: &str) -> RestartPlan {
        self.topology.restart_plan(plugin_id)
    }

    /// Everything that depends on `plugin_id`, transitively — the chain
    /// **excludes** the queried plugin itself (matching the reference
    /// design's chain queries; the restart plan keeps the root).
    #[must_use]
    pub fn dependents_chain(&self, plugin_id: &str) -> Vec<String> {
        let mut chain = self.topology.affected_closure(plugin_id);
        if chain.first().map(String::as_str) == Some(plugin_id) {
            chain.remove(0);
        }
        chain
    }

    /// Everything `plugin_id` depends on, transitively — excludes the
    /// queried plugin itself.
    #[must_use]
    pub fn dependencies_chain(&self, plugin_id: &str) -> Vec<String> {
        let mut chain = self.topology.dependencies_closure(plugin_id);
        if chain.first().map(String::as_str) == Some(plugin_id) {
            chain.remove(0);
        }
        chain
    }

    /// Three-way reconciliation: registry ↔ index ↔ loaded set.
    #[must_use]
    pub fn audit(&self, loaded: &[String]) -> AuditReport {
        let loaded: BTreeSet<String> = loaded.iter().cloned().collect();
        self.ledger.audit(&loaded)
    }

    /// Fixes index drift on sight; returns what was repaired.
    ///
    /// # Errors
    /// Returns [`CoreError::Ledger`] on persistence failure.
    pub fn repair(&mut self) -> Result<AuditReport, CoreError> {
        Ok(self.ledger.repair()?)
    }

    /// Evaluates an access rule for one sensitive-API call. A caller
    /// claiming a plugin identity that is not registered (or is disabled)
    /// is treated as unattributed — the reference design resolves callers
    /// through the loaded-plugin set and rejects unknowns the same way.
    #[must_use]
    pub fn check_api_access(
        &self,
        rule: AccessRule,
        hard_fail: bool,
        caller: &CallerIdentity,
        target_plugin_id: &str,
        permission_key: &str,
    ) -> Verdict {
        let effective;
        let caller = match caller {
            CallerIdentity::Plugin { plugin_id, .. } => {
                let live = self.ledger.record(plugin_id).is_some_and(|r| r.enabled);
                if live {
                    caller
                } else {
                    effective = CallerIdentity::Unknown;
                    &effective
                }
            }
            _ => caller,
        };
        self.charter
            .check_api_access(rule, hard_fail, caller, target_plugin_id, permission_key)
    }

    /// Records the user's answer to an authorization prompt.
    pub fn record_grant(&mut self, plugin_id: &str, permission_key: &str, granted: bool) {
        self.charter
            .record_grant(plugin_id, permission_key, granted);
    }

    /// Registry access for read-only views.
    #[must_use]
    pub fn ledger(&self) -> &Ledger {
        &self.ledger
    }

    /// Graph access for read-only views.
    #[must_use]
    pub fn topology(&self) -> &Topology {
        &self.topology
    }

    /// Registers a pooled-service instance (`"className:taskN"`).
    pub fn register_instance(&mut self, instance_id: &str, plugin_id: &str) {
        self.topology.register_instance(instance_id, plugin_id);
    }

    /// Drops one pooled-service instance registration.
    pub fn unregister_instance(&mut self, instance_id: &str) {
        self.topology.unregister_instance(instance_id);
    }

    /// Registers a plugin's static receivers at load (component-disabled
    /// entries are skipped). Pass the parsed list from the record.
    pub fn register_receivers(&mut self, plugin_id: &str, receivers: Vec<StaticReceiver>) {
        self.receivers.register_receivers(plugin_id, receivers);
    }

    /// Registers a plugin's content providers at load.
    pub fn register_providers(&mut self, plugin_id: &str, providers: Vec<ProviderSpec>) {
        self.receivers.register_providers(plugin_id, providers);
    }

    /// Matches a broadcast against every registered receiver, in
    /// registration order; the Kotlin proxy layer dispatches the hits.
    /// Semantics are narrowed Android filter matching (see [`dispatch`]).
    #[must_use]
    pub fn match_receivers(&self, query: &IntentQuery) -> Vec<(String, StaticReceiver)> {
        self.receivers
            .match_receivers(query)
            .into_iter()
            .map(|(id, r)| (id.to_string(), r.clone()))
            .collect()
    }

    /// Routes a provider authority to `(owning plugin, provider class)`.
    #[must_use]
    pub fn route_authority(&self, authority: &str) -> Option<(String, String)> {
        self.receivers
            .route_authority(authority)
            .map(|(owner, class)| (owner.to_string(), class.to_string()))
    }

    /// Classifies one uncaught exception against the class index: cause-
    /// chain attribution plus category precedence (dependency → class-cast
    /// → resource → linkage → other). See [`classify_crash`].
    #[must_use]
    pub fn classify_crash(
        &self,
        chain: &[ExceptionFrame],
        dependency_failure: Option<&DependencyFailure>,
    ) -> Option<CrashVerdict> {
        classify_crash(chain, dependency_failure, |class| {
            self.ledger.lookup_class(class).map(str::to_string)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(id: &str, version: u64, classes: &[&str]) -> PluginRecord {
        PluginRecord {
            plugin_id: id.to_string(),
            name: id.to_uppercase(),
            icon_res_id: None,
            version_code: version,
            version_name: format!("{version}.0"),
            entry_class: format!("{id}.Entry"),
            description: String::new(),
            signature_digests: vec!["sig".to_string()],
            package_sha256: "pkg".to_string(),
            install_path: format!("/plugins/{id}"),
            enabled: true,
            installed_at_ms: 0,
            classes: classes.iter().map(ToString::to_string).collect(),
            static_receivers_json: None,
            providers_json: None,
            capabilities: Vec::new(),
        }
    }

    #[test]
    fn locate_records_borrow_and_falls_back_to_host_on_miss() {
        let mut core = PluginCore::in_memory(SignatureStrategy::Strict, vec!["host".to_string()]);
        core.commit_install(record("a", 1, &["a.Foo"])).unwrap();
        core.commit_install(record("b", 1, &[])).unwrap();

        assert_eq!(
            core.locate_class("a.Foo", Some("b")),
            LocateOutcome::Plugin {
                plugin_id: "a".to_string()
            }
        );
        assert_eq!(
            core.topology().edges(),
            vec![BorrowEdge {
                borrower: "b".into(),
                lender: "a".into()
            }]
        );
        assert_eq!(
            core.locate_class("host.Thing", Some("b")),
            LocateOutcome::HostFallback
        );
    }

    #[test]
    fn uninstall_clears_registry_graph_and_grants_together() {
        let mut core = PluginCore::in_memory(SignatureStrategy::Strict, vec!["host".to_string()]);
        core.commit_install(record("a", 1, &["a.Foo"])).unwrap();
        core.commit_install(record("b", 1, &[])).unwrap();
        let _ = core.locate_class("a.Foo", Some("b"));
        core.register_instance("a.Svc:task1", "a");
        core.record_grant("a", "k", true);

        // Before uninstall: "a" is registered and its cached grant settles
        // a SelfOrHost check against "b" (a ≠ b, "x" is not host-trusted).
        let caller_a = || CallerIdentity::Plugin {
            plugin_id: "a".to_string(),
            signature_digests: vec!["x".to_string()],
        };
        assert_eq!(
            core.check_api_access(AccessRule::SelfOrHost, false, &caller_a(), "b", "k"),
            Verdict::Allow
        );

        core.commit_uninstall("a").unwrap();
        assert!(core.ledger().record("a").is_none());
        assert!(core.topology().edges().is_empty());
        assert_eq!(core.topology().instance_owner("a.Svc:task1"), None);
        // After uninstall: "a" is no longer a registered caller, so it is
        // treated as unattributed and falls back to ask — the dropped grant
        // is never consulted.
        assert!(matches!(
            core.check_api_access(AccessRule::SelfOrHost, false, &caller_a(), "b", "k"),
            Verdict::RequireUserGrant { .. }
        ));
    }

    #[test]
    fn locate_skips_disabled_plugins() {
        let mut core = PluginCore::in_memory(SignatureStrategy::Strict, vec!["host".to_string()]);
        core.commit_install(record("a", 1, &["a.Foo"])).unwrap();
        core.commit_install(record("b", 1, &[])).unwrap();
        core.set_enabled("a", false).unwrap();
        assert_eq!(
            core.locate_class("a.Foo", Some("b")),
            LocateOutcome::HostFallback
        );
        assert!(core.topology().edges().is_empty()); // no edge recorded
        core.set_enabled("a", true).unwrap();
        assert_eq!(
            core.locate_class("a.Foo", Some("b")),
            LocateOutcome::Plugin {
                plugin_id: "a".to_string()
            }
        );
    }

    #[test]
    fn dispatch_flows_through_facade() {
        use dispatch::{IntentFilter, IntentQuery, StaticReceiver};
        let mut core = PluginCore::in_memory(SignatureStrategy::Strict, vec!["host".to_string()]);
        core.commit_install(record("a", 1, &["a.R", "a.Broken"]))
            .unwrap();
        core.register_receivers(
            "a",
            vec![StaticReceiver {
                class_name: "a.R".to_string(),
                enabled: true,
                exported: false,
                intent_filters: vec![IntentFilter {
                    actions: vec!["A".to_string()],
                    ..IntentFilter::default()
                }],
            }],
        );
        // Non-exported receiver only matches internal broadcasts.
        assert!(core
            .match_receivers(&IntentQuery {
                action: Some("A".to_string()),
                ..IntentQuery::default()
            })
            .is_empty());
        let hits = core.match_receivers(&IntentQuery {
            action: Some("A".to_string()),
            is_internal: true,
            ..IntentQuery::default()
        });
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].0, "a");

        // Crash attribution walks the class index.
        let verdict = core
            .classify_crash(
                &[ExceptionFrame {
                    class_name: "java.lang.ClassCastException".to_string(),
                    stack_classes: vec!["a.Broken".to_string()],
                }],
                None,
            )
            .unwrap();
        assert_eq!(verdict.kind, CrashKind::ClassCast);
        assert_eq!(verdict.culprit_plugin_id.as_deref(), Some("a"));

        // Uninstall cascades receiver routes away.
        core.commit_uninstall("a").unwrap();
        assert!(core
            .match_receivers(&IntentQuery {
                action: Some("A".to_string()),
                is_internal: true,
                ..IntentQuery::default()
            })
            .is_empty());
    }

    #[test]
    fn restart_plan_flows_through_facade() {
        let mut core = PluginCore::in_memory(SignatureStrategy::Strict, vec!["host".to_string()]);
        core.commit_install(record("a", 1, &["a.Foo"])).unwrap();
        core.commit_install(record("b", 1, &[])).unwrap();
        let _ = core.locate_class("a.Foo", Some("b"));

        let plan = core.restart_plan("a");
        assert_eq!(plan.reload_order, vec!["a", "b"]);
        assert_eq!(plan.unload_order, vec!["b", "a"]);
    }
}
