//! charter: the permission charter.
//!
//! Rule evaluation for everything the plugin framework must decide before
//! bytes or classes move: install/update adjudication (digest, version
//! monotonicity, signature trust) and sensitive-API access checks.
//! Pure evaluation plus a session-scoped grant cache — no IO, no Android.
//!
//! Signature semantics are **set-subset**, matching how Android signing
//! actually works: a package may carry several signers (`apkContentsSigners`
//! on multi-signer APKs), and a plugin is host-trusted when every one of its
//! signer digests is in the host's trusted set. Install adjudication runs
//! two independent signature gates: update continuity (an update must carry
//! the same signer set as the installed record — Android's own update rule)
//! and host trust (the signer set must be covered by the host's trusted
//! set). `force_overwrite` skips the downgrade ban only — never the
//! signature gates.
//!
//! Deliberate upgrades over the reference design: update packages must carry
//! a SHA-256 digest that is verified here (the reference channel ships no
//! hash and leans on signature alone); and an unrecognized caller is never
//! silently allowed — an unknown identity falls to ask-or-deny, while the
//! host is allowed through an explicit branch.

use std::collections::{BTreeSet, HashMap};

use serde::{Deserialize, Serialize};

/// Permission levels gating framework APIs, least to most privileged.
/// Annotation-level metadata: the KSP weave marks sensitive APIs with a
/// level, and the access rule derives from it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum PermissionLevel {
    /// Open to every installed plugin.
    L0,
    /// Ordinary plugin APIs.
    L1,
    /// Sensitive plugin-to-plugin APIs.
    L2,
    /// Framework-internal APIs.
    L3,
    /// Host-only APIs; process-level isolation is a future concern.
    L4,
}

/// A capability a plugin may declare at install. Sensitive capabilities are
/// adjudicated at install (user grant, cached per plugin) and gateable at
/// runtime through `check_api_access` using `capability:<name>` as the
/// permission key. This is the framework's mechanism for heavy-native
/// facilities (Proot's exec, MNN's GPU/network) to declare — and be gated
/// on — what they need instead of running free.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Capability {
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

impl Capability {
    /// The permission key used by the grant cache and runtime gates.
    #[must_use]
    pub fn permission_key(self) -> String {
        format!("capability:{self:?}")
    }
}

/// How a signature-gate failure is adjudicated.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum SignatureStrategy {
    /// Failure rejects outright.
    Strict,
    /// Failure escalates to a user grant.
    UserGrant,
    /// Failure is ignored (development builds only).
    Insecure,
}

/// Access rule attached to a sensitive API: who may call it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AccessRule {
    /// Caller's signer set must be covered by the host's trusted set.
    Host,
    /// Caller must be the target plugin itself, or be host-trusted.
    SelfOrHost,
    /// Any installed plugin may call.
    AnyPlugin,
}

/// Identity of an API caller, as resolved by the Kotlin side (stack
/// attribution is a JVM concern; only the conclusion crosses).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CallerIdentity {
    /// The host application — explicitly allowed.
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

/// What the framework decided. `RequireUserGrant` routes to the
/// authorization UI; `Deny` is terminal.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Verdict {
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

/// An install or update request, adjudicated before any file lands.
#[derive(Clone, Debug)]
pub struct InstallRequest {
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
    /// Skips the downgrade ban (explicit host override). Signature gates
    /// still apply.
    pub force_overwrite: bool,
    /// Capabilities the package declares it needs. Sensitive ones escalate
    /// to a user grant at install and are gateable at runtime.
    pub capabilities: Vec<Capability>,
}

/// The installed record an update would replace.
#[derive(Clone, Debug)]
pub struct ExistingInstall {
    /// Installed version code.
    pub version_code: u64,
    /// Installed signer digests.
    pub signature_digests: Vec<String>,
}

/// The charter: signature strategy, host trusted signer set, and the
/// session-scoped grant cache. Grants die with the process and with the
/// plugin's removal.
pub struct Charter {
    strategy: SignatureStrategy,
    host_signature_digests: BTreeSet<String>,
    /// (plugin id, permission key) → granted. Only Ask outcomes are cached.
    grants: HashMap<(String, String), bool>,
}

/// Case-insensitive set equality over lowercase-hex digest lists.
fn same_signers(a: &[String], b: &[String]) -> bool {
    a.len() == b.len()
        && a.iter()
            .all(|x| b.iter().any(|y| x.eq_ignore_ascii_case(y)))
}

