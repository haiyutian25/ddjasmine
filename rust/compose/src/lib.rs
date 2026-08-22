//! compose: the patch composer.
//!
//! Fixed-core composition algebra (ANDROID-PLAN §3.4), a faithful port of
//! upstream's `vendor/include` patch semantics:
//!
//! - a set-patch **locates its target row by id and overrides per key**
//!   (shallow, last-write-wins); keys the patch does not mention are inherited
//!   from lower layers — this is *not* whole-row replacement;
//! - the `config` key is replaced wholesale (no deep merge);
//! - an explicit `null` **clears** a key (`disabled: null` undoes a lower
//!   layer's `disabled: true`; same for `config`/`inject`) — upstream's tri-state
//!   `absent | null | value` semantics, preserved here via [`PatchValue`];
//! - a patch whose `name` disagrees with the target row is warned about and
//!   skipped, as is a patch targeting a nonexistent id (never an error);
//! - per-item warn-and-skip granularity: one malformed entry never blocks the
//!   rest of the layer (upstream's `'patch: id is required…'` behavior);
//! - an insert is a **list of rows** appended to the root list (same YAML
//!   shape as `vendor/include`, so real `cordis.patch.yml` layers parse); a
//!   later layer may target a row an earlier layer just inserted;
//! - an inserted row without an `id` gets a deterministic generated one.
//!
//! Deliberate phase-2 gaps (each warns loudly instead of silently dropping):
//! group-scoped operations (`{id, insert}`, patches targeting rows nested in
//! a group's config), `!!js` expression values, and arbitrary extension keys
//! (`intercept`/`isolate`).

use serde::{Deserialize, Serialize};
use serde_json::Value;
use serde_yaml::{Mapping, Value as YamlValue};
use sha2::{Digest, Sha256};
use thiserror::Error;

/// Tri-state value carried by a set-patch: *absent* is represented by
/// `Option::None` on the patch field (inherit); an explicit YAML `null` is
/// [`PatchValue::Clear`]; anything else is [`PatchValue::Set`].
#[derive(Clone, Debug, PartialEq)]
pub enum PatchValue<T> {
    /// Explicit `null`: remove the key's value (row falls back to "no flag").
    Clear,
    /// Override with this value.
    Set(T),
}

impl<T: Serialize> Serialize for PatchValue<T> {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        match self {
            PatchValue::Clear => serializer.serialize_none(),
            PatchValue::Set(v) => v.serialize(serializer),
        }
    }
}

/// One composed plugin row.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
pub struct EntryRow {
    /// Patch-targeting identity. Generated when omitted on insert.
    pub id: String,
    /// Plugin name as registered in the compile-time index.
    pub name: String,
    /// Whole-replaceable plugin config.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub config: Option<Value>,
    /// Nested-group marker (group children targeting is a phase-2 feature).
    #[serde(default)]
    pub group: bool,
    /// Tri-state disable flag; absent means "inherited / enabled".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disabled: Option<bool>,
    /// Entry-level service dependencies, merged with the plugin's own.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub inject: Option<Vec<String>>,
}

/// Partial row carried by a set-patch: only listed keys override. Built by
/// [`PatchLayer::from_yaml`], which owns the tri-state (`null`) decoding.
#[derive(Clone, Debug, Default, Serialize, PartialEq)]
pub struct RowPatch {
    /// Id of the row to patch.
    pub id: String,
    /// Expected plugin name; mismatch warns and skips the whole patch.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Config override (wholesale) or explicit clear.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub config: Option<PatchValue<Value>>,
    /// Group-marker override.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<bool>,
    /// Disable-flag override or explicit clear.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disabled: Option<PatchValue<bool>>,
    /// Dependency-list override or explicit clear.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub inject: Option<PatchValue<Vec<String>>>,
}

