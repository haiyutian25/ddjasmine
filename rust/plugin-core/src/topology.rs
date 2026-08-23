//! topology: the dependency graph.
//!
//! Edges are recorded on first class borrow (zero-config dependency: the
//! first time plugin A loads a class from plugin B, the edge A→B exists),
//! and they drive the deterministic chained-restart plan. Two deliberate
//! upgrades over the reference design: ordered sets (`BTreeMap`/`BTreeSet`)
//! so the plan is reproducible run over run, and an exact-match instance
//! registry for pooled services so a prefix collision can never misattribute
//! a running instance.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

/// A chained-restart plan: unload in reverse dependency order, reload in
/// dependency order, so old and new code never mix.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RestartPlan {
    /// The plugin whose update triggered the plan.
    pub plugin_id: String,
    /// Affected plugins in dependency order (root first, dependents last) —
    /// the reload order.
    pub reload_order: Vec<String>,
    /// The exact reverse — the unload order.
    pub unload_order: Vec<String>,
}

/// Bidirectional dependency graph plus the pooled-service instance
/// registry. `BTree` everywhere: iteration order is the plan order.
#[derive(Default)]
pub struct Topology {
    /// borrower → set of plugins it has borrowed classes from.
    borrows: BTreeMap<String, BTreeSet<String>>,
    /// lender → set of plugins that borrow from it.
    dependents: BTreeMap<String, BTreeSet<String>>,
    /// Exact service-instance ids (`"className:taskN"`) → owning plugin.
    instances: BTreeMap<String, String>,
}

/// A recorded borrow edge.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BorrowEdge {
    /// The plugin performing the class load.
    pub borrower: String,
    /// The plugin providing the class.
    pub lender: String,
}

impl Topology {
    /// An empty graph.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Records a class borrow. Self-borrows and duplicates are no-ops;
    /// returns true when a new edge was created.
    pub fn record_borrow(&mut self, borrower: &str, lender: &str) -> bool {
        if borrower == lender {
            return false;
        }
        let is_new = self
            .borrows
            .entry(borrower.to_string())
            .or_default()
            .insert(lender.to_string());
        if is_new {
            self.dependents
                .entry(lender.to_string())
                .or_default()
                .insert(borrower.to_string());
        }
        is_new
    }

    /// Drops every edge and instance registration touching `plugin_id`.
    pub fn remove_plugin(&mut self, plugin_id: &str) {
        if let Some(lenders) = self.borrows.remove(plugin_id) {
            for lender in lenders {
                if let Some(set) = self.dependents.get_mut(&lender) {
                    set.remove(plugin_id);
                    if set.is_empty() {
                        self.dependents.remove(&lender);
                    }
                }
            }
        }
        if let Some(borrowers) = self.dependents.remove(plugin_id) {
            for borrower in borrowers {
                if let Some(set) = self.borrows.get_mut(&borrower) {
                    set.remove(plugin_id);
                    if set.is_empty() {
                        self.borrows.remove(&borrower);
                    }
                }
            }
        }
        self.instances.retain(|_, owner| owner != plugin_id);
    }

    /// All plugins affected by `plugin_id` changing: itself plus the
    /// transitive dependents closure, in deterministic DFS preorder
    /// (root first). Neighbors are visited in sorted order, so the result
    /// is reproducible.
    #[must_use]
    pub fn affected_closure(&self, plugin_id: &str) -> Vec<String> {
        let mut order = Vec::new();
        let mut visited = BTreeSet::new();
        let mut stack = vec![plugin_id.to_string()];
        while let Some(id) = stack.pop() {
            if !visited.insert(id.clone()) {
                continue;
            }
            order.push(id.clone());
            if let Some(set) = self.dependents.get(&id) {
                // Push reversed so the smallest id pops first.
                stack.extend(set.iter().rev().cloned());
            }
        }
        order
    }

    /// Everything `plugin_id` depends on, transitively: itself plus the
    /// closure over its borrow edges, in deterministic DFS preorder.
    #[must_use]
    pub fn dependencies_closure(&self, plugin_id: &str) -> Vec<String> {
        let mut order = Vec::new();
        let mut visited = BTreeSet::new();
        let mut stack = vec![plugin_id.to_string()];
        while let Some(id) = stack.pop() {
            if !visited.insert(id.clone()) {
                continue;
            }
            order.push(id.clone());
            if let Some(set) = self.borrows.get(&id) {
                stack.extend(set.iter().rev().cloned());
            }
        }
        order
    }

