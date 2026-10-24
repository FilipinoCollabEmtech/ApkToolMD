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
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fluxin.apktoolmd.R
import com.fluxin.apktoolmd.debug.DebugLog
import com.fluxin.apktoolmd.debug.DebugOverlayService
import com.fluxin.apktoolmd.debug.DumperBridge
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.ArrayDeque

/**
 * Debug tab: toggles for floating logger overlay only.
 * Aim-related features (aimbot, aim loader/inject dumper) have been removed.
 */
class DebugFragment : Fragment() {

    private val overlayRequest = 2001

    private lateinit var toggle: SwitchMaterial
    private lateinit var statusView: TextView
    private lateinit var checkButton: MaterialButton
    private lateinit var crashButton: MaterialButton
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleLog: TextView

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

        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggle) return@setOnCheckedChangeListener
            if (isChecked) enableLogger() else disableLogger()
        }

        syncToggle()
    }

    override fun onResume() {
        super.onResume()
        if (toggle.isChecked && canOverlay() && !DebugOverlayService.running) {
            startService()
        } else {
            syncToggle()
        }
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