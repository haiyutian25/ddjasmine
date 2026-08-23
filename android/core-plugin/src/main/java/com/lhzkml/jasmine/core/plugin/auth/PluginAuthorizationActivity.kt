package com.lhzkml.jasmine.core.plugin.auth

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.lhzkml.jasmine.core.plugin.AuthorizationHandler
import com.lhzkml.jasmine.core.plugin.AuthorizationPrompt
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation

/**
 * Default authorization UI: a dialog-themed activity (declared in this
 * library's manifest, merged into the host) that asks the user and
 * broadcasts the answer back. Hosts wanting their own UX implement
 * [AuthorizationHandler] instead and never touch this.
 */
class PluginAuthorizationActivity : Activity() {

    companion object {
        const val ACTION_AUTHORIZATION_RESULT =
            "com.lhzkml.jasmine.core.plugin.AUTHORIZATION_RESULT"
        const val EXTRA_REQUEST_CODE = "request_code"
        const val EXTRA_KIND = "kind"
        const val EXTRA_PLUGIN_ID = "plugin_id"
        const val EXTRA_REASON = "reason"
        const val EXTRA_RESULT_GRANTED = "result_granted"
    }

    private var requestCode = -1
    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val title = if (kind == "install") "插件安装授权" else "插件 API 访问授权"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("插件 [$pluginId]\n\n$reason")
            .setPositiveButton("允许") { _, _ -> finishWithResult(true) }
            .setNegativeButton("拒绝") { _, _ -> finishWithResult(false) }
            .setOnCancelListener { finishWithResult(false) }
            .show()
    }

    private fun finishWithResult(granted: Boolean) {
        if (resultSent) return
        resultSent = true
        sendBroadcast(
            Intent(ACTION_AUTHORIZATION_RESULT).apply {
                setPackage(packageName)
                putExtra(EXTRA_REQUEST_CODE, requestCode)
                putExtra(EXTRA_RESULT_GRANTED, granted)
            },
        )
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing && !resultSent) finishWithResult(false)
    }
}

/**
 * Wires [PluginHost] Ask verdicts to [PluginAuthorizationActivity]:
 * suspends the caller, shows the dialog, resumes with the answer.
 */
class DefaultPluginAuthorizationHandler(
    private val application: Application,
) : AuthorizationHandler {

    private val nextCode = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Continuation<Boolean>>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PluginAuthorizationActivity.ACTION_AUTHORIZATION_RESULT) return
            val code = intent.getIntExtra(PluginAuthorizationActivity.EXTRA_REQUEST_CODE, -1)
            val granted = intent.getBooleanExtra(
                PluginAuthorizationActivity.EXTRA_RESULT_GRANTED, false,
            )
            pending.remove(code)?.resumeWith(Result.success(granted))
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            receiver,
            IntentFilter(PluginAuthorizationActivity.ACTION_AUTHORIZATION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override suspend fun onAuthorization(prompt: AuthorizationPrompt): Boolean =
        suspendCancellableCoroutine { continuation ->
            val code = nextCode.getAndIncrement()
            pending[code] = continuation
            continuation.invokeOnCancellation { pending.remove(code) }
            application.startActivity(
                Intent(application, PluginAuthorizationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(PluginAuthorizationActivity.EXTRA_REQUEST_CODE, code)
                    putExtra(
                        PluginAuthorizationActivity.EXTRA_KIND,
                        if (prompt.kind == AuthorizationPrompt.Kind.Install) "install" else "api",
                    )
                    putExtra(PluginAuthorizationActivity.EXTRA_PLUGIN_ID, prompt.pluginId)
                    putExtra(PluginAuthorizationActivity.EXTRA_REASON, prompt.reason)
                },
            )
        }
}
