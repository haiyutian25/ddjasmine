package com.lhzkml.jasmine.core.data

import com.lhzkml.jasmine.core.kernel.PluginSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Runtime state of one plugin id. */
enum class PluginRuntimeState {
    /** The row has no Kotlin implementation behind it. */
    NO_CODE,

    /** The row is enabled but not (yet) mounted. */
    UNMOUNTED,

    /** A fiber is live in the kernel. */
    MOUNTED,

    /** The plugin's startup threw; the failure sits in its fiber. */
    FAILED,
}

/**
 * The bridge between the composed profile and the plugin kernel: an enabled
 * row with an implementation mounts a plugin fiber, a disabled row unmounts
 * it. This is where "配置管理" becomes a real plugin runtime.
 */
@Singleton
class PluginRuntime @Inject constructor(
    private val kernelHolder: KernelHolder,
    private val index: Map<String, PluginSpec>,
) {

    private val handles = LinkedHashMap<String, DisposableHandleBridge>()

    private val _states = MutableStateFlow<Map<String, PluginRuntimeState>>(emptyMap())
    val states: StateFlow<Map<String, PluginRuntimeState>> = _states.asStateFlow()

    /** Reconciles the kernel with the composed rows; idempotent per id. */
    fun sync(rows: List<PluginRow>) {
        val next = LinkedHashMap<String, PluginRuntimeState>()
        rows.forEach { row ->
            val hasCode = index.containsKey(row.id)
            val mounted = handles.containsKey(row.id)
            when {
                !hasCode -> next[row.id] = PluginRuntimeState.NO_CODE
                row.disabled -> {
                    handles.remove(row.id)?.dispose()
                    next[row.id] = PluginRuntimeState.UNMOUNTED
                }
                !mounted -> {
                    val id = row.id
                    handles[id] = DisposableHandleBridge(
                        kernelHolder.kernel.registry.mount(index.getValue(id)) { fiberState ->
                            _states.value = _states.value + (id to
                                if (fiberState == com.lhzkml.jasmine.core.kernel.FiberState.FAILED) PluginRuntimeState.FAILED
                                else PluginRuntimeState.MOUNTED)
                        }
                    )
                    next[id] = PluginRuntimeState.MOUNTED
                }
                else -> next[row.id] = PluginRuntimeState.MOUNTED
            }
        }
        handles.keys.filterNot { it in next.keys }.forEach { handles.remove(it)?.dispose() }
        _states.value = next
    }
}

/** Thin wrapper so handle disposal reads like the rest of the bridge. */
private class DisposableHandleBridge(
    private val handle: com.lhzkml.jasmine.core.kernel.FiberRegistryHandle,
) {
    fun dispose() = handle.dispose()
}
