package com.homebase.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * "Rain on a window" background.
 *
 * Behind the glass: softly blurred lights (painted into a tiny bitmap and
 * stretched, so the blur costs nothing). On the glass: water droplets that land,
 * sit, and every few seconds one gets heavy enough to run down, swallowing the
 * droplets in its path and leaving a trail of tiny beads.
 *
 * Every droplet is the same pre-rendered sprite drawn at different sizes, so a
 * frame is a few hundred GPU bitmap draws - cheap even on a 2 GB TV. It only
 * animates while HomeBase is on screen.
 */
class RainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Drop(
        var x: Float,
        var y: Float,
        var r: Float,
        var vy: Float = 0f,
        var sliding: Boolean = false,
        var wobble: Float = Random.nextFloat() * 6.28f,
        var lastTrailY: Float = 0f
    )

    // ---- blurred lights behind the glass ----
    private val lowW = 160
    private val lowH = 90
    private val backdrop: Bitmap = Bitmap.createBitmap(lowW, lowH, Bitmap.Config.ARGB_8888)
    private val backdropCanvas = Canvas(backdrop)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint()
    private val full = Rect()

    // ---- droplets on the glass ----
    private val sprite: Bitmap = makeDropSprite(64)
    private val spriteSrc = Rect(0, 0, sprite.width, sprite.height)
    private val dropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val drops = ArrayList<Drop>(400)
    private val density = resources.displayMetrics.density

    private var running = false
    private var lastFrame = 0L
    private var nextSlide = 0L
    private val start = SystemClock.elapsedRealtime()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 40) // 25 fps
        }
    }

    fun start() {
        if (running) return
        running = true
        lastFrame = SystemClock.elapsedRealtime()
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
        full.set(0, 0, w, h)
        shadePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(30, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        drops.clear()
        // Start with a window that's already been rained on
        repeat(MAX_DROPS * 3 / 4) { drops += randomDrop(w, h) }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
        lastFrame = now
        val t = (now - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()

        drawBackdrop(canvas, t)
        if (running) simulate(dt, now, w, h)

        for (d in drops) {
            // Running drops stretch into a tear shape
            val stretch = if (d.sliding) 1.25f else 1.08f
            dst.set(d.x - d.r, d.y - d.r * stretch, d.x + d.r, d.y + d.r * stretch)
            canvas.drawBitmap(sprite, spriteSrc, dst, dropPaint)
        }

        canvas.drawRect(full, shadePaint)
    }

    private fun drawBackdrop(canvas: Canvas, t: Float) {
        val night = isNight()
        backdropCanvas.drawColor(if (night) Color.rgb(0x06, 0x0B, 0x16) else Color.rgb(0x26, 0x36, 0x48))
        val colors = if (night) NIGHT_LIGHTS else DAY_LIGHTS
        colors.forEachIndexed { i, c ->
            val sp = 0.02f + i * 0.006f
            val cx = lowW * (0.5f + 0.45f * sin(t * sp + i * 1.9f))
            val cy = lowH * (0.5f + 0.38f * cos(t * sp * 0.7f + i * 2.7f))
            val r = lowW * (0.16f + 0.05f * ((i * 37) % 5))
            blobPaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(withAlpha(c, 190), withAlpha(c, 70), withAlpha(c, 0)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            backdropCanvas.drawCircle(cx, cy, r, blobPaint)
        }
        canvas.drawBitmap(backdrop, null, full, scalePaint)
    }

    private fun simulate(dt: Float, now: Long, w: Float, h: Float) {
        // New droplets landing
        val landings = if (drops.size < MAX_DROPS) 3 else 0
        repeat(landings) { if (Random.nextFloat() < 0.35f) drops += randomDrop(w.toInt(), h.toInt()) }

        // Every few seconds a bigger droplet starts to run
        if (now > nextSlide) {
            nextSlide = now + 1500 + Random.nextLong(3500)
            drops.filter { !it.sliding && it.r > 7 * density }.randomOrNull()?.let {
                it.sliding = true
                it.vy = 20f * density
                it.lastTrailY = it.y
            }
        }

        val removed = HashSet<Drop>()
        val trail = ArrayList<Drop>()
        for (d in drops) {
            if (!d.sliding || d in removed) continue
            // Heavier drops run faster; a little side-to-side wobble like real water
            d.vy = (d.vy + 120f * density * dt).coerceAtMost(260f * density * (d.r / (12 * density)).coerceIn(0.6f, 1.6f))
            d.y += d.vy * dt
            d.wobble += dt * 3f
            d.x += sin(d.wobble) * 8f * density * dt

            // Swallow droplets in the path and grow
            for (o in drops) {
                if (o === d || o in removed || o.sliding) continue
                if (hypot(o.x - d.x, o.y - d.y) < d.r + o.r * 0.6f) {
                    removed += o
                    d.r = sqrt(d.r * d.r + o.r * o.r * 0.6f).coerceAtMost(18f * density)
                }
            }

            // Leave a trail of tiny beads, losing a little water each time
            if (d.y - d.lastTrailY > 10f * density) {
                d.lastTrailY = d.y
                if (Random.nextFloat() < 0.7f) {
                    val bead = (1.2f + Random.nextFloat() * 1.8f) * density
                    trail += Drop(d.x + (Random.nextFloat() - 0.5f) * 3 * density, d.y - d.r * 1.4f, bead)
                    d.r = (d.r - bead * 0.08f).coerceAtLeast(4f * density)
                }
            }
            if (d.y - d.r > h) removed += d
        }
        if (removed.isNotEmpty()) drops.removeAll(removed)
        drops.addAll(trail)
        // Keep the count bounded: oldest tiny beads evaporate first
        while (drops.size > MAX_DROPS + 60) {
            val i = drops.indexOfFirst { !it.sliding }
            if (i < 0) break
            drops.removeAt(i)
        }
    }

    private fun randomDrop(w: Int, h: Int): Drop {
        // Mostly small droplets, a few big ones
        val roll = Random.nextFloat()
        val r = when {
            roll < 0.65f -> 1.5f + Random.nextFloat() * 2.5f
            roll < 0.92f -> 4f + Random.nextFloat() * 4f
            else -> 8f + Random.nextFloat() * 5f
        } * density
        return Drop(Random.nextFloat() * w, Random.nextFloat() * h, r)
    }

    private fun isNight(): Boolean {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hr < 7 || hr >= 18
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        private const val MAX_DROPS = 300

        // City lights through a rainy window at night
        private val NIGHT_LIGHTS = intArrayOf(
            Color.rgb(0xFF, 0xB3, 0x47), Color.rgb(0x2E, 0xC4, 0xB6), Color.rgb(0xE8, 0x6A, 0x92),
            Color.rgb(0x4D, 0x7C, 0xFE), Color.rgb(0xFF, 0xD8, 0x8A), Color.rgb(0x6A, 0x5A, 0xCD)
        )

        // Soft grey-blue daylight with a few warm reflections
        private val DAY_LIGHTS = intArrayOf(
            Color.rgb(0x9F, 0xB8, 0xD0), Color.rgb(0xD8, 0xE4, 0xEE), Color.rgb(0x6F, 0x8F, 0xB0),
            Color.rgb(0xF2, 0xD4, 0xA8), Color.rgb(0x8C, 0xC5, 0xC8), Color.rgb(0xB8, 0xC6, 0xE6)
        )

        /**
         * One water droplet as seen on glass: a dark rim and shadow, light
         * gathered in the lower half (water flips the scene behind it), and a
         * small bright highlight up top.
         */
        private fun makeDropSprite(radius: Int): Bitmap {
            val size = radius * 2 + 8
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val r = radius.toFloat()
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // Soft shadow under the drop
            p.shader = RadialGradient(cx, cy + r * 0.18f, r * 1.05f,
                intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy + r * 0.18f, r * 1.05f, p)

            // Body: darker top, brighter bottom
            p.shader = LinearGradient(cx, cy - r, cx, cy + r,
                intArrayOf(Color.argb(95, 10, 20, 35), Color.argb(35, 200, 220, 240), Color.argb(120, 235, 245, 255)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)

            // Rim
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.08f
            p.color = Color.argb(80, 0, 0, 0)
            c.drawCircle(cx, cy, r * 0.96f, p)

            // Lower inner glow (light refracted through the drop)
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(cx, cy + r * 0.45f, r * 0.6f,
                intArrayOf(Color.argb(130, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy + r * 0.45f, r * 0.6f, p)

            // Specular highlight
            p.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.42f, r * 0.26f,
                intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx - r * 0.32f, cy - r * 0.42f, r * 0.26f, p)
            return bmp
        }
    }
}