/// One operation inside a patch layer.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(untagged)]
pub enum PatchOp {
    /// Append new rows: `- insert: [{name: ...}, ...]` (list, as in upstream).
    Insert {
        /// Rows to append; `id` optional per row (generated when absent).
        insert: Vec<InsertRow>,
    },
    /// Per-key override of the row with the same id.
    Set(RowPatch),
    /// Malformed or unsupported entry, kept so composition can warn and skip
    /// it with upstream's per-item granularity. Never produced by YAML parsing
    /// into this type directly; constructed by [`PatchLayer::from_yaml`].
    Invalid {
        /// Why the entry is invalid.
        reason: String,
    },
}

/// Row payload of an insert-patch; `id` may be omitted.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct InsertRow {
    /// Explicit id; generated from content when absent.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// Plugin name (required for the row to be loadable).
    pub name: String,
    /// Initial config.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub config: Option<Value>,
    /// Nested-group marker.
    #[serde(default)]
    pub group: bool,
    /// Initial disable flag.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disabled: Option<bool>,
    /// Initial dependency list.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub inject: Option<Vec<String>>,
}

/// One named layer of patch operations. Layers apply in order.
#[derive(Clone, Debug, Default, Serialize, PartialEq)]
pub struct PatchLayer {
    /// Diagnostics label (bundle name, profile patch, overlay path...).
    #[serde(default)]
    pub label: String,
    /// Operations of this layer, in file order.
    pub ops: Vec<PatchOp>,
}

/// Composition result: the composed rows plus non-fatal warnings.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ComposeOutput {
    /// Final row list, in insertion order.
    pub rows: Vec<EntryRow>,
    /// Human-readable skip/mismatch diagnostics, labelled by layer.
    pub warnings: Vec<String>,
}

/// Errors of parsing and composition. Composition itself never fails on
/// missing referents — those become warnings in [`ComposeOutput`].
#[derive(Debug, Error)]
pub enum ComposeError {
    /// A YAML layer could not be parsed.
    #[error("invalid patch layer YAML in {label}: {reason}")]
    InvalidYaml {
        /// Layer label for diagnostics.
        label: String,
        /// Parser message.
        reason: String,
    },
}

impl PatchLayer {
    /// Parses one YAML patch layer. The document must be a list of patch
    /// entries (upstream's contract); each entry is converted with per-item
    /// granularity: malformed or unsupported entries become
    /// [`PatchOp::Invalid`] (warned and skipped at composition) instead of
    /// failing the whole layer.
    ///
    /// # Errors
    /// Returns [`ComposeError::InvalidYaml`] only when the document is not a
    /// YAML list at all (misconfiguration fails loud at load).
    pub fn from_yaml(label: impl Into<String>, yaml: &str) -> Result<Self, ComposeError> {
        let label = label.into();
        let raw: Vec<YamlValue> =
            serde_yaml::from_str(yaml).map_err(|e| ComposeError::InvalidYaml {
                reason: format!("top level must be a list of patch entries: {e}"),
                label: label.clone(),
            })?;
        let ops = raw
            .into_iter()
            .enumerate()
            .map(|(index, item)| parse_op(index + 1, &item))
            .collect();
        Ok(Self { label, ops })
    }
}

/// Converts one raw YAML entry into a [`PatchOp`], never failing: problems
/// become [`PatchOp::Invalid`].
fn parse_op(index: usize, item: &YamlValue) -> PatchOp {
    let Some(mapping) = item.as_mapping() else {
        return invalid(index, "patch entry must be a mapping");
    };
    let has_insert = mapping.contains_key("insert");
    let has_id = mapping.contains_key("id");
    if has_insert && has_id {
        // In upstream this inserts rows into a group's config. Phase 2 here; warn
        // loudly instead of silently degrading into an empty set-patch.
        return invalid(
            index,
            "group-scoped insert (id + insert) is a phase-2 feature; entry skipped",
        );
    }
    if has_insert {
        let unknown = unknown_keys(mapping, &["insert"]);
        if !unknown.is_empty() {
            return invalid(
                index,
                &format!("unsupported keys [{}]; entry skipped", unknown.join(", ")),
            );
        }
        return match serde_yaml::from_value::<Vec<InsertRow>>(
            mapping.get("insert").expect("checked").clone(),
        ) {
            Ok(rows) => PatchOp::Insert { insert: rows },
            Err(e) => invalid(index, &format!("invalid insert: {e}; entry skipped")),
        };
    }
    if !has_id {
        // upstream's wording: 'patch: id is required for non-insert patches'.
        return invalid(
            index,
            "id is required for non-insert patches; entry skipped",
        );
    }
    parse_set_patch(index, mapping)
}

