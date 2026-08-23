package com.lhzkml.jasmine.core.plugin.proxy

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import com.lhzkml.jasmine.core.plugin.PluginHost
import com.lhzkml.jasmine.core.plugin.component.PluginActivity
import com.lhzkml.jasmine.core.plugin.rust.FfiLocateOutcome

internal object ProxyKeys {
    const val ACTIVITY_CLASS = "jasmine.plugin.runtime.ACTIVITY_CLASS"
    const val SERVICE_CLASS = "jasmine.plugin.runtime.SERVICE_CLASS"
    const val SERVICE_INSTANCE_ID = "jasmine.plugin.runtime.SERVICE_INSTANCE_ID"
}

/** Starts a plugin activity through the host's single proxy activity. */
fun Context.startPluginActivity(proxyClass: Class<out HostActivity>, pluginActivityClass: String) {
    startActivity(
        Intent(this, proxyClass).apply {
            putExtra(ProxyKeys.ACTIVITY_CLASS, pluginActivityClass)
        },
    )
}

/**
 * Single-proxy activity: one registered host activity fronts every plugin
 * activity and forwards the full lifecycle. Instantiation failure is
 * surfaced (logged + finished) rather than leaving a blank screen alive.
 */
open class HostActivity : ComponentActivity() {

    protected var pluginActivity: PluginActivity? = null
        private set

    /** The plugin's own resources while hosting an activity; null for host UI. */
    private var pluginResources: Resources? = null

    /**
     * When hosting a plugin activity, resolve resources against the plugin's
     * package-id-partitioned table (host keeps 0x7f, plugins take 0x80+N).
     * This makes `setContentView(R.layout.…)` and `findViewById(R.id.…)`
     * inside plugin activities work with their own ids.
     */
    override fun getResources(): Resources = pluginResources ?: super.getResources()

    private fun initPluginActivity() {
        val className = intent?.getStringExtra(ProxyKeys.ACTIVITY_CLASS) ?: return
        try {
            val pluginId = when (
                val outcome = PluginHost.coreHandle.locateClass(className, null)
            ) {
                is FfiLocateOutcome.Plugin -> outcome.pluginId
                FfiLocateOutcome.HostFallback ->
                    throw IllegalStateException("没有已加载插件能提供 Activity: $className")
            }
            val instance = PluginHost.instantiateComponent(pluginId, className)
            pluginActivity = (instance as? PluginActivity)
                ?: throw IllegalStateException("$className 未实现 PluginActivity")
            pluginResources = PluginHost.resourcesOf(pluginId)
            pluginActivity?.attach(this)
        } catch (e: Throwable) {
            pluginActivity = null
            pluginResources = null
            PluginHost.loadFailureCallback?.onFailure(className, "activity", e)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initPluginActivity()
        pluginActivity?.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        pluginActivity?.onStart()
    }

    override fun onResume() {
        super.onResume()
        pluginActivity?.onResume()
    }

    override fun onPause() {
        super.onPause()
        pluginActivity?.onPause()
    }

    override fun onStop() {
        super.onStop()
        pluginActivity?.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        pluginActivity?.onDestroy()
    }

    override fun onRestart() {
        super.onRestart()
        pluginActivity?.onRestart()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pluginActivity?.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        pluginActivity?.onRestoreInstanceState(savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pluginActivity?.onConfigurationChanged(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        pluginActivity?.onWindowFocusChanged(hasFocus)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        pluginActivity?.onKeyDown(keyCode, event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        pluginActivity?.onKeyUp(keyCode, event) == true || super.onKeyUp(keyCode, event)

    override fun onTouchEvent(event: MotionEvent?): Boolean =
        pluginActivity?.onTouchEvent(event) == true || super.onTouchEvent(event)
}
