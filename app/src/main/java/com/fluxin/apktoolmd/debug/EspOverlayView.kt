package com.fluxin.apktoolmd.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.io.File

/**
 * Floating ESP renderer.
 *
 * The mod (the dumper .so) writes one line per visible enemy to
 * /sdcard/aimbot_esp.txt:
 *
 *   addr hp maxhp distM team onScreen npoints x1 y1 x2 y2 ... name
 *
 * The points are the projected body points:
 *   0 head  1 neck  2 chest  3 pelvis  4 lfoot  5 rfoot
 *   6 lhand 7 rhand 8 box-left 9 box-right
 *
 * Everything is drawn on the overlay window; the switches live in the
 * "External" tab of the debug panel.
 */
class EspOverlayView(context: Context) : View(context) {

    class Frame {
        var addr: String = ""
        var hp: Int = 0
        var maxHp: Int = 0
        var distM: Float = 0f
        var team: Int = 0
        var onScreen: Boolean = false
        var realBones: Boolean = false
        var name: String = ""
        var worldX: Float = 0f
        var worldZ: Float = 0f
        val pts = FloatArray(32)
        var npts: Int = 0
    }

    // options (toggled from the overlay panel)
    var showBox = true
    var showSkeleton = true
    var showHealth = true
    var showNames = true
    var showDistance = true
    var showTracers = false
    var onlyEnemies = true
    var maxDistM = 120
    var showRadar = true

    // The player position (for the radar) - written by the mod.
    @Volatile
    var playerX = 0f
    @Volatile
    var playerZ = 0f
    @Volatile
    var playerYaw = 0f
    @Volatile
    var havePlayer = false

