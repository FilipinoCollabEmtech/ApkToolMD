package com.fluxin.apktoolmd.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fluxin.apktoolmd.R
import com.fluxin.apktoolmd.debug.DebugLog
import com.fluxin.apktoolmd.debug.DebugOverlayService
import com.fluxin.apktoolmd.debug.DumperBridge
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.util.ArrayDeque

/**
 * Debug tab: toggles for floating logger overlay + aimbot settings.
 */
class DebugFragment : Fragment() {

    private val overlayRequest = 2001

    private lateinit var toggle: SwitchMaterial
    private lateinit var statusView: TextView
    private lateinit var checkButton: MaterialButton
    private lateinit var crashButton: MaterialButton
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleLog: TextView

    // Aimbot toggles
    private lateinit var aimEnabled: SwitchMaterial
    private lateinit var aimHead: SwitchMaterial
    private lateinit var aimInstant: SwitchMaterial
    private lateinit var aimAutoFire: SwitchMaterial
    private lateinit var aimGameAssist: SwitchMaterial
    private lateinit var aimEngineAim: SwitchMaterial
    private lateinit var aimBones: SwitchMaterial
    private lateinit var aimNoSpread: SwitchMaterial
    private lateinit var aimEsp: SwitchMaterial
    private lateinit var aimSkipDead: SwitchMaterial
    private lateinit var aimSkipTeam: SwitchMaterial

    // ------------------------------------------------------------ aim loader
    // Writes the aim offset address + enabled flag to /sdcard/ApkToolMD/aim_target.txt.
    // The cocos_dumper.so (loaded in the GAME process via DT_NEEDED) reads this
    // file at startup and writes the aim angle to that address.
    private lateinit var aimAddr: EditText
    private lateinit var aimEnable: SwitchMaterial
    private lateinit var aimApply: MaterialButton

    private val aimTargetFile: File
        get() = File("/sdcard/ApkToolMD/aim_target.txt")
    private lateinit var aimHeadZ: SeekBar
    private lateinit var aimHeadZLabel: TextView

    private var suppressToggle = false

    private val handler = Handler(Looper.getMainLooper())
    private val displayLines = ArrayDeque<String>()
    private val pending = StringBuilder()
    private val pendingLock = Any()
    private var flushScheduled = false
    private val maxDisplayLines = 400

    private val logListener: (String) -> Unit = { line ->
        synchronized(pendingLock) {
            pending.append(line).append('\n')
        }
        scheduleFlush()
    }

