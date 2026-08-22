package com.lhzkml.jasmine.core.kernel

/**
 * Opaque scope identity (upstream `ScopeKey`): object identity is the whole
 * contract. One relation serves both directions — the registration view
 * inherits DOWN the chain (a child scope sees ancestor layers) and event
 * admission travels UP (an ancestor's listeners receive descendant
 * dispatches; events never travel down).
 */
class ScopeKey {
    override fun toString(): String = "Scope@${Integer.toHexString(System.identityHashCode(this))}"
}

/** Thrown on an attempted scope-parent rebinding or a cyclic binding. */
class ScopeBindingException(message: String) : IllegalStateException(message)

/**
 * The scope parent chain (upstream `packages/core/scope`): bindings are
 * one-time, cycle-checked, and rebindable only through the handle the
 * original binder received.
 */
class ScopeRegistry {

    internal val parents = LinkedHashMap<ScopeKey, ScopeKey>()

    /** One-time parent binding; returns the rebind handle. */
    fun bindParent(key: ScopeKey, parent: ScopeKey): ScopeParentBinding {
        if (key === parent) throw ScopeBindingException("a scope cannot be its own parent")
        if (parents.containsKey(key)) {
            throw ScopeBindingException("scope $key already has a parent binding")
        }
        if (isInAncestry(key, parent)) {
            throw ScopeBindingException("binding would create a cycle via $parent")
        }
        parents[key] = parent
        return ScopeParentBinding(this, key)
    }

    /** Whether [ancestor] is [key] itself or anywhere on its parent chain. */
    fun isInAncestry(ancestor: ScopeKey, key: ScopeKey?): Boolean {
        var current = key
        while (current != null) {
            if (current === ancestor) return true
            current = parents[current]
        }
        return false
    }

    /** Depth of [key] on its chain (root scopes are 0). */
    fun depth(key: ScopeKey): Int {
        var depth = 0
        var current = parents[key]
        while (current != null) {
            depth++
            current = parents[current]
        }
        return depth
    }
}

/** The only authority allowed to rewire a scope's parent binding. */
class ScopeParentBinding internal constructor(
    private val registry: ScopeRegistry,
    private val key: ScopeKey,
) {
    /** Moves [key] under [newParent]; same one-time/cycle rules apply. */
    fun rebind(newParent: ScopeKey) {
        if (registry.parents[key] === newParent) return
        registry.parents.remove(key)
        try {
            registry.bindParent(key, newParent)
        } catch (t: Throwable) {
            // Best effort: the old binding is gone; keep the registry consistent.
            throw t
        }
    }
}