fn invalid(index: usize, reason: &str) -> PatchOp {
    PatchOp::Invalid {
        reason: format!("entry {index}: {reason}"),
    }
}

fn unknown_keys(mapping: &Mapping, known: &[&str]) -> Vec<String> {
    mapping
        .keys()
        .map(|k| {
            k.as_str()
                .map_or_else(|| "<non-string key>".to_string(), str::to_string)
        })
        .filter(|k| !known.contains(&k.as_str()))
        .collect()
}

/// Builds a [`RowPatch`] from a raw mapping, decoding the tri-state
/// (`absent | null | value`) per key. Unsupported keys skip the whole entry
/// with a warning — upstream would apply arbitrary keys (`intercept`, `isolate`);
/// dropping them silently is worse than refusing the entry.
fn parse_set_patch(index: usize, mapping: &Mapping) -> PatchOp {
    const KNOWN: &[&str] = &["id", "name", "config", "group", "disabled", "inject"];
    let unknown = unknown_keys(mapping, KNOWN);
    if !unknown.is_empty() {
        return invalid(
            index,
            &format!(
                "unsupported keys [{}] (upstream applies arbitrary keys; unsupported here); patch skipped",
                unknown.join(", ")
            ),
        );
    }
    let mut patch = RowPatch::default();
    for (key, value) in mapping {
        let key = key.as_str().expect("string keys checked");
        match key {
            "id" => match value.as_str() {
                Some(id) if !id.is_empty() => patch.id = id.to_string(),
                _ => return invalid(index, "id must be a non-empty string; patch skipped"),
            },
            "name" => {
                if value.is_null() {
                    // absence-equivalent, ignore
                } else if let Some(name) = value.as_str() {
                    patch.name = Some(name.to_string());
                } else {
                    return invalid(index, "name must be a string; patch skipped");
                }
            }
            "config" => {
                patch.config = Some(if value.is_null() {
                    PatchValue::Clear
                } else {
                    PatchValue::Set(yaml_to_json(value))
                });
            }
            "group" => match value.as_bool() {
                Some(b) => patch.group = Some(b),
                None => return invalid(index, "group must be a boolean; patch skipped"),
            },
            "disabled" => {
                patch.disabled = Some(if value.is_null() {
                    PatchValue::Clear
                } else if let Some(b) = value.as_bool() {
                    PatchValue::Set(b)
                } else {
                    return invalid(index, "disabled must be a boolean or null; patch skipped");
                });
            }
            "inject" => {
                patch.inject = Some(if value.is_null() {
                    PatchValue::Clear
                } else if let Some(seq) = value.as_sequence() {
                    let mut list = Vec::with_capacity(seq.len());
                    let mut well_typed = true;
                    for item in seq {
                        if let Some(s) = item.as_str() {
                            list.push(s.to_string());
                        } else {
                            well_typed = false;
                            break;
                        }
                    }
                    if !well_typed {
                        return invalid(
                            index,
                            "inject must be a list of strings or null; patch skipped",
                        );
                    }
                    PatchValue::Set(list)
                } else {
                    return invalid(
                        index,
                        "inject must be a list of strings or null; patch skipped",
                    );
                });
            }
            _ => unreachable!("unknown keys filtered"),
        }
    }
    PatchOp::Set(patch)
}