    private val flushRunnable = object : Runnable {
        override fun run() {
            val text: String
            synchronized(pendingLock) {
                flushScheduled = false
                if (pending.isEmpty()) return
                text = pending.toString()
                pending.setLength(0)
            }
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                displayLines.addLast(line)
                while (displayLines.size > maxDisplayLines) displayLines.removeFirst()
            }
            consoleLog.text = TextUtils.join("\n", displayLines)
            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun scheduleFlush() {
        synchronized(pendingLock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        handler.postDelayed(flushRunnable, 250)
    }

    private val aimbotConfigFile: File
        get() = File("/sdcard/aimbot_config.txt")

    private fun writeAimConfig() {
        val enabled = aimEnabled.isChecked
        val head = aimHead.isChecked
        val instant = aimInstant.isChecked
        val autofire = aimAutoFire.isChecked
        // Write 1/0 (unambiguous for the native mod's parser)
        // gameassist=0 means "turn the game's own aim assist OFF"
        val gameAssist = !aimGameAssist.isChecked
        // engineaim=1 means "we write the rotation ourselves"
        val engineAim = aimEngineAim.isChecked
        // bones=1 -> the mod calls the engine bone functions (real skeleton)
        val bones = aimBones.isChecked
        // nospread=1 -> remove the bullet spread
        val noSpread = aimNoSpread.isChecked
        // headz = head height as a fraction of the capsule half height
        val headZ = headZFromProgress(aimHeadZ.progress)
        // aimstr = how hard the aim pulls the view back to the target.
        // 1.0 = hard lock (the mouse is fought), 0.35 = soft (the mouse still
        // moves the view, the aim is pulled back gently).
        val aimStr = if (instant) 1.0f else 0.35f
        val content = "enabled=${if (enabled) 1 else 0}\nhead=${if (head) 1 else 0}\n" +
                "instant=${if (instant) 1 else 0}\nautofire=${if (autofire) 1 else 0}\n" +
                "gameassist=${if (gameAssist) 1 else 0}\n" +
                "engineaim=${if (engineAim) 1 else 0}\n" +
                "bones=${if (bones) 1 else 0}\n" +
                "nospread=${if (noSpread) 1 else 0}\n" +
                "headz=${String.format(java.util.Locale.US, "%.2f", headZ)}\n" +
                "aimstr=${String.format(java.util.Locale.US, "%.2f", aimStr)}\n" +
                "esp=${if (aimEsp.isChecked) 1 else 0}\n" +
                "skipdead=${if (aimSkipDead.isChecked) 1 else 0}\n" +
                "skipteam=${if (aimSkipTeam.isChecked) 1 else 0}\n" +
                // The in-game projection needs the viewport size in pixels.
                "screenw=${requireContext().resources.displayMetrics.widthPixels}\n" +
                "screenh=${requireContext().resources.displayMetrics.heightPixels}\n"
        try {
            aimbotConfigFile.writeText(content)
            DebugLog.app("ApkToolMD", "Aim config written: $content")
        } catch (e: Throwable) {
            DebugLog.app("ApkToolMD", "Failed to write aim config: $e")
        }
    }

    // The native side aims at: capsule centre + headz * capsule half height.
    // 0.20 .. 1.20 of the half height (0.8 = the eyes of a 1.8 m character).
    private fun headZFromProgress(progress: Int): Float = 0.20f + (progress.coerceIn(0, 100)) / 100f

    private fun progressFromHeadZ(value: Float): Int =
        (((value - 0.20f) * 100f).toInt()).coerceIn(0, 100)

    private fun updateHeadZLabel() {
        val v = headZFromProgress(aimHeadZ.progress)
        aimHeadZLabel.text = getString(R.string.aimbot_headz_label,
                String.format(java.util.Locale.US, "%.2f x capsule", v))
    }

    private fun readAimConfig() {
        if (!aimbotConfigFile.exists()) return
        try {
            val lines = aimbotConfigFile.readText().lines()
            fun parse(v: String): Boolean {
                val t = v.trim()
                return t == "true" || t == "1"
            }
            for (line in lines) {
                if (line.startsWith("enabled=")) aimEnabled.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("head=")) aimHead.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("instant=")) aimInstant.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("autofire=")) aimAutoFire.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("gameassist=")) aimGameAssist.isChecked = !parse(line.substringAfter("="))
                else if (line.startsWith("engineaim=")) aimEngineAim.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("bones=")) aimBones.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("nospread=")) aimNoSpread.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("headz=")) {
                    val v = line.substringAfter("=").trim().toFloatOrNull()
                    if (v != null) {
                        suppressToggle = true
                        aimHeadZ.progress = progressFromHeadZ(v)
                        suppressToggle = false
                    }
                }
                else if (line.startsWith("esp=")) aimEsp.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("skipdead=")) aimSkipDead.isChecked = parse(line.substringAfter("="))
                else if (line.startsWith("skipteam=")) aimSkipTeam.isChecked = parse(line.substringAfter("="))
            }
        } catch (e: Throwable) {
            DebugLog.app("ApkToolMD", "Failed to read aim config: $e")
        }
        updateHeadZLabel()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_debug, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toggle = view.findViewById(R.id.debug_toggle)
        statusView = view.findViewById(R.id.debug_status)
        checkButton = view.findViewById(R.id.debug_check)
        crashButton = view.findViewById(R.id.debug_crash)
        consoleScroll = view.findViewById(R.id.debug_console_scroll)
        consoleLog = view.findViewById(R.id.debug_console_log)

        aimEnabled = view.findViewById(R.id.aimbot_toggle_enabled)
        aimHead = view.findViewById(R.id.aimbot_toggle_head)
        aimInstant = view.findViewById(R.id.aimbot_toggle_instant)
        aimAutoFire = view.findViewById(R.id.aimbot_toggle_autofire)
        aimGameAssist = view.findViewById(R.id.aimbot_toggle_gameassist)
        aimEngineAim = view.findViewById(R.id.aimbot_toggle_engineaim)
        aimBones = view.findViewById(R.id.aimbot_toggle_bones)
        aimNoSpread = view.findViewById(R.id.aimbot_toggle_nospread)
        aimEsp = view.findViewById(R.id.aimbot_toggle_esp)
        aimSkipDead = view.findViewById(R.id.aimbot_toggle_skipdead)
        aimSkipTeam = view.findViewById(R.id.aimbot_toggle_skipteam)
        aimHeadZ = view.findViewById(R.id.aimbot_headz)
        aimHeadZLabel = view.findViewById(R.id.aimbot_headz_label)

        // ------------------------------------------------------------ aim loader
        aimAddr = view.findViewById(R.id.aim_addr)
        aimEnable = view.findViewById(R.id.aim_enable)
        aimApply = view.findViewById(R.id.aim_apply)

        // Load saved aim config if present
        if (aimTargetFile.exists()) {
            try {
                val text = aimTargetFile.readText().trim()
                val parts = text.split("\\s+".toRegex()).dropWhile { it.isEmpty() }
                if (parts.isNotEmpty()) {
                    aimAddr.setText(parts[0])
                }
                if (parts.size >= 2) {
                    aimEnable.isChecked = parts[1] == "1"
                }
            } catch (e: Throwable) {
                DebugLog.app("ApkToolMD", "Failed to read aim target: $e")
            }
        }

        aimApply.setOnClickListener {
            val addrText = aimAddr.text.toString().trim()
            if (addrText.isEmpty()) {
                showSnack(view, "Enter an address (e.g. 0x7fxxxxxx)")
                return@setOnClickListener
            }
            val addr = try {
                if (addrText.startsWith("0x", ignoreCase = true)) {
                    addrText.substring(2).toInt(16).toLong()
                } else {
                    addrText.toLong()
                }
            } catch (e: NumberFormatException) {
                showSnack(view, "Invalid address: $addrText")
                return@setOnClickListener
            }
            val enabled = aimEnable.isChecked
            try {
                aimTargetFile.writeText("${addr.toString(16)} ${if (enabled) 1 else 0}\n")
                DebugLog.app("ApkToolMD", "Aim target saved: 0x${addr.toString(16)} enabled=$enabled")
                showSnack(view, "Aim target saved: 0x${addr.toString(16)} enabled=$enabled. " +
                    "The dumper .so reads this on load. Restart the game to apply.")
            } catch (e: Throwable) {
                showSnack(view, "Failed to write: ${e.message}")
            }
        }

        // Defaults: aimbot on, head-only on, instant on, autofire off.
        aimEnabled.isChecked = true
        aimHead.isChecked = true
        aimInstant.isChecked = true
        aimAutoFire.isChecked = false
        // ON = "disable the built-in game aim assist" -> the game assist is
        // off, otherwise it fights our own aim and the view flickers.
        aimGameAssist.isChecked = true
        aimEngineAim.isChecked = true
        // The engine bone calls give the real skeleton (and the real head
        // bone for the aim)
        aimBones.isChecked = true
        aimNoSpread.isChecked = true
        aimEsp.isChecked = true
        aimHeadZ.progress = progressFromHeadZ(0.80f)
        updateHeadZLabel()

        consoleLog.text = "(live verbose logcat)\n"
        DebugLog.start()
        DebugLog.addListener(logListener)

        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            if (isChecked) enableLogger() else disableLogger()
        }

        aimEnabled.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimHead.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimInstant.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimAutoFire.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimGameAssist.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimEngineAim.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimBones.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimNoSpread.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimEsp.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimSkipDead.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimSkipTeam.setOnCheckedChangeListener { _, _ -> writeAimConfig() }
        aimHeadZ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateHeadZLabel()
                if (suppressToggle) return
                writeAimConfig()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        crashButton.setOnClickListener {
            // Show the crash report the mod writes when the game dies.
            Thread {
                val f = File("/sdcard/apktoolmd_crash.txt")
                val text = try {
                    if (f.exists()) f.readText().takeLast(4000) else "(no crash report yet)"
                } catch (e: Throwable) {
                    "crash log unreadable: $e"
                }
                DebugLog.app("ApkToolMD", "crash report:\n$text")
                view.post {
                    showSnack(view, if (text.startsWith("(")) text else "crash report sent to the log")
                }
            }.start()
        }

        checkButton.setOnClickListener {
            DebugLog.app("ApkToolMD", "manual connection check requested")
            Thread {
                val ok = DumperBridge.checkConnection(requireContext().applicationContext)
                val msg = if (ok) "Connected: dumper .so attached" else "Not connected: see console"
                view.post { statusView.text = msg }
            }.start()
        }

        syncToggle()
        readAimConfig()
    }

    override fun onResume() {
        super.onResume()
        if (toggle.isChecked && canOverlay() && !DebugOverlayService.running) {
            startService()
        } else {
            syncToggle()
        }
        readAimConfig()
        writeAimConfig()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        DebugLog.removeListener(logListener)
        DebugLog.stop()
        handler.removeCallbacks(flushRunnable)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayRequest) {
            if (canOverlay()) {
                startService()
            } else {
                suppressToggle = true
                toggle.isChecked = false
                suppressToggle = false
                statusView.text = getString(R.string.debug_status_denied)
            }
        }
    }

    private fun canOverlay(): Boolean = try {
        Settings.canDrawOverlays(requireContext())
    } catch (e: Throwable) {
        false
    }

    private fun syncToggle() {
        suppressToggle = true
        toggle.isChecked = DebugOverlayService.running
        suppressToggle = false
        statusView.text =
            if (DebugOverlayService.running) getString(R.string.debug_status_on)
            else getString(R.string.debug_status_off)
    }

    private fun enableLogger() {
        val ctx = requireContext()
        if (!canOverlay()) {
            statusView.text = getString(R.string.debug_need_permission)
            showSnack(view, getString(R.string.debug_need_permission))
            try {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.packageName)
                    ),
                    overlayRequest
                )
            } catch (e: Throwable) {
                showSnack(view, "Cannot open overlay settings: ${e.message}")
                suppressToggle = true
                toggle.isChecked = false
                suppressToggle = false
            }
            return
        }
        startService()
    }

    private fun startService() {
        try {
            DebugOverlayService.start(requireContext())
            DebugLog.app("ApkToolMD", "debug logger turned ON (draw over other apps)")
            statusView.text = getString(R.string.debug_status_on)
            suppressToggle = true
            toggle.isChecked = true
            suppressToggle = false
        } catch (e: Throwable) {
            statusView.text = "Failed to start logger: ${e.message}"
            showSnack(view, "Failed to start logger: ${e.message}")
        }
    }

    private fun disableLogger() {
        DebugOverlayService.stop(requireContext())
        DebugLog.app("ApkToolMD", "debug logger turned OFF")
        statusView.text = getString(R.string.debug_status_off)
    }

    private fun showSnack(v: View?, message: String) {
        v ?: return
        val snackbar = Snackbar.make(v, message, Snackbar.LENGTH_LONG)
        requireActivity().findViewById<View>(R.id.bottom_nav)?.let {
            snackbar.setAnchorView(it)
        }
        snackbar.show()
    }
}