package com.homebase.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Photo slideshow background: cross-fades between photos with a slow
 * "Ken Burns" zoom. Photos are downloaded once, cached on the TV, and decoded
 * at screen size in a compact format so two photos use only ~8 MB of memory.
 */
class PhotoWall @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val a = ImageView(context)
    private val b = ImageView(context)
    private var front = a
    private var urls: List<String> = emptyList()
    private var index = 0
    private var intervalMs = 5 * 60_000L
    private var running = false
    private var loading = false
    private val cacheDir = File(context.cacheDir, "wallpapers").apply { mkdirs() }

    private val next = object : Runnable {
        override fun run() {
            if (!running) return
            showNext()
            postDelayed(this, intervalMs)
        }
    }

    init {
        for (iv in listOf(a, b)) {
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.alpha = 0f
            addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        // Darken towards the bottom so tiles and text stay readable on bright photos
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x33000000, 0x99000000.toInt(), 0xDD000000.toInt())
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun start(photoUrls: List<String>, everyMs: Long) {
        val changed = photoUrls != urls
        urls = photoUrls
        intervalMs = everyMs.coerceAtLeast(30_000L)
        if (urls.isEmpty()) return
        if (changed) index = 0
        running = true
        removeCallbacks(next)
        // Show something immediately the first time, then keep to the interval
        if (changed || front.drawable == null) post(next) else {
            postDelayed(next, intervalMs)
            resumeZoom()
        }
    }

    fun stop() {
        running = false
        removeCallbacks(next)
        a.animate().cancel()
        b.animate().cancel()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun showNext() {
        if (loading || urls.isEmpty()) return
        val url = urls[index % urls.size]
        index++
        loading = true
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        thread(name = "wallpaper") {
            val bmp = runCatching { decode(fetch(url), w, h) }.getOrNull()
            post {
                loading = false
                if (bmp != null && running) crossfadeTo(bmp)
            }
        }
    }

    private fun crossfadeTo(bmp: Bitmap) {
        val back = if (front === a) b else a
        back.animate().cancel()
        back.setImageBitmap(bmp)
        back.alpha = 0f
        back.scaleX = 1f
        back.scaleY = 1f
        back.translationX = 0f
        back.bringToFront()
        bringOverlayToFront() // keep the readability overlay on top

        back.animate().alpha(1f).setDuration(1500).withEndAction {
            front.setImageDrawable(null) // free the old photo
            front.alpha = 0f
            front = back
            zoom(back)
        }.start()
    }

    private fun zoom(iv: ImageView) {
        val dx = if (index % 2 == 0) 24f else -24f
        iv.animate().scaleX(1.10f).scaleY(1.10f).translationX(dx * resources.displayMetrics.density)
            .setDuration(intervalMs + 2000).setInterpolator(null).start()
    }

    private fun resumeZoom() {
        if (front.drawable != null) zoom(front)
    }

    private fun bringOverlayToFront() {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c !== a && c !== b) {
                c.bringToFront()
                return
            }
        }
    }

    private fun fetch(url: String): File {
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".img"
        val file = File(cacheDir, name)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "HomeBase-TV-Launcher")
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val tmp = File(cacheDir, "$name.part")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(file)
                    trimCache()
                    return file
                }
                301, 302, 303, 307, 308 -> {
                    current = URL(URL(current), conn.getHeaderField("Location")).toString()
                    conn.disconnect()
                }
                else -> throw java.io.IOException("HTTP $code")
            }
        }
        throw java.io.IOException("Too many redirects")
    }

    /** Keep at most 40 photos (~20-40 MB) on the TV. */
    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".img") }?.sortedBy { it.lastModified() } ?: return
        files.dropLast(40).forEach { it.delete() }
    }

    private fun decode(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }
}