fn yaml_to_json(value: &YamlValue) -> Value {
    serde_json::to_value(value).unwrap_or(Value::Null)
}

/// Composes ordered layers onto the empty root list.
///
/// Semantics (per key, last-write-wins): a set-patch overrides only the keys
/// it lists; explicit `null` clears a key; `config` is replaced wholesale;
/// missing ids and name mismatches produce warnings and are skipped; inserts
/// append and are targetable by later layers.
#[must_use]
pub fn compose_rows(layers: &[PatchLayer]) -> ComposeOutput {
    let mut rows: Vec<EntryRow> = Vec::new();
    let mut warnings: Vec<String> = Vec::new();
    let mut used_ids: std::collections::HashSet<String> = std::collections::HashSet::new();
    for layer in layers {
        for op in &layer.ops {
            match op {
                PatchOp::Insert { insert } => {
                    for row in insert {
                        let id = row.id.clone().unwrap_or_else(|| {
                            // Identical id-less rows would otherwise share one
                            // generated id and become indistinguishable patch targets.
                            let base = generate_id(row);
                            if !used_ids.contains(&base) {
                                return base;
                            }
                            let mut suffix = 2;
                            while used_ids.contains(&format!("{base}-{suffix}")) {
                                suffix += 1;
                            }
                            format!("{base}-{suffix}")
                        });
                        used_ids.insert(id.clone());
                        rows.push(EntryRow {
                            id,
                            name: row.name.clone(),
                            config: row.config.clone(),
                            group: row.group,
                            disabled: row.disabled,
                            inject: row.inject.clone(),
                        });
                    }
                }
                PatchOp::Invalid { reason } => {
                    warnings.push(format!("{}: {reason}", layer.label));
                }
                PatchOp::Set(patch) => {
                    let Some(target) = rows.iter_mut().find(|row| row.id == patch.id) else {
                        warnings.push(format!(
                            "{}: patch target '{}' not found; skipped",
                            layer.label, patch.id
                        ));
                        continue;
                    };
                    if let Some(name) = &patch.name {
                        if name != &target.name {
                            warnings.push(format!(
                                "{}: patch for '{}' names '{name}' but row is '{}'; skipped",
                                layer.label, patch.id, target.name
                            ));
                            continue;
                        }
                    }
                    if let Some(config) = &patch.config {
                        match config {
                            PatchValue::Clear => target.config = None,
                            PatchValue::Set(v) => target.config = Some(v.clone()),
                        }
                    }
                    if let Some(group) = patch.group {
                        target.group = group;
                    }
                    if let Some(disabled) = &patch.disabled {
                        match disabled {
                            PatchValue::Clear => target.disabled = None,
                            PatchValue::Set(b) => target.disabled = Some(*b),
                        }
                    }
                    if let Some(inject) = &patch.inject {
                        match inject {
                            PatchValue::Clear => target.inject = None,
                            PatchValue::Set(list) => target.inject = Some(list.clone()),
                        }
                    }
                }
            }
        }
    }
    ComposeOutput { rows, warnings }
}

