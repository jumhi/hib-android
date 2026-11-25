package com.sameerasw.airsync.clip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * SPACE.md §16.3: Android never tells a background app that the clipboard changed — it only logs
 * "ClipboardService: Denying clipboard access to com.sameerasw.airsync, application is not in focus".
 * With READ_LOGS (granted over ADB: `pm grant com.sameerasw.airsync android.permission.READ_LOGS`)
 * H.I.B follows that log and, on its own line, asks [onDenied] to take focus and read the clip.
 *
 * Android 13+ asks "Allow H.I.B to access all device logs?" when the log reader starts (only while
 * H.I.B is in front; a reader started in the background is declined). So the reader is started, or
 * restarted, when H.I.B comes to the front, and then kept running.
 */
object ClipLogWatcher {
    private const val TAG = "HIBClip"
    /** Lines in the first moments are the tail of the old log, not a new copy. */
    private const val WARMUP_MS = 750L
    /** One focus read per this many ms at most (a single copy can log several denials). */
    private const val MIN_GAP_MS = 700L

    enum class State { OFF, NEEDS_SETUP, WAITING_FOR_FRONT, RUNNING }

    @Volatile private var process: Process? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var startedInFront = false
    @Volatile private var lastTrigger = 0L
    @Volatile private var onDenied: (() -> Unit)? = null

    fun canReadLogs(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun isRunning(): Boolean = thread?.isAlive == true

    fun state(context: Context): State = when {
        !canReadLogs(context) || !ClipFocusReader.canOverlay(context) -> State.NEEDS_SETUP
        isRunning() && startedInFront -> State.RUNNING
        else -> State.WAITING_FOR_FRONT
    }

    /**
     * Starts the reader if it can (READ_LOGS and the overlay allowed). [inFront] says H.I.B is on screen
     * now; a reader that was started in the background is restarted then, so Android can ask.
     */
    @Synchronized
    fun ensure(context: Context, inFront: Boolean, onDenied: () -> Unit) {
        this.onDenied = onDenied
        val ctx = context.applicationContext
        if (!canReadLogs(ctx) || !ClipFocusReader.canOverlay(ctx)) {
            stop()
            return
        }
        if (isRunning() && (startedInFront || !inFront)) return
        stop()
        startedInFront = inFront
        val pkg = ctx.packageName
        val proc = runCatching {
            ProcessBuilder("logcat", "-v", "brief", "-T", "1", "ClipboardService:V", "*:S")
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            Log.w(TAG, "[clip] log watcher could not start: ${it.javaClass.simpleName}")
            return
        }
        process = proc
        val t = Thread({ readLoop(proc, pkg) }, "HIB-ClipLog").apply { isDaemon = true }
        thread = t
        t.start()
        Log.d(TAG, "[clip] log watcher started (front=$inFront)")
    }

    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
        thread?.interrupt()
        thread = null
    }

    private fun readLoop(proc: Process, pkg: String) {
        val started = SystemClock.elapsedRealtime()
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    if (!ClipLogLine.isDenialFor(line, pkg)) continue
                    val now = SystemClock.elapsedRealtime()
                    if (now - started < WARMUP_MS || now - lastTrigger < MIN_GAP_MS) continue
                    lastTrigger = now
                    runCatching { onDenied?.invoke() }
                }
            }
        } catch (_: Exception) {
        } finally {
            Log.d(TAG, "[clip] log watcher stopped")
            if (process === proc) {
                process = null
                thread = null
            }
        }
    }
}