impl Charter {
    /// Creates a charter. `host_signature_digests` is the trusted signer
    /// set (the host's own signing certificates, lowercase-hex SHA-256).
    #[must_use]
    pub fn new(strategy: SignatureStrategy, host_signature_digests: Vec<String>) -> Self {
        Self {
            strategy,
            host_signature_digests: host_signature_digests.into_iter().collect(),
            grants: HashMap::new(),
        }
    }

    /// The configured signature strategy.
    #[must_use]
    pub fn strategy(&self) -> SignatureStrategy {
        self.strategy
    }

    /// True when every digest in `plugin_digests` is in the host's trusted
    /// set. An empty plugin set is **not** trusted (an unsigned package must
    /// never pass on a technicality).
    #[must_use]
    pub fn is_host_trusted(&self, plugin_digests: &[String]) -> bool {
        !plugin_digests.is_empty()
            && plugin_digests.iter().all(|d| {
                self.host_signature_digests
                    .iter()
                    .any(|h| h.eq_ignore_ascii_case(d))
            })
    }

    /// Maps a signature-gate failure through the configured strategy.
    fn gate_failure(&self, reason: String) -> Verdict {
        match self.strategy {
            SignatureStrategy::Strict => Verdict::Deny { reason },
            SignatureStrategy::UserGrant => Verdict::RequireUserGrant { reason },
            SignatureStrategy::Insecure => Verdict::Allow,
        }
    }

    /// Adjudicates an install/update. Order: package digest (when the
    /// channel published one) → downgrade ban (skippable by
    /// `force_overwrite`) → update signature continuity → host trust.
    #[must_use]
    pub fn adjudicate_install(
        &self,
        request: &InstallRequest,
        existing: Option<&ExistingInstall>,
    ) -> Verdict {
        if let Some(expected) = &request.expected_sha256 {
            if !expected.eq_ignore_ascii_case(&request.package_sha256) {
                return Verdict::Deny {
                    reason: format!(
                        "package digest mismatch: channel published {expected}, package is {}",
                        request.package_sha256
                    ),
                };
            }
        }
        if let Some(old) = existing {
            if !request.force_overwrite && request.version_code <= old.version_code {
                return Verdict::Deny {
                    reason: format!(
                        "downgrade refused: installed versionCode {} >= incoming {}",
                        old.version_code, request.version_code
                    ),
                };
            }
            if !same_signers(&request.signature_digests, &old.signature_digests) {
                return self.gate_failure(
                    "update signer set differs from the installed record".to_string(),
                );
            }
        }
        if !self.is_host_trusted(&request.signature_digests) {
            return self.gate_failure(
                "plugin signer set is not covered by the host trusted set".to_string(),
            );
        }
        Verdict::Allow
    }

    /// Evaluates an access rule for one call. A cached grant settles an Ask
    /// without re-prompting; a cached refusal does not deny outright — the
    /// user may be asked again.
    #[must_use]
    pub fn check_api_access(
        &self,
        rule: AccessRule,
        hard_fail: bool,
        caller: &CallerIdentity,
        target_plugin_id: &str,
        permission_key: &str,
    ) -> Verdict {
        let fallback = || {
            if hard_fail {
                Verdict::Deny {
                    reason: format!("access rule {rule:?} failed (hard fail)"),
                }
            } else {
                Verdict::RequireUserGrant {
                    reason: format!("access rule {rule:?} requires authorization"),
                }
            }
        };
        match caller {
            CallerIdentity::Host => Verdict::Allow,
            CallerIdentity::Unknown => fallback(),
            CallerIdentity::Plugin {
                plugin_id,
                signature_digests,
            } => {
                let passes = match rule {
                    AccessRule::Host => self.is_host_trusted(signature_digests),
                    AccessRule::SelfOrHost => {
                        plugin_id == target_plugin_id || self.is_host_trusted(signature_digests)
                    }
                    AccessRule::AnyPlugin => true,
                };
                if passes {
                    return Verdict::Allow;
                }
                if !hard_fail
                    && self
                        .grants
                        .get(&(plugin_id.clone(), permission_key.to_string()))
                        .copied()
                        == Some(true)
                {
                    return Verdict::Allow;
                }
                fallback()
            }
        }
    }