/// Deterministic id for id-less inserts: content-derived, stable across runs.
fn generate_id(insert: &InsertRow) -> String {
    let canonical = serde_json::to_vec(insert).expect("insert serialization is infallible");
    let digest = Sha256::digest(&canonical);
    let mut id = String::from("gen-");
    for byte in &digest[..4] {
        id.push_str(&format!("{byte:02x}"));
    }
    id
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Same shape as the real `packages/bundle/base/cordis.patch.yml`:
    /// `- insert:` followed by a *list* of rows.
    fn base_layer() -> PatchLayer {
        PatchLayer::from_yaml(
            "bundle",
            r#"
- insert:
  - id: llm
    name: llm
  - id: tool-bash
    name: tool-bash
    config: {timeout_ms: 30000}
    disabled: false
    inject: [subprocess, shell]
"#,
        )
        .unwrap()
    }

    #[test]
    fn real_bundle_patch_shape_parses() {
        let out = compose_rows(&[base_layer()]);
        assert!(out.warnings.is_empty());
        assert_eq!(out.rows.len(), 2);
        assert_eq!(out.rows[0].id, "llm");
    }

    #[test]
    fn set_patch_overrides_config_wholesale_and_inherits_unmentioned_keys() {
        let patch = PatchLayer::from_yaml(
            "profile",
            r#"
- id: tool-bash
  config: {timeout_ms: 5000}
"#,
        )
        .unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        assert!(out.warnings.is_empty());
        let row = out.rows.iter().find(|r| r.id == "tool-bash").unwrap();
        // config replaced wholesale, not deep-merged
        assert_eq!(row.config, Some(json!({"timeout_ms": 5000})));
        // keys the patch did not mention are inherited from the lower layer
        assert_eq!(row.disabled, Some(false));
        assert_eq!(
            row.inject,
            Some(vec!["subprocess".to_string(), "shell".to_string()])
        );
    }

    #[test]
    fn explicit_null_clears_a_key_set_by_a_lower_layer() {
        let base = PatchLayer::from_yaml(
            "bundle",
            "- insert:\n  - id: x\n    name: x\n    config: {a: 1}\n    disabled: true\n    inject: [llm]\n",
        )
        .unwrap();
        let patch = PatchLayer::from_yaml("profile", "- id: x\n  disabled: null\n  config: null\n")
            .unwrap();
        let out = compose_rows(&[base, patch]);
        assert!(out.warnings.is_empty());
        let row = &out.rows[0];
        // `disabled: null` undoes the lower layer's disable (upstream: Boolean(null) = enabled)
        assert_eq!(row.disabled, None);
        // `config: null` clears the config
        assert_eq!(row.config, None);
        // unmentioned keys still inherit
        assert_eq!(row.inject, Some(vec!["llm".to_string()]));
    }

    #[test]
    fn group_key_is_patchable() {
        let patch = PatchLayer::from_yaml("profile", "- id: llm\n  group: true\n").unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        assert!(out.warnings.is_empty());
        assert!(out.rows.iter().find(|r| r.id == "llm").unwrap().group);
    }

    #[test]
    fn missing_target_warns_and_skips_without_failing() {
        let patch = PatchLayer::from_yaml("profile", "- id: nope\n  config: {}\n").unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        assert_eq!(out.rows.len(), 2);
        assert_eq!(out.warnings.len(), 1);
        assert!(out.warnings[0].contains("nope"));
    }

    #[test]
    fn name_mismatch_warns_and_skips_the_whole_patch() {
        let patch = PatchLayer::from_yaml(
            "profile",
            "- id: tool-bash\n  name: tool-pwsh\n  disabled: true\n",
        )
        .unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        assert_eq!(out.warnings.len(), 1);
        let row = out.rows.iter().find(|r| r.id == "tool-bash").unwrap();
        // skipped entirely: disabled was NOT applied
        assert_eq!(row.disabled, Some(false));
    }

    #[test]
    fn later_layer_targets_a_row_inserted_by_an_earlier_layer() {
        let patch = PatchLayer::from_yaml(
            "home",
            "- id: tool-bash\n  disabled: true\n- id: llm\n  inject: []\n",
        )
        .unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        assert!(out.warnings.is_empty());
        let bash = out.rows.iter().find(|r| r.id == "tool-bash").unwrap();
        assert_eq!(bash.disabled, Some(true));
        let llm = out.rows.iter().find(|r| r.id == "llm").unwrap();
        assert_eq!(llm.inject, Some(vec![]));
    }

    #[test]
    fn insert_without_id_gets_a_deterministic_generated_id() {
        let layer = PatchLayer::from_yaml("bundle", "- insert:\n  - name: x\n").unwrap();
        let a = compose_rows(std::slice::from_ref(&layer));
        let b = compose_rows(&[layer]);
        assert!(a.rows[0].id.starts_with("gen-"));
        assert_eq!(a.rows[0].id, b.rows[0].id);
    }

    #[test]
    fn layering_is_order_sensitive_not_idempotent_across_swaps() {
        let a = PatchLayer::from_yaml("a", "- id: tool-bash\n  config: {v: 1}\n").unwrap();
        let b = PatchLayer::from_yaml("b", "- id: tool-bash\n  config: {v: 2}\n").unwrap();
        let base = base_layer();
        let ab = compose_rows(&[base_layer(), a.clone(), b.clone()]);
        let ba = compose_rows(&[base, b, a]);
        let get = |out: &ComposeOutput| {
            out.rows
                .iter()
                .find(|r| r.id == "tool-bash")
                .unwrap()
                .config
                .clone()
        };
        assert_eq!(get(&ab), Some(json!({"v": 2})));
        assert_eq!(get(&ba), Some(json!({"v": 1})));
    }

    #[test]
    fn missing_id_patch_warns_per_item_and_the_layer_continues() {
        let patch = PatchLayer::from_yaml(
            "profile",
            "- disabled: true\n- id: tool-bash\n  disabled: true\n",
        )
        .unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        // the id-less entry warns; the valid entry still applies
        assert_eq!(out.warnings.len(), 1);
        assert!(out.warnings[0].contains("id is required"));
        assert_eq!(
            out.rows
                .iter()
                .find(|r| r.id == "tool-bash")
                .unwrap()
                .disabled,
            Some(true)
        );
    }

    #[test]
    fn group_scoped_insert_warns_instead_of_silently_degrading() {
        let layer =
            PatchLayer::from_yaml("profile", "- id: llm\n  insert:\n  - name: nested\n").unwrap();
        let out = compose_rows(&[base_layer(), layer]);
        assert_eq!(out.rows.len(), 2); // nothing added
        assert_eq!(out.warnings.len(), 1);
        assert!(out.warnings[0].contains("group-scoped insert"));
    }

    #[test]
    fn unsupported_extension_keys_warn_and_skip_the_entry() {
        let patch =
            PatchLayer::from_yaml("profile", "- id: tool-bash\n  intercept: {config: {}}\n")
                .unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        assert_eq!(out.warnings.len(), 1);
        assert!(out.warnings[0].contains("intercept"));
        // not silently applied: the row is untouched
        assert_eq!(
            out.rows
                .iter()
                .find(|r| r.id == "tool-bash")
                .unwrap()
                .config,
            Some(json!({"timeout_ms": 30000}))
        );
    }

    #[test]
    fn non_string_keys_warn_and_skip_instead_of_panicking() {
        let patch = PatchLayer::from_yaml("profile", "- id: tool-bash\n  1: y\n").unwrap();
        let out = compose_rows(&[base_layer(), patch]);
        assert_eq!(out.warnings.len(), 1);
        assert!(out.warnings[0].contains("non-string key"));
        // entry skipped: the row keeps its original config
        assert_eq!(
            out.rows
                .iter()
                .find(|r| r.id == "tool-bash")
                .unwrap()
                .config,
            Some(json!({"timeout_ms": 30000}))
        );
    }

    #[test]
    fn duplicate_id_less_inserts_get_distinct_generated_ids() {
        let layer = PatchLayer::from_yaml(
            "bundle",
            "- insert:\n  - name: x\n    config: {a: 1}\n  - name: x\n    config: {a: 1}\n",
        )
        .unwrap();
        let out = compose_rows(&[layer]);
        assert_eq!(out.rows.len(), 2);
        assert_ne!(out.rows[0].id, out.rows[1].id);
    }

    #[test]
    fn invalid_yaml_fails_loud_at_load() {
        let err = PatchLayer::from_yaml("broken", "- id: [unclosed").unwrap_err();
        assert!(matches!(err, ComposeError::InvalidYaml { .. }));
    }
}
