package com.lhzkml.jasmine.core.plugin.component

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity

/** Plugin-side activity contract; the host proxy forwards its lifecycle. */
interface PluginActivity {
    /** First step: the running proxy injects itself, before [onCreate]. */
    fun onAttach(proxy: ComponentActivity)

    fun onCreate(savedInstanceState: Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onRestart()
    fun onSaveInstanceState(outState: Bundle)
    fun onRestoreInstanceState(savedInstanceState: Bundle)
    fun onConfigurationChanged(newConfig: Configuration)
    fun onWindowFocusChanged(hasFocus: Boolean)
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean
    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean
    fun onTouchEvent(event: MotionEvent?): Boolean
}

/** No-op base implementation for plugin activities. */
open class BasePluginActivity : PluginActivity {
    protected var proxy: ComponentActivity? = null
        private set

    override fun onAttach(proxy: ComponentActivity) {
        this.proxy = proxy
    }

    override fun onCreate(savedInstanceState: Bundle?) {}
    override fun onStart() {}
    override fun onResume() {}
    override fun onPause() {}
    override fun onStop() {}
    override fun onDestroy() {}
    override fun onRestart() {}
    override fun onSaveInstanceState(outState: Bundle) {}
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {}
    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onWindowFocusChanged(hasFocus: Boolean) {}
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = false
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

/** Plugin-side service contract. */
interface PluginService {
    /** First step: the running proxy injects itself, before [onCreate]. */
    fun onAttach(proxy: Service)

    fun onCreate()
    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    fun onBind(intent: Intent?): IBinder?
    fun onUnbind(intent: Intent?): Boolean
    fun onRebind(intent: Intent?)
    fun onDestroy()
    fun onConfigurationChanged(newConfig: Configuration)
    fun onLowMemory()
    fun onTrimMemory(level: Int)
}

/** No-op base implementation for plugin services. */
open class BasePluginService : PluginService {
    protected var proxy: Service? = null
        private set

    override fun onAttach(proxy: Service) {
        this.proxy = proxy
    }

    override fun onCreate() {}
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        Service.START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onUnbind(intent: Intent?): Boolean = false
    override fun onRebind(intent: Intent?) {}
    override fun onDestroy() {}
    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onLowMemory() {}
    override fun onTrimMemory(level: Int) {}
}

/** Plugin-side static broadcast receiver contract. */
interface PluginReceiver {
    fun onReceive(context: Context, intent: Intent)
}
