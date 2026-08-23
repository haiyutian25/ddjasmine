//! ledger: the plugin registry.
//!
//! Installed-plugin metadata with transactional commit and atomic
//! persistence (`store::atomic_write`: tmp → fsync → rename, `.bak`
//! rotation, recovery on read). The class index is **bidirectional**:
//! `class → plugin` for O(1) lookup, `plugin → classes` riding the record
//! for O(1) removal — the reference design's index removal is an O(n)
//! full-table scan and its drift is logged but never reconciled; here
//! [`Ledger::audit`] reconciles registry ↔ index ↔ loaded set and
//! [`Ledger::repair`] fixes drift on sight.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use store::StoreError;
use thiserror::Error;

/// On-disk format version; no compatibility promise while it stays at 0.
pub const LEDGER_FORMAT_VERSION: u32 = 0;

/// Errors of the ledger.
#[derive(Debug, Error)]
pub enum LedgerError {
    /// Persistence failure.
    #[error("store error: {0}")]
    Store(#[from] StoreError),
    /// The ledger file is not well-formed.
    #[error("corrupt ledger: {0}")]
    Corrupt(String),
    /// Operation referenced a plugin that is not registered.
    #[error("unknown plugin: {0}")]
    UnknownPlugin(String),
}

/// One installed plugin.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PluginRecord {
    /// Plugin id from the package metadata.
    pub plugin_id: String,
    /// Display name (application label).
    pub name: String,
    /// Launcher icon resource id, when the package declares one.
    pub icon_res_id: Option<u32>,
    /// Monotonic version code.
    pub version_code: u64,
    /// Human-readable version.
    pub version_name: String,
    /// Fully-qualified entry class name (`plugin.entryClass` meta-data);
    /// the lifecycle executor instantiates this class, so the record must
    /// carry it — re-parsing the APK at load time is not an option.
    pub entry_class: String,
    /// Free-form description (`plugin.description` meta-data).
    pub description: String,
    /// SHA-256 digests of the signing certificates, lowercase hex (a
    /// multi-signer package carries several).
    pub signature_digests: Vec<String>,
    /// SHA-256 digest of the package bytes, lowercase hex.
    pub package_sha256: String,
    /// Directory the package payload was installed into.
    pub install_path: String,
    /// Disabled plugins stay registered but are skipped at load.
    pub enabled: bool,
    /// Install time, milliseconds since the Unix epoch.
    pub installed_at_ms: i64,
    /// Classes this plugin provides (from the build-time DEX scan) — the
    /// reverse half of the bidirectional index.
    pub classes: BTreeSet<String>,
    /// Static broadcast receivers parsed at install, serialized as JSON.
    /// Opaque to the ledger; the proxy layer registers them at load.
    pub static_receivers_json: Option<String>,
    /// Content providers parsed at install, serialized as JSON. Opaque to
    /// the ledger; the proxy layer registers them at load.
    pub providers_json: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct LedgerFile {
    format_version: u32,
    records: Vec<PluginRecord>,
}

/// One observed inconsistency between registry, index, and loaded set.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AuditDrift {
    /// Index points at a plugin that is not registered.
    IndexTargetsUnknownPlugin {
        /// The indexed class.
        class: String,
        /// The plugin id the index names.
        plugin_id: String,
    },
    /// Index and registry disagree: the owning record does not list the
    /// class (package content changed without a commit).
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
    /// Index points at an installed plugin that is not currently loaded
    /// (informational; load order may legitimately lag).
    IndexTargetNotLoaded {
        /// The indexed class.
        class: String,
        /// The plugin the index names.
        plugin_id: String,
    },
}

/// Three-way reconciliation report: registry ↔ index ↔ loaded set.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AuditReport {
    /// Every drift found, in deterministic order.
    pub drifts: Vec<AuditDrift>,
}

