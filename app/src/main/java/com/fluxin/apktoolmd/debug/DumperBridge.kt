package com.fluxin.apktoolmd.debug

import android.content.Context
import android.provider.Settings
import java.io.File

/**
 * Verifies that the dumper `.so` is attached to and connected with ApkToolMD.
 *
 * The check is deliberately end-to-end rather than a simple file test:
 *
 *  1. Is the overlay (draw-over-other-apps) layer active?
 *  2. Is the dumper `.so` actually present for this app (native lib dir / assets)?
 *  3. Attach it to this process with [System.loadLibrary]; a successful soname
 *     link means the app and the `.so` are wired together.
 *  4. The `.so`'s `__attribute__((constructor))` logs to logcat under the
 *     `UEDump3r` tag the instant it is linked — seeing a *new* such line proves
 *     a live, bidirectional app<->.so log bridge.
 *  5. The same constructor writes `UEDump3r_status.txt`; reading it back proves
 *     the `.so` and the app share a filesystem channel.
 */
object DumperBridge {

    const val LIB = "shared_UEDump3r_arm64"
    private const val TAG = "ApkToolMD-conn"

    @Volatile var attached = false
        private set

    private fun step(msg: String) = DebugLog.app(TAG, msg)

    fun checkConnection(ctx: Context): Boolean {
        step("=== CHECK CONNECTION ===")
        var ok = true

        // 1. overlay layer
        val overlay = try {
            Settings.canDrawOverlays(ctx)
        } catch (e: Throwable) {
            step("overlay permission: error $e")
            false
        }
        step("overlay permission (draw over other apps): $overlay")
        step("overlay service running: ${DebugLog.isRunning()}")

        // 2. presence
        val nld = ctx.applicationInfo.nativeLibraryDir ?: "?"
        step("nativeLibraryDir: $nld")
        val soFile = File(nld, "lib$LIB.so")
        val present = soFile.exists()
        step("dumper .so in nativeLibraryDir: $present" +
            if (present) " (${soFile.length()} bytes)" else " ($soFile)")

        val assetSize = assetSize(ctx, "dumper/lib$LIB.so")
        step("assets/dumper/lib$LIB.so: " +
            if (assetSize > 0) "present ($assetSize bytes)" else "missing")

        if (!present && assetSize <= 0) {
            step("RESULT: FAIL - dumper .so is not bundled with ApkToolMD")
            attached = false
            return false
        }

        // 3. attach to this process
        val before = DebugLog.dumperLineCount()
        step("attaching .so to this app process: System.loadLibrary(\"$LIB\") ...")
        try {
            System.loadLibrary(LIB)
            attached = true
            step("attach result: OK - JNI soname link established")
        } catch (e: Throwable) {
            attached = false
            ok = false
            step("attach FAILED: $e")
        }

        // 4. bidirectional log bridge: wait for a fresh UEDump3r line
        var sawFresh = false
        if (attached) {
            step("waiting up to 4s for UEDump3r log lines from the .so ...")
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                if (DebugLog.dumperLineCount() > before) {
                    sawFresh = true
                    break
                }
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {
                }
            }
            if (sawFresh) {
                step("log bridge: CONNECTED - received ${DebugLog.dumperLineCount() - before} new UEDump3r line(s)")
            } else {
                step("log bridge: no new UEDump3r lines yet (attached, native may be quiet)")
            }
        }

        // 5. filesystem handshake
        val status = File("/sdcard/UEDump3r_status.txt")
        val statusTxt = if (status.exists()) {
            try {
                status.readText().trim()
            } catch (e: Throwable) {
                "unreadable: $e"
            }
        } else {
            "(not written yet)"
        }
        step("status file /sdcard/UEDump3r_status.txt: $statusTxt")
        step("note: the injected .so runs in the GAME process, so its live logcat is not readable here (per-uid). Dump progress/failures are shown via the [status] bridge below.")

        val pass = attached && (sawFresh || statusTxt.contains("LOADED"))
        val verdict = when {
            pass -> "PASS - ApkToolMD <-> dumper .so connected"
            attached -> "PARTIAL - .so attached, bridge not yet observed"
            else -> "FAIL - could not attach .so"
        }
        step("RESULT: $verdict")
        return pass
    }

    private fun assetSize(ctx: Context, path: String): Long {
        return try {
            ctx.assets.open(path).use { it.available().toLong() }
        } catch (e: Throwable) {
            0L
        }
    }
}
