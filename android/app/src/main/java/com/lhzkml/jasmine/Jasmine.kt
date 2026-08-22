package com.lhzkml.jasmine

import android.app.Application
import android.util.Log
import com.lhzkml.jasmine.rust.FfiSessionHeader
import com.lhzkml.jasmine.rust.SessionLogHandle
import dagger.hilt.android.HiltAndroidApp
import java.util.UUID

@HiltAndroidApp
class Jasmine : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
        installCrashRecorder()
        rustSmokeTest()
    }

    /**
     * Persists the last uncaught JVM crash under files/crash/last-crash.txt.
     * Native tombstones still require logcat, but Java/Kotlin crashes become
     * inspectable after relaunch even when no development machine is attached.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { AppLog.crash(thread, failure) }
            previous?.uncaughtException(thread, failure)
        }
    }

    /**
     * Proves the Rust data spine is loadable and round-trips on this device:
     * create an in-memory session log, append one user message, project the
     * history back. Failures are logged loudly but never crash startup —
     * the demo UI must stay reachable even when the spine is missing (for
     * example an ABI not packaged in a test build).
     */
    private fun rustSmokeTest() {
        try {
            System.loadLibrary("ffi")
            val log = SessionLogHandle.inMemory(
                FfiSessionHeader(
                    formatVersion = 0u,
                    sessionId = UUID.randomUUID().toString(),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            log.append("user/message", """{"content":"hello from Kotlin"}""")
            val messages = log.deriveMessages(ULong.MAX_VALUE)
            check(messages.size == 1 && messages.first().content == "hello from Kotlin") {
                "unexpected projection: $messages"
            }
            Log.i(TAG, "rust spine ok: ${log.eventCount()} events, projection round-trips")
        } catch (t: Throwable) {
            Log.e(TAG, "rust spine unavailable", t)
        }
    }

    private companion object {
        const val TAG = "Jasmine"
    }
}
