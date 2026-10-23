package com.fluxin.apktoolmd.debug

import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live log bus for the Debug page.
 *
 * Two sources feed the console:
 *
 *  1. A background thread tails `logcat -v threadtime` (no priority filter, so
 *     VERBOSE and everything above is captured). This shows ApkToolMD's own
 *     logs, and — once the dumper `.so` is attached in-process — its `UEDump3r`
 *     tagged logs too.
 *  2. A status-file watcher. The injected `.so` normally runs inside the GAME
 *     process, whose logcat is not readable from ApkToolMD (per-uid isolation,
 *     no root). But that `.so` writes `UEDump3r_status.txt` for every phase and
 *     error, so we poll it (and the game's own app-data copy) and inject changes
 *     as `[status]` lines. That is how live dump progress/failures reach the
 *     overlay while you play.
 *
 * Listeners are invoked on background threads; UI consumers must post to their
 * own handler/looper.
 */
object DebugLog {

    private const val MAX_LINES = 1500
    private const val DUMPER_TAG = "UEDump3r"
    private const val STATUS_PREFIX = "[status]"

    private val buffer = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val refCount = AtomicInteger(0)

    @Volatile private var process: Process? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var statusWatcher: Thread? = null
    @Volatile private var watching = false
    @Volatile private var dumperLines = 0

    /** True while the log reader is attached. */
    fun isRunning(): Boolean = refCount.get() > 0

    /** Number of `UEDump3r`-tagged logcat lines seen (used to detect new .so logs). */
    fun dumperLineCount(): Int = dumperLines

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** Log through the normal Android logger; it re-enters via the logcat tail. */
    fun app(msg: String) {
        Log.i(TAG, msg)
    }

    fun app(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    private fun push(line: String) {
        val trimmed = line.trimEnd()
        synchronized(this) {
            buffer.addLast(trimmed)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            // Status injections must not count as .so logcat lines.
            if (!trimmed.startsWith(STATUS_PREFIX) && trimmed.contains(DUMPER_TAG)) {
                dumperLines++
            }
        }
        for (l in listeners) {
            try {
                l(trimmed)
            } catch (_: Throwable) {
                // a broken listener must never kill the log bus
            }
        }
    }

    /** Attach the log reader + status watcher (reference counted). */
    fun start() {
        if (refCount.incrementAndGet() != 1) return
        push("=== ApkToolMD debug logger attached (verbose logcat + status bridge) ===")
        startLogcatReader()
        startStatusWatcher()
    }

    /** Detach everything (reference counted). */
    fun stop() {
        if (refCount.decrementAndGet() > 0) return
        refCount.set(0)
        stopLogcatReader()
        stopStatusWatcher()
        push("=== ApkToolMD debug logger detached ===")
    }

    private fun startLogcatReader() {
        val t = Thread {
            try {
                val pb = ProcessBuilder("logcat", "-v", "threadtime")
                pb.redirectErrorStream(true)
                val p = pb.start()
                process = p
                val input = p.inputStream.bufferedReader()
                while (true) {
                    val line = try {
                        input.readLine()
                    } catch (e: Throwable) {
                        break
                    }
                    if (line == null) break
                    push(line)
                }
                push("=== logcat stream ended ===")
            } catch (e: Throwable) {
                push("!!! logcat reader failed: $e")
            } finally {
                process = null
            }
        }
        t.name = "ApkToolMD-logcat"
        t.isDaemon = true
        reader = t
        t.start()
    }

    private fun stopLogcatReader() {
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        reader = null
    }

    private fun startStatusWatcher() {
        if (watching) return
        watching = true
        val t = Thread {
            val last = HashMap<String, String>()
            while (watching) {
                for (f in statusCandidates()) {
                    val text = try {
                        if (f.exists()) f.readText().trim() else null
                    } catch (e: Throwable) {
                        null
                    }
                    if (text.isNullOrEmpty()) continue
                    val prev = last[f.path]
                    if (prev != text) {
                        last[f.path] = text
                        val flat = text.replace('\n', ' ').replace('\r', ' ')
                        push("$STATUS_PREFIX ${f.name} -> $flat")
                    }
                }
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        t.name = "ApkToolMD-status"
        t.isDaemon = true
        statusWatcher = t
        t.start()
    }

    private fun stopStatusWatcher() {
        watching = false
        statusWatcher?.interrupt()
        statusWatcher = null
    }

    /** Files written by the dumper .so (shared /sdcard + the owning app dir). */
    private fun statusCandidates(): List<File> {
        val out = ArrayList<File>()
        out.add(File("/sdcard/UEDump3r_status.txt"))
        out.add(File("/sdcard/UE4Dump3r_status.txt"))
        try {
            File("/sdcard/Android/data").listFiles()?.forEach { pkg ->
                val c = File(pkg, "files/UEDump3r_status.txt")
                if (c.exists() && !out.contains(c)) out.add(c)
            }
        } catch (_: Throwable) {
        }
        return out
    }

    const val TAG = "ApkToolMD"
}
