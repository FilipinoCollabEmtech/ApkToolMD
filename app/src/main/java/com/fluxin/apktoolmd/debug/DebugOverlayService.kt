package com.fluxin.apktoolmd.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
import java.io.File
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fluxin.apktoolmd.R
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Foreground service that draws the debug logger: a floating blob overlay that
 * opens into a console panel showing verbose logcat (including the dumper
 * `.so`'s own `UEDump3r` logs once it is attached to ApkToolMD).
 *
 * Running it as a foreground service keeps the overlay alive while the user is
 * inside the patched game (i.e. "draw over other apps").
 */
class DebugOverlayService : Service() {

    companion object {
        @Volatile
        var running = false
            private set

        private const val CHANNEL_ID = "apktoolmd_debug"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.fluxin.apktoolmd.debug.START"
        const val ACTION_STOP = "com.fluxin.apktoolmd.debug.STOP"

        fun start(ctx: Context) {
            val intent = Intent(ctx, DebugOverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DebugOverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var blobView: View? = null
    private var panelView: View? = null
    private var espView: TextView? = null
    private var espOverlayView: EspOverlayView? = null
    private val espHandler = Handler(Looper.getMainLooper())
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView

    private var panelShowing = false
    private var autoScroll = true

    private val displayLines = ArrayDeque<String>()
    private val pending = StringBuilder()
    private val pendingLock = Any()
    private var flushScheduled = false

    private val maxDisplayLines = 600

    private val logListener: (String) -> Unit = { line ->
        synchronized(pendingLock) {
            pending.append(line).append('\n')
        }
        scheduleFlush()
    }

    private val modLogRunnable = object : Runnable {
        override fun run() {
            pollModLog()
            handler.postDelayed(this, 500)
        }
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
            console.text = TextUtils.join("\n", displayLines)
            if (autoScroll) {
                consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun scheduleFlush() {
        synchronized(pendingLock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        handler.postDelayed(flushRunnable, 250)
    }

    // ---------------------------------------------------------------------
    // The MOD's own log (/sdcard/aimbot_debug.log) - without this the
    // floating window shows only the app's logcat and the mod's diagnostics
    // (the scan counts, the bone map, the aim, the crash breadcrumbs) are
    // invisible.
    // ---------------------------------------------------------------------
    private var modLogOffset = 0L
    private var showModLog = true

    private fun pollModLog() {
        if (!showModLog) return
        try {
            val f = File("/sdcard/aimbot_debug.log")
            if (!f.exists()) return
            val len = f.length()
            if (len < modLogOffset) modLogOffset = 0L      // the mod restarted
            if (len == modLogOffset) return
            val want = (len - modLogOffset).coerceAtMost(16384L).toInt()
            val buf = ByteArray(want)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(modLogOffset)
                val n = raf.read(buf)
                if (n <= 0) return
                val text = String(buf, 0, n)
                val pendingMod = StringBuilder()
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    pendingMod.append("[mod] ").append(line).append('\n')
                }
                synchronized(pendingLock) {
                    pending.append(pendingMod.toString())
                }
                scheduleFlush()
            }
            modLogOffset = len
        } catch (_: Throwable) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundNotification()
        DebugLog.start()
        DebugLog.addListener(logListener)

        handler.postDelayed(modLogRunnable, 1000)

        try {
            addBlob()
            addEspPanel()
            addEspOverlay()
            buildPanel()
            running = true
            DebugLog.app("ApkToolMD", "debug overlay attached (service running)")
        } catch (e: Throwable) {
            DebugLog.app("ApkToolMD", "overlay attach failed: $e")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        DebugLog.removeListener(logListener)
        DebugLog.stop()
        handler.removeCallbacks(flushRunnable)
        try {
            panelView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {
        }
        try {
            espOverlayView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {
        }
        try {
            blobView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {
        }
        panelView = null
        blobView = null
        espOverlayView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- overlay ui

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    /** Real display size in pixels (includes the area under system bars). */
    private fun screenSize(): Pair<Int, Int> {
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return point.x to point.y
    }

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w,
            h,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    /**
     * Full screen ESP layer.  It never takes touches (the game stays usable)
     * and is fed from the mod's /sdcard/aimbot_esp.txt.
     */
    private fun addEspOverlay() {
        val (screenW, screenH) = screenSize()
        // publish the CURRENT orientation (the game runs in landscape) so the
        // mod can project the world positions to the right pixels
        writeExternalFlagInt("screenw", screenW)
        writeExternalFlagInt("screenh", screenH)
        val view = EspOverlayView(this)
        applyEspOptions(view)

        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            windowManager.addView(view, params)
            espOverlayView = view
        } catch (_: Throwable) {
        }

        val poll = object : Runnable {
            override fun run() {
                try {
                    applyEspOptions(view)
                    view.loadFrom(File("/sdcard/aimbot_esp_frames.txt"))
                    view.invalidate()
                } catch (_: Throwable) {
                }
                // Publish the CURRENT screen size.  The service usually starts
                // in portrait while the game runs in landscape - with the
                // wrong size the mod projects every enemy off screen and the
                // ESP never updates.
                try {
                    val (w, h) = screenSize()
                    if (w != lastScreenW || h != lastScreenH) {
                        lastScreenW = w
                        lastScreenH = h
                        writeExternalFlagInt("screenw", w)
                        writeExternalFlagInt("screenh", h)
                    }
                } catch (_: Throwable) {
                }
                espHandler.postDelayed(this, 100)
            }
        }
        espHandler.postDelayed(poll, 100)
    }

    /** Push the panel switches into the ESP view. */
    private fun applyEspOptions(view: EspOverlayView) {
        view.showBox = readExternalFlag("espbox")
        view.showSkeleton = readExternalFlag("espskeleton")
        view.showHealth = readExternalFlag("esphealth")
        view.showNames = readExternalFlag("espnames")
        view.showDistance = readExternalFlag("espdistance")
        view.showTracers = readExternalFlag("esptracer")
        view.showRadar = readExternalFlag("espradar", true)
        view.onlyEnemies = readExternalFlag("esponly")
        view.maxDistM = externalFlagInt("espmaxdist", 120)
    }

    private fun externalFlagInt(key: String, def: Int): Int {
        return try {
            val line = externalFlagsFile().readLines().firstOrNull { it.startsWith("$key=") }
            line?.substringAfter("=")?.trim()?.toIntOrNull() ?: def
        } catch (_: Throwable) {
            def
        }
    }

    /** Panel showing the in-game aimbot distance readout (from the mod). */
    private fun addEspPanel() {
        val tv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#00FF66"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(0x99000000.toInt())
            maxLines = 4
            isSingleLine = false
        }
        // Wide + tall enough for the full distance line
        val params = baseParams(dp(340), dp(72)).apply {
            x = dp(8)
            y = dp(8)
        }
        try {
            windowManager.addView(tv, params)
            espView = tv
        } catch (_: Throwable) {
        }

        val poll = object : Runnable {
            override fun run() {
                updateEsp()
                espHandler.postDelayed(this, 200)
            }
        }
        espHandler.postDelayed(poll, 200)
    }

    private fun updateEsp() {
        val tv = espView ?: return
        try {
            val f = File("/sdcard/aimbot_esp.txt")
            if (f.exists() && f.length() > 0) {
                val txt = f.readText().trim()
                if (txt.isNotEmpty()) {
                    tv.text = txt
                    return
                }
            }
        } catch (_: Throwable) {
        }
        tv.text = ""
    }

    private fun addBlob() {
        val size = dp(56)
        val blob = TextView(this).apply {
            text = "LOG"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_blob)
        }
        val params = baseParams(size, size).apply {
            x = dp(12)
            y = dp(160)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        blob.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(blob, params)
                    } catch (_: Throwable) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(blob, params)
        blobView = blob
    }

    private fun panelButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_debug_button)
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            lp.marginEnd = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun buildPanel() {
        val (screenW, screenH) = screenSize()
        // Fit the panel to the CURRENT orientation; margins keep it on-screen
        // in both portrait and landscape so all buttons stay reachable.
        val panelWidth = (minOf(dp(400), screenW - dp(16))).coerceAtLeast(dp(240))
        val panelHeight = (minOf(dp(520), screenH - dp(80))).coerceAtLeast(dp(220))
        val startX = dp(8)
        val startY = (screenH - panelHeight - dp(8)).coerceAtLeast(dp(8))

        console = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF00FF66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setText("(waiting for logs…)\n")
        }

        consoleScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF050505.toInt())
            addView(
                console,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0)
                if (child != null) {
                    val bottom = child.height - height
                    autoScroll = scrollY >= bottom - dp(24)
                }
            }
        }

        // ---- tab switcher (Console / External) + a drag handle ----
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        // The tab bar is all buttons, so the panel had nothing to grab -
        // this handle makes it draggable again.
        val dragHandle = TextView(this).apply {
            text = "≡"
            setTextColor(0xFF00FF66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        tabBar.addView(dragHandle)
        val consoleTab = panelButton("Console") { showTab(true) }
        val externalTab = panelButton("External") { showTab(false) }
        tabBar.addView(consoleTab)
        tabBar.addView(externalTab)

        // ---- console content ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
        }
        val title = TextView(this).apply {
            text = "ApkToolMD Debug"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "HIDE"
            setTextColor(0xFF00FF66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { togglePanel() }
        }
        header.addView(title)
        header.addView(close)

        val consoleButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        consoleButtons.addView(panelButton(getString(R.string.debug_check_connection)) {
            Thread {
                val ok = DumperBridge.checkConnection(applicationContext)
                DebugLog.app("ApkToolMD-conn", "check finished: ${if (ok) "CONNECTED" else "NOT CONNECTED"}")
            }.start()
        })
        consoleButtons.addView(panelButton(getString(R.string.debug_clear)) {
            synchronized(pendingLock) {
                pending.setLength(0)
                displayLines.clear()
            }
            console.text = ""
        })
        consoleButtons.addView(panelButton(getString(R.string.debug_mod_log)) {
            showModLog = !showModLog
            if (!showModLog) {
                modLogOffset = try {
                    File("/sdcard/aimbot_debug.log").length()
                } catch (_: Throwable) {
                    0L
                }
            }
            DebugLog.app("ApkToolMD", "mod log = $showModLog")
        })
        consoleButtons.addView(panelButton(getString(R.string.debug_hide)) { togglePanel() })

        val consoleContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        consoleContent.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        consoleContent.addView(consoleScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        consoleContent.addView(consoleButtons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- external content (real ESP switches) ----
        val externalContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val extTitle = TextView(this).apply {
            text = "ESP (drawn on the game)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        externalContent.addView(extTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun addEspSwitch(label: String, key: String, def: Boolean): Switch {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(2))
            }
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = Switch(this).apply {
                isChecked = readExternalFlag(key, def)
                setOnCheckedChangeListener { _, v ->
                    writeExternalFlag(key, v)
                    DebugLog.app("ApkToolMD-esp", "$key = $v")
                    espOverlayView?.let { applyEspOptions(it); it.invalidate() }
                }
            }
            row.addView(tv)
            row.addView(sw)
            externalContent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return sw
        }

        addEspSwitch("Box", "espbox", true)
        addEspSwitch("Skeleton", "espskeleton", true)
        addEspSwitch("Health bar", "esphealth", true)
        addEspSwitch("Names", "espnames", true)
        addEspSwitch("Distance", "espdistance", true)
        addEspSwitch("Tracers", "esptracer", false)
        addEspSwitch("Only enemies", "esponly", true)
        addEspSwitch("Radar", "espradar", true)

        // max distance slider (metres)
        val distRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val distLabel = TextView(this).apply {
            text = "Max distance: 120 m"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val distSeek = android.widget.SeekBar(this).apply {
            max = 300
            progress = externalFlagInt("espmaxdist", 120)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    distLabel.text = "Max distance: $progress m"
                    writeExternalFlagInt("espmaxdist", progress)
                    espOverlayView?.let { applyEspOptions(it); it.invalidate() }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        distRow.addView(distLabel)
        distRow.addView(distSeek, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
        externalContent.addView(distRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val extHint = TextView(this).apply {
            text = "Data comes from the mod (aimbot_esp.txt). The switches are written to ApkToolMD/external_flags.txt so the mod can filter too."
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(0, dp(10), 0, 0)
        }
        externalContent.addView(extHint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // The switches do not fit into the panel -> make the tab scrollable
        val externalScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF050505.toInt())
            isFillViewport = true
            addView(
                externalContent,
                FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // container that swaps content
        val contentFrame = FrameLayout(this).apply { }
        consoleContent.visibility = View.VISIBLE
        externalScroll.visibility = View.GONE
        contentFrame.addView(consoleContent, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        contentFrame.addView(externalScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // remember refs for tab switching
        this.consoleContentRef = consoleContent
        this.externalContentRef = externalScroll

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_debug_panel)
        }
        root.addView(tabBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(contentFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val params = baseParams(panelWidth, panelHeight).apply {
            x = startX
            y = startY
        }

        makeDraggable(dragHandle, root, params)
        // the title is draggable as well
        makeDraggable(title, root, params)

        panelView = root
        panelParams = params
    }

    private var consoleContentRef: View? = null
    private var externalContentRef: View? = null

    private fun showTab(console: Boolean) {
        consoleContentRef?.visibility = if (console) View.VISIBLE else View.GONE
        externalContentRef?.visibility = if (console) View.GONE else View.VISIBLE
    }

    private fun externalFlagsFile(): File {
        val dir = File("/sdcard/ApkToolMD")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "external_flags.txt")
    }

    private fun readExternalFlag(key: String, def: Boolean = false): Boolean {
        return try {
            val line = externalFlagsFile().readLines().firstOrNull { it.startsWith("$key=") }
            if (line == null) def else line.substringAfter("=").trim() == "1"
        } catch (_: Throwable) { def }
    }

    private fun writeExternalFlag(key: String, value: Boolean) {
        try {
            val f = externalFlagsFile()
            val lines = (if (f.exists()) f.readLines() else emptyList()).filter { !it.startsWith("$key=") }.toMutableList()
            lines.add("$key=${if (value) 1 else 0}")
            f.writeText(lines.joinToString("\n") + "\n")
            // also mirror into game app data dir if present
            val gameDir = File("/sdcard/Android/data/com.ForgeGames.SpecialForcesGroup2/files")
            if (gameDir.exists()) {
                File(gameDir, "ApkToolMD_external_flags.txt").writeText(lines.joinToString("\n") + "\n")
            }
        } catch (e: Throwable) {
            DebugLog.app("ApkToolMD-ext", "write flag failed: $e")
        }
    }

    private fun writeExternalFlagInt(key: String, value: Int) {
        try {
            val f = externalFlagsFile()
            val lines = (if (f.exists()) f.readLines() else emptyList()).filter { !it.startsWith("$key=") }.toMutableList()
            lines.add("$key=$value")
            f.writeText(lines.joinToString("\n") + "\n")
            val gameDir = File("/sdcard/Android/data/com.ForgeGames.SpecialForcesGroup2/files")
            if (gameDir.exists()) {
                File(gameDir, "ApkToolMD_external_flags.txt").writeText(lines.joinToString("\n") + "\n")
            }
        } catch (e: Throwable) {
            DebugLog.app("ApkToolMD-ext", "write flag failed: $e")
        }
    }

    private var panelParams: WindowManager.LayoutParams? = null
    private var lastScreenW = 0
    private var lastScreenH = 0

    private fun makeDraggable(handle: View, window: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    try {
                        windowManager.updateViewLayout(window, params)
                    } catch (_: Throwable) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun togglePanel() {
        val panel = panelView ?: return
        val params = panelParams ?: return
        if (panelShowing) {
            try {
                windowManager.removeView(panel)
            } catch (_: Throwable) {
            }
            panelShowing = false
        } else {
            try {
                windowManager.addView(panel, params)
                panelShowing = true
                autoScroll = true
                consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
            } catch (e: Throwable) {
                DebugLog.app("ApkToolMD", "show panel failed: $e")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rebuild panel to fit new orientation.
        val wasShowing = panelShowing
        try { panelView?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        panelShowing = false
        buildPanel()
        if (wasShowing) togglePanel()
        // the screen size changed -> tell the mod and resize the ESP layer
        try {
            val (w, h) = screenSize()
            writeExternalFlagInt("screenw", w)
            writeExternalFlagInt("screenh", h)
            espOverlayView?.let { v ->
                v.layoutParams?.width = w
                v.layoutParams?.height = h
                (v.layoutParams as? WindowManager.LayoutParams)?.let { windowManager.updateViewLayout(v, it) }
                v.invalidate()
            }
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.debug_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DebugOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_debug)
            .setContentTitle(getString(R.string.debug_notif_title))
            .setContentText(getString(R.string.debug_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.debug_stop), stopIntent)
            .build()

        startForeground(NOTIF_ID, notification)
    }
}
