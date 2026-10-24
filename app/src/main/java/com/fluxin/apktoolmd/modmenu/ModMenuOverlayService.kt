package com.fluxin.apktoolmd.modmenu

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * R3DNETWORK Mod Menu floating overlay service.
 *
 * Shows a small floating bubble on top of the target app. Tapping the bubble
 * expands a panel with toggle switches for mod menu features (god mode, aim
 * assist, ESP, speed hack, no spread, auto fire, infinite ammo, FOV).
 *
 * Requires SYSTEM_ALERT_WINDOW permission (granted by the user via settings).
 */
class ModMenuOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var windowManagerLayoutParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelVisible = false

    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        hideBubble()
        hidePanel()
        super.onDestroy()
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleView = LayoutInflater.from(this).inflate(R.layout.modmenu_bubble, null)

        windowManagerLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManagerLayoutParams!!.gravity = Gravity.TOP or Gravity.LEFT
        windowManagerLayoutParams!!.x = 50
        windowManagerLayoutParams!!.y = 200

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowManagerLayoutParams!!.x
                    initialY = windowManagerLayoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    togglePanel()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    windowManagerLayoutParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    windowManagerLayoutParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager!!.updateViewLayout(bubbleView, windowManagerLayoutParams)
                    true
                }
                else -> false
            }
        }

        windowManager!!.addView(bubbleView, windowManagerLayoutParams)
    }

    private fun hideBubble() {
        windowManager?.removeView(bubbleView)
        bubbleView = null
    }

    private fun togglePanel() {
        if (panelVisible) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        panelView = LayoutInflater.from(this).inflate(R.layout.modmenu_panel, null)

        val switchGodMode: Switch = panelView!!.findViewById(R.id.switch_god_mode)
        val switchAimAssist: Switch = panelView!!.findViewById(R.id.switch_aim_assist)
        val switchESP: Switch = panelView!!.findViewById(R.id.switch_esp)
        val switchSpeedHack: Switch = panelView!!.findViewById(R.id.switch_speed_hack)
        val switchNoSpread: Switch = panelView!!.findViewById(R.id.switch_no_spread)
        val switchAutoFire: Switch = panelView!!.findViewById(R.id.switch_auto_fire)
        val switchInfiniteAmmo: Switch = panelView!!.findViewById(R.id.switch_infinite_ammo)

        switchGodMode.isChecked = ModMenuLoader.isGodMode()
        switchAimAssist.isChecked = ModMenuLoader.isAimAssist()
        switchESP.isChecked = ModMenuLoader.isESP()
        switchSpeedHack.isChecked = ModMenuLoader.isSpeedHack()
        switchNoSpread.isChecked = ModMenuLoader.isNoSpread()
        switchAutoFire.isChecked = ModMenuLoader.isAutoFire()
        switchInfiniteAmmo.isChecked = ModMenuLoader.isInfiniteAmmo()

        switchGodMode.setOnCheckedChangeListener { _, isChecked ->
            ModMenuLoader.setGodMode(isChecked)
        }
        switchAimAssist.setOnCheckedChangeListener { _, isChecked ->
            ModMenuLoader.setAimAssist(isChecked)
        }
        switchESP.setOnCheckedChangeListener { _, isChecked ->
            ModMenuLoader.setESP(isChecked)
        }
        switchSpeedHack.setOnCheckedChangeListener { _, isChecked ->
            ModMenuLoader.setSpeedHack(isChecked)
        }
        switchNoSpread.setOnCheckedChangeListener { _, isChecked ->
            ModMenuLoader.setNoSpread(isChecked)
        }
        switchAutoFire.setOnCheckedChangeListener { _, isChecked ->
            ModMenuLoader.setAutoFire(isChecked)
        }
        switchInfiniteAmmo.setOnCheckedChangeListener { _, isChecked ->
            ModMenuLoader.setInfiniteAmmo(isChecked)
        }

        val txtTitle: TextView = panelView!!.findViewById(R.id.txt_menu_title)
        txtTitle.text = "R3DNETWORK Mod Menu"

        panelParams = WindowManager.LayoutParams(
            400,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        panelParams!!.gravity = Gravity.TOP or Gravity.LEFT
        panelParams!!.x = 50
        panelParams!!.y = 300

        windowManager!!.addView(panelView, panelParams)
        panelVisible = true
    }

    private fun hidePanel() {
        windowManager?.removeView(panelView)
        panelView = null
        panelVisible = false
    }
}
