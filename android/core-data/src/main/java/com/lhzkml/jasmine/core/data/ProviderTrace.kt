package com.lhzkml.jasmine.core.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Independent trace of everything the provider actually returned — every
 * raw streamed line (including reasoning fields), plus per-request headers
 * and end markers. This exists separately from the app crash log so you can
 * tell at a glance whether a provider streams at all and whether it emits
 * thinking content, without parsing a general debug log.
 *
 * The live file lives in app-private storage; on every launch the previous
 * trace is copied to Downloads as `jasmine-provider-<timestamp>.log`.
 */
object ProviderTrace {

    private const val TAG = "ProviderTrace"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "provider.log"
    private val started = AtomicBoolean(false)

    private val lock = Any()
    private var writer: PrintWriter? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        copyPreviousToDownloads(context)
        val capture = captureFile(context)
        capture.parentFile?.mkdirs()
        synchronized(lock) { writer = capture.printWriter() }
        Log.i(TAG, "provider trace started: ${capture.absolutePath}")
    }

    /** One request boundary: protocol, model, flags, timestamp. */
    fun request(header: String) = write("=== ${stamp()} :: $header")

    /** One raw line exactly as the provider sent it (streaming or not). */
    fun raw(line: String) = write("raw: $line")

    /** Request outcome: final length or the failure. */
    fun end(summary: String) = write("--- ${stamp()} :: $summary")

    private fun write(line: String) {
        synchronized(lock) {
            writer?.apply {
                println(line)
                flush()
            }
        }
    }

    private fun stamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun captureFile(context: Context): File =
        File(context.filesDir, "$LOG_DIR/$LOG_FILE")

    private fun copyPreviousToDownloads(context: Context) {
        val source = captureFile(context)
        if (!source.exists() || source.length() == 0L) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "jasmine-provider-$stamp.log"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    }
                }
            } else {
                val downloads = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    name,
                )
                source.copyTo(downloads, overwrite = true)
            }
            Log.i(TAG, "previous provider trace copied to Downloads as $name")
        }.onFailure { error ->
            Log.e(TAG, "could not copy provider trace to Downloads", error)
        }
        source.delete()
    }
}