    private val frames = ArrayList<Frame>()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        color = Color.parseColor("#FF4B4B")
    }
    private val skelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = Color.parseColor("#00FF66")
    }
    private val healthBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66000000")
    }
    private val healthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val distPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD24B")
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = Color.parseColor("#66FF66")
    }
    private val radarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.parseColor("#6644FF88")
    }
    private val radarDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF4B4B")
    }
    private val radarPlayerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66FF66")
    }

    /** Read the mod's ESP frame file.  Cheap: a few lines, parsed in place. */
    fun loadFrom(file: File) {
        val parsed = ArrayList<Frame>()
        try {
            if (!file.exists()) {
                frames.clear()
                invalidate()
                return
            }
            file.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val p = line.split(' ')
                if (p.size < 8) return@forEach
                if (p[0] == "P" && p.size >= 4) {
                    // the player position (radar)
                    playerX = p[1].toFloatOrNull() ?: 0f
                    playerZ = p[2].toFloatOrNull() ?: 0f
                    playerYaw = p[3].toFloatOrNull() ?: 0f
                    havePlayer = true
                    return@forEach
                }
                val fr = Frame()
                fr.addr = p[0]
                fr.hp = p[1].toIntOrNull() ?: 0
                fr.maxHp = p[2].toIntOrNull() ?: 0
                fr.distM = p[3].toFloatOrNull() ?: 0f
                fr.team = p[4].toIntOrNull() ?: 0
                fr.onScreen = (p[5].toIntOrNull() ?: 0) != 0
                fr.realBones = (p[6].toIntOrNull() ?: 0) != 0
                val n = (p[7].toIntOrNull() ?: 0).coerceIn(0, 16)
                fr.npts = n
                fr.worldX = p[8].toFloatOrNull() ?: 0f
                fr.worldZ = p[9].toFloatOrNull() ?: 0f
                var i = 10
                for (k in 0 until n) {
                    if (i + 1 >= p.size) break
                    fr.pts[k * 2] = p[i].toFloatOrNull() ?: 0f
                    fr.pts[k * 2 + 1] = p[i + 1].toFloatOrNull() ?: 0f
                    i += 2
                }
                // the rest is the name (it may contain spaces)
                fr.name = if (i < p.size) p.subList(i, p.size).joinToString(" ") else ""
                parsed.add(fr)
            }
        } catch (_: Throwable) {
        }
        frames.clear()
        frames.addAll(parsed)
        invalidate()
    }

    private fun boneLine(canvas: Canvas, fr: Frame, a: Int, b: Int) {
        canvas.drawLine(fr.pts[a * 2], fr.pts[a * 2 + 1], fr.pts[b * 2], fr.pts[b * 2 + 1], skelPaint)
    }

    /**
     * A radar around the player.  The aimbot points the camera at the target,
     * so everything else is off screen - the radar shows all of them.
     */
    private fun drawRadar(canvas: Canvas) {
        val r = 110f
        val cx = width * 0.5f
        val cy = height * 0.5f
        canvas.drawCircle(cx, cy, r, radarRingPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, radarRingPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, radarRingPaint)

        val range = (maxDistM * 100f).coerceAtLeast(100f)
        val yawRad = Math.toRadians((playerYaw - 90f).toDouble())
        val cos = Math.cos(yawRad).toFloat()
        val sin = Math.sin(yawRad).toFloat()

        for (fr in frames) {
            val dx = fr.worldX - playerX
            val dz = fr.worldZ - playerZ
            val d = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
            if (d > range) continue
            // rotate into the player's frame and flip Z (the world's Z is up)
            val rx = dx * cos + dz * sin
            val rz = -dx * sin + dz * cos
            val px = cx + (rx / range) * r
            val py = cy - (rz / range) * r
            radarDotPaint.color = if (fr.hp > 0) Color.parseColor("#FF4B4B") else Color.parseColor("#888888")
            canvas.drawCircle(px, py, 4f, radarDotPaint)
        }
        // the player
        canvas.drawCircle(cx, cy, 5f, radarPlayerPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showRadar && havePlayer) drawRadar(canvas)
        if (frames.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        for (fr in frames) {
            if (!fr.onScreen) continue
            if (fr.distM > maxDistM) continue
            if (fr.npts < 8) continue

            val hx = fr.pts[0]
            val hy = fr.pts[1]
            // feet: real bones -> 14, capsule fallback -> 4
            val footL = if (fr.realBones) 14 else 4
            val fx = fr.pts[footL * 2]
            val fy = fr.pts[footL * 2 + 1]

            if (showTracers) {
                canvas.drawLine(w / 2f, h, hx, hy, tracerPaint)
            }

            if (showBox) {
                val left = minOf(hx, fx) - 8f
                val right = maxOf(hx, fx) + 8f
                val top = minOf(hy, fy)
                val bottom = maxOf(hy, fy)
                canvas.drawRect(left, top, right, bottom, boxPaint)
            }

            if (showSkeleton) {
                if (fr.realBones && fr.npts >= 16) {
                    // real bones: head, neck, chest, pelvis, clav_l, clav_r,
                    // uparm_l, uparm_r, hand_l, hand_r, thigh_l, thigh_r,
                    // calf_l, calf_r, foot_l, foot_r
                    boneLine(canvas, fr, 0, 1)
                    boneLine(canvas, fr, 1, 2)
                    boneLine(canvas, fr, 2, 3)
                    boneLine(canvas, fr, 2, 4)
                    boneLine(canvas, fr, 2, 5)
                    boneLine(canvas, fr, 4, 6)
                    boneLine(canvas, fr, 6, 8)
                    boneLine(canvas, fr, 5, 7)
                    boneLine(canvas, fr, 7, 9)
                    boneLine(canvas, fr, 3, 10)
                    boneLine(canvas, fr, 10, 12)
                    boneLine(canvas, fr, 12, 14)
                    boneLine(canvas, fr, 3, 11)
                    boneLine(canvas, fr, 11, 13)
                    boneLine(canvas, fr, 13, 15)
                    canvas.drawCircle(hx, hy, 4f, skelPaint)
                } else {
                    // capsule fallback: head, neck, chest, pelvis, lfoot,
                    // rfoot, lhand, rhand
                    val nkX = fr.pts[2]; val nkY = fr.pts[3]
                    val cX = fr.pts[4]; val cY = fr.pts[5]
                    val pX = fr.pts[6]; val pY = fr.pts[7]
                    val lfX = fr.pts[8]; val lfY = fr.pts[9]
                    val rfX = fr.pts[10]; val rfY = fr.pts[11]
                    val lhX = fr.pts[12]; val lhY = fr.pts[13]
                    val rhX = fr.pts[14]; val rhY = fr.pts[15]
                    canvas.drawLine(hx, hy, nkX, nkY, skelPaint)
                    canvas.drawLine(nkX, nkY, cX, cY, skelPaint)
                    canvas.drawLine(cX, cY, pX, pY, skelPaint)
                    canvas.drawLine(cX, cY, lhX, lhY, skelPaint)
                    canvas.drawLine(cX, cY, rhX, rhY, skelPaint)
                    canvas.drawLine(pX, pY, lfX, lfY, skelPaint)
                    canvas.drawLine(pX, pY, rfX, rfY, skelPaint)
                    canvas.drawCircle(hx, hy, 4f, skelPaint)
                }
            }

            if (showHealth && fr.maxHp > 0) {
                val ratio = (fr.hp.toFloat() / fr.maxHp.toFloat()).coerceIn(0f, 1f)
                val bw = 6f
                val bh = (fy - hy).coerceAtLeast(10f)
                val x = minOf(fx, hx) - bw - 4f
                val y = hy
                canvas.drawRect(x, y, x + bw, y + bh, healthBgPaint)
                healthPaint.color = when {
                    ratio > 0.5f -> Color.parseColor("#00FF66")
                    ratio > 0.25f -> Color.parseColor("#FFD24B")
                    else -> Color.parseColor("#FF4B4B")
                }
                canvas.drawRect(x, y + bh * (1f - ratio), x + bw, y + bh, healthPaint)
            }

            if (showNames && fr.name.isNotEmpty()) {
                canvas.drawText(fr.name, hx + 6f, hy - 6f, textPaint)
            }
            if (showDistance) {
                canvas.drawText("${fr.distM.toInt()}m", hx + 6f, hy + 16f, distPaint)
            }
        }
    }
}
