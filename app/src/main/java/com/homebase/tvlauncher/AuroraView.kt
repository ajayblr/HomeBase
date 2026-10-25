package com.homebase.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slowly drifting "aurora" background whose colours follow the time of day.
 *
 * Built to be cheap on a 2 GB TV: the glow is painted into a tiny 192x108 bitmap
 * about 20 times a second and stretched to full screen with filtering, which also
 * gives the soft, blurred look for free. It stops animating while HomeBase is
 * hidden (an app is open), so it costs nothing in the background.
 */
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Phase { MORNING, AFTERNOON, EVENING, NIGHT }

    private data class Palette(val base: Int, val blobs: IntArray, val stars: Boolean)

    private val palettes = mapOf(
        // Sunrise: warm peach and rose over deep plum
        Phase.MORNING to Palette(
            Color.rgb(0x1F, 0x12, 0x2B),
            intArrayOf(Color.rgb(0xFF, 0x8A, 0x5B), Color.rgb(0xF2, 0x5F, 0x9A), Color.rgb(0xFF, 0xC8, 0x6B), Color.rgb(0x7A, 0x5C, 0xFF)),
            stars = false
        ),
        // Daylight: teal, sky blue and a touch of mint
        Phase.AFTERNOON to Palette(
            Color.rgb(0x08, 0x1B, 0x2E),
            intArrayOf(Color.rgb(0x1E, 0xA7, 0xFF), Color.rgb(0x16, 0xD9, 0xB5), Color.rgb(0x4F, 0x6B, 0xFF), Color.rgb(0x8E, 0xF0, 0xFF)),
            stars = false
        ),
        // Sunset: magenta, violet and ember orange
        Phase.EVENING to Palette(
            Color.rgb(0x16, 0x0B, 0x24),
            intArrayOf(Color.rgb(0xC0, 0x3B, 0xFF), Color.rgb(0xFF, 0x4D, 0x8D), Color.rgb(0xFF, 0x8C, 0x3A), Color.rgb(0x5B, 0x3B, 0xFF)),
            stars = false
        ),
        // Night: northern-lights green and indigo with stars
        Phase.NIGHT to Palette(
            Color.rgb(0x04, 0x07, 0x14),
            intArrayOf(Color.rgb(0x1D, 0xE9, 0x9B), Color.rgb(0x3A, 0x4B, 0xFF), Color.rgb(0x00, 0xB4, 0xD8), Color.rgb(0x8A, 0x2B, 0xE2)),
            stars = true
        )
    )

    private val lowW = 192
    private val lowH = 108
    private val buffer: Bitmap = Bitmap.createBitmap(lowW, lowH, Bitmap.Config.ARGB_8888)
    private val bufferCanvas = Canvas(buffer)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val vignettePaint = Paint()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()

    // Fixed star field (x, y in 0..1, size, twinkle phase)
    private val stars = Array(70) { i ->
        val r = java.util.Random(i * 7919L)
        floatArrayOf(r.nextFloat(), r.nextFloat() * 0.75f, 0.8f + r.nextFloat() * 1.6f, r.nextFloat() * 6.28f)
    }

    private var palette = palettes.getValue(currentPhase())
    private var running = false
    private val startTime = SystemClock.elapsedRealtime()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 50) // ~20 fps is plenty for slow drifting colour
        }
    }

    fun start() {
        palette = palettes.getValue(currentPhase())
        if (running) return
        running = true
        post(tick)
    }

    fun stop() {
        running = false
        removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dst.set(0, 0, w, h)
        // Darken the bottom so app tiles and text stay readable
        vignettePaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(40, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.argb(170, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.elapsedRealtime() - startTime) / 1000f
        val p = palette

        bufferCanvas.drawColor(p.base)
        p.blobs.forEachIndexed { i, color ->
            // Each blob drifts on its own slow Lissajous path
            val sp = 0.035f + i * 0.012f
            val cx = lowW * (0.5f + 0.42f * sin(t * sp + i * 1.7f))
            val cy = lowH * (0.42f + 0.35f * cos(t * sp * 0.8f + i * 2.3f))
            val radius = lowW * (0.42f + 0.08f * sin(t * 0.05f + i))
            blobPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(withAlpha(color, 150), withAlpha(color, 60), withAlpha(color, 0)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            bufferCanvas.drawCircle(cx, cy, radius, blobPaint)
        }

        canvas.drawBitmap(buffer, null, dst, scalePaint)

        if (p.stars) {
            val w = width.toFloat()
            val h = height.toFloat()
            for (s in stars) {
                val twinkle = 0.45f + 0.55f * ((sin(t * 1.3f + s[3]) + 1f) / 2f)
                starPaint.color = Color.argb((twinkle * 200).toInt(), 255, 255, 255)
                canvas.drawCircle(s[0] * w, s[1] * h, s[2], starPaint)
            }
        }

        canvas.drawRect(dst, vignettePaint)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        fun currentPhase(cal: Calendar = Calendar.getInstance()): Phase =
            when (cal.get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> Phase.MORNING
                in 12..16 -> Phase.AFTERNOON
                in 17..20 -> Phase.EVENING
                else -> Phase.NIGHT
            }
    }
}