    /// Adjudicates a plugin's declared capabilities at install. Every
    /// declared capability is sensitive: an unauthorized one escalates to a
    /// user grant (with the pending set in the reason), and a previously
    /// cached grant settles it without re-prompting. An empty declaration
    /// passes trivially.
    #[must_use]
    pub fn adjudicate_capabilities(
        &self,
        plugin_id: &str,
        capabilities: &[Capability],
    ) -> Verdict {
        let mut pending = Vec::new();
        for capability in capabilities {
            let granted = self
                .grants
                .get(&(plugin_id.to_string(), capability.permission_key()))
                .copied()
                == Some(true);
            if !granted {
                pending.push(format!("{capability:?}"));
            }
        }
        if pending.is_empty() {
            Verdict::Allow
        } else {
            Verdict::RequireUserGrant {
                reason: format!("插件请求敏感能力: {}", pending.join(", ")),
            }
        }
    }

    /// Records the user's answer to an authorization prompt.
    pub fn record_grant(&mut self, plugin_id: &str, permission_key: &str, granted: bool) {
        self.grants
            .insert((plugin_id.to_string(), permission_key.to_string()), granted);
    }

    /// Drops every cached grant of a plugin (uninstall, or crash-guided
    /// disable). Grant caching must never outlive the plugin it refers to.
    pub fn drop_plugin(&mut self, plugin_id: &str) {
        self.grants.retain(|(id, _), _| id != plugin_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn charter() -> Charter {
        Charter::new(
            SignatureStrategy::UserGrant,
            vec!["aa".to_string(), "bb".to_string()],
        )
    }

    fn request(version: u64, signers: &[&str]) -> InstallRequest {
        InstallRequest {
            plugin_id: "p".to_string(),
            version_code: version,
            signature_digests: signers.iter().map(ToString::to_string).collect(),
            package_sha256: "dd".to_string(),
            expected_sha256: None,
            force_overwrite: false,
            capabilities: Vec::new(),
        }
    }

    fn existing(version: u64, signers: &[&str]) -> ExistingInstall {
        ExistingInstall {
            version_code: version,
            signature_digests: signers.iter().map(ToString::to_string).collect(),
        }
    }

    #[test]
    fn capability_adjudication_escalates_and_caches() {
        let mut c = charter();
        // Empty declaration passes trivially.
        assert_eq!(c.adjudicate_capabilities("p", &[]), Verdict::Allow);
        // An unauthorized capability escalates, naming the pending set.
        match c.adjudicate_capabilities("p", &[Capability::Gpu, Capability::Network]) {
            Verdict::RequireUserGrant { reason } => {
                assert!(reason.contains("Gpu") && reason.contains("Network"));
            }
            other => panic!("expected RequireUserGrant, got {other:?}"),
        }
        // Granting one capability settles only that one.
        c.record_grant("p", &Capability::Gpu.permission_key(), true);
        match c.adjudicate_capabilities("p", &[Capability::Gpu]) {
            Verdict::Allow => {}
            other => panic!("expected Allow after grant, got {other:?}"),
        }
        // The ungranted one still escalates.
        assert!(matches!(
            c.adjudicate_capabilities("p", &[Capability::Network]),
            Verdict::RequireUserGrant { .. }
        ));
        // Dropping the plugin revokes the cached grant.
        c.drop_plugin("p");
        assert!(matches!(
            c.adjudicate_capabilities("p", &[Capability::Gpu]),
            Verdict::RequireUserGrant { .. }
        ));
    }

    #[test]
    fn host_trust_is_set_subset_and_rejects_empty() {
        let c = charter();
        assert!(c.is_host_trusted(&["aa".to_string()]));
        assert!(c.is_host_trusted(&["aa".to_string(), "BB".to_string()]));
        assert!(!c.is_host_trusted(&["aa".to_string(), "zz".to_string()]));
        assert!(!c.is_host_trusted(&[]));
    }

    #[test]
    fn digest_mismatch_denies_even_when_trusted() {
        let mut req = request(2, &["aa"]);
        req.expected_sha256 = Some("ee".to_string());
        assert!(matches!(
            charter().adjudicate_install(&req, Some(&existing(1, &["aa"]))),
            Verdict::Deny { .. }
        ));
        req.expected_sha256 = Some("DD".to_string()); // case-insensitive hex
        assert_eq!(
            charter().adjudicate_install(&req, Some(&existing(1, &["aa"]))),
            Verdict::Allow
        );
    }

    #[test]
    fn downgrade_denied_unless_forced_but_force_never_skips_signatures() {
        let c = charter();
        assert!(matches!(
            c.adjudicate_install(&request(1, &["aa"]), Some(&existing(1, &["aa"]))),
            Verdict::Deny { .. }
        ));
        // Forced downgrade of a trusted, same-signer package passes.
        let mut forced = request(1, &["aa"]);
        forced.force_overwrite = true;
        assert_eq!(
            c.adjudicate_install(&forced, Some(&existing(1, &["aa"]))),
            Verdict::Allow
        );
        // Forced downgrade with an untrusted signer still hits the gate.
        let mut forced_bad = request(1, &["zz"]);
        forced_bad.force_overwrite = true;
        assert!(matches!(
            Charter::new(SignatureStrategy::Strict, vec!["aa".into()])
                .adjudicate_install(&forced_bad, Some(&existing(1, &["zz"]))),
            Verdict::Deny { .. }
        ));
    }

    #[test]
    fn update_continuity_and_host_trust_are_independent_gates() {
        let strict = Charter::new(SignatureStrategy::Strict, vec!["aa".into()]);
        // Same signer set but not host-trusted → trust gate.
        assert!(matches!(
            strict.adjudicate_install(&request(2, &["zz"]), Some(&existing(1, &["zz"]))),
            Verdict::Deny { .. }
        ));
        // Host-trusted but different signer set → continuity gate.
        assert!(matches!(
            strict.adjudicate_install(&request(2, &["aa"]), Some(&existing(1, &["zz"]))),
            Verdict::Deny { .. }
        ));
        // UserGrant escalates both.
        let grant = charter();
        assert!(matches!(
            grant.adjudicate_install(&request(2, &["aa"]), Some(&existing(1, &["bb"]))),
            Verdict::RequireUserGrant { .. }
        ));
        // Insecure ignores both.
        let insecure = Charter::new(SignatureStrategy::Insecure, vec!["aa".into()]);
        assert_eq!(
            insecure.adjudicate_install(&request(2, &["zz"]), Some(&existing(1, &["qq"]))),
            Verdict::Allow
        );
    }

    #[test]
    fn host_caller_allowed_explicitly_unknown_never_silently() {
        let c = charter();
        assert_eq!(
            c.check_api_access(AccessRule::Host, false, &CallerIdentity::Host, "p", "k"),
            Verdict::Allow
        );
        assert!(matches!(
            c.check_api_access(AccessRule::Host, false, &CallerIdentity::Unknown, "p", "k"),
            Verdict::RequireUserGrant { .. }
        ));
        assert!(matches!(
            c.check_api_access(AccessRule::Host, true, &CallerIdentity::Unknown, "p", "k"),
            Verdict::Deny { .. }
        ));
    }

    #[test]
    fn self_or_host_rule_and_grant_cache() {
        let mut c = charter();
        let self_caller = CallerIdentity::Plugin {
            plugin_id: "p".to_string(),
            signature_digests: vec!["zz".to_string()],
        };
        let other_caller = CallerIdentity::Plugin {
            plugin_id: "q".to_string(),
            signature_digests: vec!["zz".to_string()],
        };
        let host_signed = CallerIdentity::Plugin {
            plugin_id: "q".to_string(),
            signature_digests: vec!["AA".to_string()],
        };
        assert_eq!(
            c.check_api_access(AccessRule::SelfOrHost, false, &self_caller, "p", "k"),
            Verdict::Allow
        );
        assert_eq!(
            c.check_api_access(AccessRule::SelfOrHost, false, &host_signed, "p", "k"),
            Verdict::Allow
        );
        assert!(matches!(
            c.check_api_access(AccessRule::SelfOrHost, false, &other_caller, "p", "k"),
            Verdict::RequireUserGrant { .. }
        ));

        // Grant settles the Ask; dropping the plugin revokes it.
        c.record_grant("q", "k", true);
        assert_eq!(
            c.check_api_access(AccessRule::SelfOrHost, false, &other_caller, "p", "k"),
            Verdict::Allow
        );
        c.drop_plugin("q");
        assert!(matches!(
            c.check_api_access(AccessRule::SelfOrHost, false, &other_caller, "p", "k"),
            Verdict::RequireUserGrant { .. }
        ));
    }
}