impl AuditReport {
    /// True when registry, index, and loaded set are fully consistent
    /// (ignoring the informational not-loaded entries).
    #[must_use]
    pub fn is_clean(&self) -> bool {
        self.drifts
            .iter()
            .all(|d| matches!(d, AuditDrift::IndexTargetNotLoaded { .. }))
    }
}

/// The registry: records plus the bidirectional class index. Persistence
/// is optional (`in_memory` for tests and ephemeral sessions).
pub struct Ledger {
    records: BTreeMap<String, PluginRecord>,
    class_index: HashMap<String, String>,
    path: Option<PathBuf>,
}

impl Ledger {
    /// An empty, unpersisted ledger.
    #[must_use]
    pub fn in_memory() -> Self {
        Self {
            records: BTreeMap::new(),
            class_index: HashMap::new(),
            path: None,
        }
    }

    /// Opens (or initializes) the ledger file at `path`, recovering from a
    /// crash mid-rotation via the atomic-write sidecars.
    ///
    /// # Errors
    /// Returns [`LedgerError::Corrupt`] when the file exists but is not a
    /// well-formed ledger of the supported format version, and
    /// [`LedgerError::Store`] on IO failure.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, LedgerError> {
        let path = path.as_ref().to_path_buf();
        let mut ledger = Self::in_memory();
        let mut recovered_from_backup = false;
        match store::read_atomic(&path)? {
            None => {}
            Some(bytes) => match Self::parse(&bytes) {
                Ok(file) => ledger.load_file(file),
                Err(main_error) => {
                    // Corruption fallback: the retained .bak is the last
                    // known-good generation (ComboLite's tryRestoreFromBackup).
                    match store::read_backup(&path)? {
                        Some(bak_bytes) => {
                            let file = Self::parse(&bak_bytes).map_err(|bak_error| {
                                LedgerError::Corrupt(format!(
                                    "main unreadable ({main_error}); backup also unreadable ({bak_error})"
                                ))
                            })?;
                            ledger.load_file(file);
                            recovered_from_backup = true;
                        }
                        None => {
                            return Err(LedgerError::Corrupt(format!(
                                "main unreadable ({main_error}); no backup available"
                            )));
                        }
                    }
                }
            },
        }
        ledger.path = Some(path);
        if recovered_from_backup {
            // Rewrite the main file from the recovered state immediately.
            ledger.persist()?;
        }
        Ok(ledger)
    }

    fn parse(bytes: &[u8]) -> Result<LedgerFile, String> {
        let file: LedgerFile = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
        if file.format_version != LEDGER_FORMAT_VERSION {
            return Err(format!(
                "unsupported format_version {}; this build supports {LEDGER_FORMAT_VERSION}",
                file.format_version
            ));
        }
        Ok(file)
    }

    fn load_file(&mut self, file: LedgerFile) {
        for record in file.records {
            self.index_record(&record);
            self.records.insert(record.plugin_id.clone(), record);
        }
    }

    /// Commits an install or update: the record and its index entries land
    /// together, the old record's stale index entries leave together, and
    /// the result is persisted before returning. `enabled` survives an
    /// update when the incoming record carries the default (`true`) and an
    /// old record exists.
    ///
    /// # Errors
    /// Returns [`LedgerError::Store`] when persistence fails; the in-memory
    /// state is rolled back so memory and disk never diverge silently.
    pub fn commit_install(&mut self, mut record: PluginRecord) -> Result<(), LedgerError> {
        let plugin_id = record.plugin_id.clone();
        let old = self.records.remove(&plugin_id);
        if let Some(old_record) = &old {
            if record.enabled && !old_record.enabled {
                record.enabled = false; // disabled state survives updates
            }
            // Install time survives updates: it marks the first install,
            // not the latest payload.
            record.installed_at_ms = old_record.installed_at_ms;
            self.unindex_record(old_record);
        }
        self.index_record(&record);
        self.records.insert(plugin_id.clone(), record);
        if let Err(e) = self.persist() {
            // Roll back to the pre-commit state.
            if let Some(new_record) = self.records.remove(&plugin_id) {
                self.unindex_record(&new_record);
            }
            if let Some(old_record) = old {
                self.index_record(&old_record);
                self.records
                    .insert(old_record.plugin_id.clone(), old_record);
            }
            return Err(e);
        }
        Ok(())
    }

    /// Commits an uninstall: record and all its index entries leave
    /// together (O(classes), never a full-table scan).
    ///
    /// # Errors
    /// Returns [`LedgerError::UnknownPlugin`] when the plugin is not
    /// registered, [`LedgerError::Store`] when persistence fails (in-memory
    /// state is rolled back).
    pub fn commit_uninstall(&mut self, plugin_id: &str) -> Result<PluginRecord, LedgerError> {
        let record = self
            .records
            .remove(plugin_id)
            .ok_or_else(|| LedgerError::UnknownPlugin(plugin_id.to_string()))?;
        self.unindex_record(&record);
        if let Err(e) = self.persist() {
            self.index_record(&record);
            self.records
                .insert(record.plugin_id.clone(), record.clone());
            return Err(e);
        }
        Ok(record)
    }

    /// Enables or disables a registered plugin.
    ///
    /// # Errors
    /// Returns [`LedgerError::UnknownPlugin`] when the plugin is not
    /// registered, [`LedgerError::Store`] when persistence fails.
    pub fn set_enabled(&mut self, plugin_id: &str, enabled: bool) -> Result<(), LedgerError> {
        let record = self
            .records
            .get_mut(plugin_id)
            .ok_or_else(|| LedgerError::UnknownPlugin(plugin_id.to_string()))?;
        let previous = record.enabled;
        record.enabled = enabled;
        if let Err(e) = self.persist() {
            self.records
                .get_mut(plugin_id)
                .expect("record present")
                .enabled = previous;
            return Err(e);
        }
        Ok(())
    }

    /// The registered record for `plugin_id`.
    #[must_use]
    pub fn record(&self, plugin_id: &str) -> Option<&PluginRecord> {
        self.records.get(plugin_id)
    }

    /// All records, ordered by plugin id.
    #[must_use]
    pub fn records(&self) -> Vec<&PluginRecord> {
        self.records.values().collect()
    }

    /// O(1) class lookup: which plugin provides `class`.
    #[must_use]
    pub fn lookup_class(&self, class: &str) -> Option<&str> {
        self.class_index.get(class).map(String::as_str)
    }

    /// Reconciles registry ↔ index ↔ loaded set. Never mutates; pair with
    /// [`Ledger::repair`] to fix what it finds.
    #[must_use]
    pub fn audit(&self, loaded: &BTreeSet<String>) -> AuditReport {
        let mut drifts = Vec::new();
        let mut index_entries: Vec<_> = self.class_index.iter().collect();
        index_entries.sort();
        for (class, plugin_id) in index_entries {
            match self.records.get(plugin_id) {
                None => drifts.push(AuditDrift::IndexTargetsUnknownPlugin {
                    class: class.clone(),
                    plugin_id: plugin_id.clone(),
                }),
                Some(record) => {
                    if !record.classes.contains(class) {
                        drifts.push(AuditDrift::IndexEntryStale {
                            class: class.clone(),
                            plugin_id: plugin_id.clone(),
                        });
                    } else if !loaded.contains(plugin_id) {
                        drifts.push(AuditDrift::IndexTargetNotLoaded {
                            class: class.clone(),
                            plugin_id: plugin_id.clone(),
                        });
                    }
                }
            }
        }
        for record in self.records.values() {
            for class in &record.classes {
                if self.class_index.get(class).map(String::as_str)
                    != Some(record.plugin_id.as_str())
                {
                    drifts.push(AuditDrift::RecordClassUnindexed {
                        plugin_id: record.plugin_id.clone(),
                        class: class.clone(),
                    });
                }
            }
        }
        AuditReport { drifts }
    }

    /// Repairs index drift reported by [`Ledger::audit`]: entries pointing
    /// at unknown or stale owners are dropped, unindexed record classes are
    /// (re)pointed at their owning record — the registry always wins.
    /// Informational not-loaded entries need no repair.
    ///
    /// # Errors
    /// Returns [`LedgerError::Store`] when persisting the repaired state
    /// fails.
    pub fn repair(&mut self) -> Result<AuditReport, LedgerError> {
        let empty = BTreeSet::new();
        let report = self.audit(&empty);
        let mut fixed = Vec::new();
        for drift in &report.drifts {
            match drift {
                AuditDrift::IndexTargetsUnknownPlugin { class, .. }
                | AuditDrift::IndexEntryStale { class, .. } => {
                    if self.class_index.remove(class).is_some() {
                        fixed.push(drift.clone());
                    }
                }
                AuditDrift::RecordClassUnindexed { plugin_id, class } => {
                    self.class_index.insert(class.clone(), plugin_id.clone());
                    fixed.push(drift.clone());
                }
                AuditDrift::IndexTargetNotLoaded { .. } => {}
            }
        }
        if !fixed.is_empty() {
            self.persist()?;
        }
        Ok(AuditReport { drifts: fixed })
    }

    fn index_record(&mut self, record: &PluginRecord) {
        for class in &record.classes {
            self.class_index
                .insert(class.clone(), record.plugin_id.clone());
        }
    }

    fn unindex_record(&mut self, record: &PluginRecord) {
        for class in &record.classes {
            if self.class_index.get(class).map(String::as_str) == Some(record.plugin_id.as_str()) {
                self.class_index.remove(class);
            }
        }
    }

    fn persist(&self) -> Result<(), LedgerError> {
        let Some(path) = &self.path else {
            return Ok(());
        };
        let file = LedgerFile {
            format_version: LEDGER_FORMAT_VERSION,
            records: self.records.values().cloned().collect(),
        };
        let bytes = serde_json::to_vec(&file).map_err(|e| LedgerError::Corrupt(e.to_string()))?;
        store::atomic_write(path, &bytes).map_err(LedgerError::from)
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
        }
    }

    fn loaded(ids: &[&str]) -> BTreeSet<String> {
        ids.iter().map(ToString::to_string).collect()
    }

    #[test]
    fn install_indexes_and_uninstall_removes_in_o_classes() {
        let mut ledger = Ledger::in_memory();
        ledger
            .commit_install(record("a", 1, &["a.Foo", "a.Bar"]))
            .unwrap();
        assert_eq!(ledger.lookup_class("a.Foo"), Some("a"));

        let removed = ledger.commit_uninstall("a").unwrap();
        assert_eq!(removed.plugin_id, "a");
        assert_eq!(ledger.lookup_class("a.Foo"), None);
        assert!(ledger.record("a").is_none());
        assert!(matches!(
            ledger.commit_uninstall("a"),
            Err(LedgerError::UnknownPlugin(_))
        ));
    }

    #[test]
    fn update_replaces_stale_index_entries_and_keeps_disabled() {
        let mut ledger = Ledger::in_memory();
        ledger.commit_install(record("a", 1, &["a.Old"])).unwrap();
        ledger.set_enabled("a", false).unwrap();

        let mut updated = record("a", 2, &["a.New"]);
        updated.installed_at_ms = 999; // must be ignored on update
        ledger.commit_install(updated).unwrap();
        assert_eq!(ledger.lookup_class("a.Old"), None);
        assert_eq!(ledger.lookup_class("a.New"), Some("a"));
        assert!(!ledger.record("a").unwrap().enabled);
        assert_eq!(ledger.record("a").unwrap().version_code, 2);
        assert_eq!(ledger.record("a").unwrap().installed_at_ms, 0); // original survives
    }

    #[test]
    fn persisted_ledger_reopens_identically() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("plugins.json");
        {
            let mut ledger = Ledger::open(&path).unwrap();
            ledger.commit_install(record("a", 1, &["a.Foo"])).unwrap();
            ledger.commit_install(record("b", 3, &[])).unwrap();
        }
        let ledger = Ledger::open(&path).unwrap();
        assert_eq!(ledger.records().len(), 2);
        assert_eq!(ledger.lookup_class("a.Foo"), Some("a"));
        assert_eq!(ledger.record("b").unwrap().version_code, 3);
    }

    #[test]
    fn corrupt_main_falls_back_to_retained_backup() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("plugins.json");
        {
            let mut ledger = Ledger::open(&path).unwrap();
            ledger.commit_install(record("a", 1, &["a.Foo"])).unwrap();
            ledger.commit_install(record("b", 3, &[])).unwrap();
        }
        // Corrupt the main file; the retained .bak holds the previous
        // generation (only "a").
        std::fs::write(&path, b"{ not json").unwrap();

        let ledger = Ledger::open(&path).unwrap();
        assert_eq!(ledger.records().len(), 1);
        assert_eq!(ledger.lookup_class("a.Foo"), Some("a"));
        // The recovered state was rewritten to main immediately.
        let reopened = Ledger::open(&path).unwrap();
        assert_eq!(reopened.records().len(), 1);
    }

    #[test]
    fn corrupt_main_without_backup_is_an_error_not_silent_loss() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("plugins.json");
        std::fs::write(&path, b"{ not json").unwrap();
        assert!(matches!(Ledger::open(&path), Err(LedgerError::Corrupt(_))));
    }

    #[test]
    fn audit_reports_three_way_drift_and_repair_fixes() {
        let mut ledger = Ledger::in_memory();
        ledger
            .commit_install(record("a", 1, &["a.Foo", "a.Gone"]))
            .unwrap();
        // Simulate drift: index entry the record no longer lists, and a
        // record class missing from the index.
        ledger
            .class_index
            .insert("ghost.Class".to_string(), "a".to_string());
        ledger
            .class_index
            .insert("orphan.Class".to_string(), "nobody".to_string());
        ledger.class_index.remove("a.Foo");

        let report = ledger.audit(&loaded(&["a"]));
        assert!(!report.is_clean());
        assert!(report.drifts.contains(&AuditDrift::IndexEntryStale {
            class: "ghost.Class".to_string(),
            plugin_id: "a".to_string(),
        }));
        assert!(report
            .drifts
            .contains(&AuditDrift::IndexTargetsUnknownPlugin {
                class: "orphan.Class".to_string(),
                plugin_id: "nobody".to_string(),
            }));
        assert!(report.drifts.contains(&AuditDrift::RecordClassUnindexed {
            plugin_id: "a".to_string(),
            class: "a.Foo".to_string(),
        }));

        let fixed = ledger.repair().unwrap();
        assert_eq!(fixed.drifts.len(), 3);
        assert_eq!(ledger.lookup_class("ghost.Class"), None);
        assert_eq!(ledger.lookup_class("orphan.Class"), None);
        assert_eq!(ledger.lookup_class("a.Foo"), Some("a"));
        assert!(ledger.audit(&loaded(&["a"])).is_clean());
    }

    #[test]
    fn audit_flags_index_pointing_at_unloaded_plugin_as_informational() {
        let mut ledger = Ledger::in_memory();
        ledger.commit_install(record("a", 1, &["a.Foo"])).unwrap();
        let report = ledger.audit(&loaded(&[]));
        assert!(report.is_clean());
        assert_eq!(
            report.drifts,
            vec![AuditDrift::IndexTargetNotLoaded {
                class: "a.Foo".to_string(),
                plugin_id: "a".to_string(),
            }]
        );
    }
}
