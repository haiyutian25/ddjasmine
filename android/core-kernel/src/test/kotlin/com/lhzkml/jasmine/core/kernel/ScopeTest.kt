package com.lhzkml.jasmine.core.kernel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScopeTest {

    private fun kernel() = Kernel()

    @Test
    fun `events travel up the scope chain and never down`() {
        val k = kernel()
        val ancestor = ScopeKey()
        val descendant = ScopeKey()
        k.scopes.bindParent(descendant, ancestor)

        val ancestorCtx = Context(k, k.root, ancestor)
        val descendantCtx = Context(k, k.root, descendant)

        val ancestorHeard = mutableListOf<String>()
        val descendantHeard = mutableListOf<String>()
        ancestorCtx.listen(KEY) { ancestorHeard.add(it); null }
        descendantCtx.listen(KEY) { descendantHeard.add(it); null }

        descendantCtx.emit(KEY, "from-descendant")
        assertEquals(listOf("from-descendant"), ancestorHeard, "ancestor hears descendants")
        assertEquals(listOf("from-descendant"), descendantHeard, "a scope hears its own dispatch")

        ancestorCtx.emit(KEY, "from-ancestor")
        assertEquals(listOf("from-descendant", "from-ancestor"), ancestorHeard)
        assertEquals(listOf("from-descendant"), descendantHeard, "events never travel down")
    }

    @Test
    fun `untagged listeners see everything and global bypasses the filter`() {
        val k = kernel()
        val untagged = Context(k, k.root, null)
        val tagged = Context(k, k.root, ScopeKey())
        val heard = mutableListOf<String>()
        untagged.on(KEY, global = false) { heard.add("untagged"); null }
        tagged.on(KEY, global = true) { heard.add("global"); null }

        tagged.emit(KEY, "p")
        assertEquals(listOf("untagged", "global"), heard)
    }

    @Test
    fun `a tagged listener ignores unrelated scopes`() {
        val k = kernel()
        val other = ScopeKey()
        val listenerScope = ScopeKey()
        val listenerCtx = Context(k, k.root, listenerScope)
        val heard = mutableListOf<String>()
        listenerCtx.listen(KEY) { heard.add("x"); null }
        Context(k, k.root, other).emit(KEY, "p")
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `parent binding is one-time and cycle-checked`() {
        val k = kernel()
        val a = ScopeKey()
        val b = ScopeKey()
        k.scopes.bindParent(b, a)
        assertFailsWith<ScopeBindingException> { k.scopes.bindParent(b, ScopeKey()) }
        assertFailsWith<ScopeBindingException> { k.scopes.bindParent(a, b) }
        assertTrue(k.scopes.isInAncestry(a, b))
        assertFalse(k.scopes.isInAncestry(b, a))
    }

    private companion object {
        val KEY = EventKey<String, String>("scope/test")
    }
}

private fun <P, R> Context.listen(key: EventKey<P, R>, listener: suspend (P) -> R?): DisposableHandle =
    on(key) { args -> listener(args[0] as P) }
