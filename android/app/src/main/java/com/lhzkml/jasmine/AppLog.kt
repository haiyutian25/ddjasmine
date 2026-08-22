package com.lhzkml.jasmine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time diagnostic capture that survives a crash.
 *
 * Two sources feed one rotating file under app-private storage:
 *  - a live `logcat --pid=<self>` stream (own-process JVM logs AND native
 *    stderr/tombstone lines before the process dies), and
 *  - an uncaught JVM exception marker written by the crash handler.
 *
 * On every launch, the previous capture is copied to the public Downloads
 * directory as `jasmine-crash-<timestamp>.log` so the file can be pulled
 * without a development machine, then the capture restarts clean.
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "jasmine.log"
    private val started = AtomicBoolean(false)

    private val lock = Any()
    private var writer: PrintWriter? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        copyPreviousToDownloads(context)
        val capture = captureFile(context)
        capture.parentFile?.mkdirs()
        synchronized(lock) { writer = capture.printWriter() }
        startLogcatTee(context, capture)
        Log.i(TAG, "log capture started: ${capture.absolutePath}")
    }

    /** Appends an explicit marker (used by the crash handler). */
    fun crash(thread: Thread, failure: Throwable) {
        val stack = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }
        writeLine(
            "CRASH thread=${thread.name} time=${System.currentTimeMillis()}\n$stack"
        )
    }

    private fun writeLine(line: String) {
        synchronized(lock) {
            writer?.apply {
                println(line)
                flush()
            }
        }
    }

    private fun captureFile(context: Context): File =
        File(context.filesDir, "$LOG_DIR/$LOG_FILE")

    private fun copyPreviousToDownloads(context: Context) {
        val source = captureFile(context)
        if (!source.exists() || source.length() == 0L) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "jasmine-crash-$stamp.log"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS,
                    )
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
            Log.i(TAG, "previous capture copied to Downloads as $name")
        }.onFailure { error ->
            Log.e(TAG, "could not copy capture to Downloads", error)
        }
        source.delete()
    }

    /**
     * Streams this process's own logcat lines into the capture file, flushing
     * per line so a crash never loses the tail. Own-process logcat is
     * readable without extra permissions since Android 7.
     */
    private fun startLogcatTee(context: Context, capture: File) {
        Thread(
            {
                try {
                    val pid = Process.myPid()
                    val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid")
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (capture.length() > MAX_CAPTURE_BYTES) break
                            writeLine(line)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "logcat tee stopped", t)
                }
            },
            "jasmine-logcat-tee",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private const val MAX_CAPTURE_BYTES = 2L * 1024 * 1024
}