    /// The chained-restart plan for updating `plugin_id`: dependents unload
    /// before their lender, reload after it.
    #[must_use]
    pub fn restart_plan(&self, plugin_id: &str) -> RestartPlan {
        let reload_order = self.affected_closure(plugin_id);
        let mut unload_order = reload_order.clone();
        unload_order.reverse();
        RestartPlan {
            plugin_id: plugin_id.to_string(),
            reload_order,
            unload_order,
        }
    }

    /// Current edges, in deterministic order (diagnostics and audits).
    #[must_use]
    pub fn edges(&self) -> Vec<BorrowEdge> {
        let mut edges = Vec::new();
        for (borrower, lenders) in &self.borrows {
            for lender in lenders {
                edges.push(BorrowEdge {
                    borrower: borrower.clone(),
                    lender: lender.clone(),
                });
            }
        }
        edges.sort_by(|a, b| (&a.borrower, &a.lender).cmp(&(&b.borrower, &b.lender)));
        edges
    }

    /// Registers a pooled-service instance under its exact id
    /// (`"className:taskN"`).
    pub fn register_instance(&mut self, instance_id: &str, plugin_id: &str) {
        self.instances
            .insert(instance_id.to_string(), plugin_id.to_string());
    }

    /// Looks up the owner of a pooled-service instance by **exact** id.
    /// The reference design prefix-matches here, so class `Foo` would
    /// misattribute `FooBar:task3`; exact match is the fix.
    #[must_use]
    pub fn instance_owner(&self, instance_id: &str) -> Option<&str> {
        self.instances.get(instance_id).map(String::as_str)
    }

    /// Exact-match running instances of one service class, in id order.
    /// Matches `"className:"` *with* the colon — never a bare prefix.
    #[must_use]
    pub fn instances_of(&self, class_name: &str) -> Vec<&str> {
        let prefix = format!("{class_name}:");
        self.instances
            .keys()
            .filter(|id| id.starts_with(&prefix))
            .map(String::as_str)
            .collect()
    }

    /// Drops one instance registration.
    pub fn unregister_instance(&mut self, instance_id: &str) {
        self.instances.remove(instance_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn borrow_records_both_directions_once() {
        let mut t = Topology::new();
        assert!(t.record_borrow("a", "b"));
        assert!(!t.record_borrow("a", "b"));
        assert!(!t.record_borrow("a", "a"));
        assert_eq!(
            t.edges(),
            vec![BorrowEdge {
                borrower: "a".into(),
                lender: "b".into()
            }]
        );
    }

    #[test]
    fn restart_plan_is_deterministic_and_dependency_ordered() {
        let mut t = Topology::new();
        // c borrows from b; b and d borrow from a; e borrows from d.
        t.record_borrow("c", "b");
        t.record_borrow("b", "a");
        t.record_borrow("d", "a");
        t.record_borrow("e", "d");

        let plan = t.restart_plan("a");
        assert_eq!(plan.reload_order, vec!["a", "b", "c", "d", "e"]);
        assert_eq!(plan.unload_order, vec!["e", "d", "c", "b", "a"]);

        // Same graph rebuilt in a different insertion order → same plan.
        let mut t2 = Topology::new();
        t2.record_borrow("e", "d");
        t2.record_borrow("d", "a");
        t2.record_borrow("b", "a");
        t2.record_borrow("c", "b");
        assert_eq!(t2.restart_plan("a"), plan);
    }

    #[test]
    fn cycles_do_not_revisit() {
        let mut t = Topology::new();
        t.record_borrow("a", "b");
        t.record_borrow("b", "a");
        let plan = t.restart_plan("a");
        assert_eq!(plan.reload_order, vec!["a", "b"]);
    }

    #[test]
    fn remove_plugin_drops_edges_and_instances() {
        let mut t = Topology::new();
        t.record_borrow("a", "b");
        t.record_borrow("c", "b");
        t.register_instance("b.Service:task1", "b");
        t.remove_plugin("b");
        assert!(t.edges().is_empty());
        assert_eq!(t.restart_plan("a").reload_order, vec!["a"]);
        assert_eq!(t.instance_owner("b.Service:task1"), None);
    }

    #[test]
    fn instance_lookup_is_exact_never_bare_prefix() {
        let mut t = Topology::new();
        t.register_instance("Foo:task1", "p1");
        t.register_instance("FooBar:task3", "p2");
        assert_eq!(t.instance_owner("Foo:task1"), Some("p1"));
        assert_eq!(t.instance_owner("Foo"), None);
        assert_eq!(t.instance_owner("FooBar:task3"), Some("p2"));
        assert_eq!(t.instances_of("Foo"), vec!["Foo:task1"]);
        assert!(t.instances_of("FooB").is_empty());
    }
}
