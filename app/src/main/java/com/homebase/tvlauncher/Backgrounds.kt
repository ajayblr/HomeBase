package com.homebase.tvlauncher

import android.app.Activity
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Background styles the user can pick in Launcher options. */
enum class BackgroundMode(val labelRes: Int) {
    AURORA(R.string.bg_aurora),
    BING(R.string.bg_bing),
    PHOTOS(R.string.bg_photos)
}

/**
 * Switches between the animated aurora and a photo slideshow, and fetches the
 * photo lists. Lists are cached so the background appears instantly and the
 * TV only checks for new photos every few hours.
 */
class Backgrounds(
    private val act: Activity,
    private val aurora: AuroraView,
    private val photos: PhotoWall
) {
    private val prefs = Prefs(act)

    fun start() {
        val mode = prefs.backgroundMode
        if (mode == BackgroundMode.AURORA) {
            showAurora()
            return
        }
        val cached = prefs.photoList(mode)
        if (cached.isNotEmpty()) showPhotos(cached) else showAurora()

        val stale = System.currentTimeMillis() - prefs.photoListFetchedAt(mode) > REFRESH_MS
        if (cached.isEmpty() || stale) {
            thread(name = "wallpaper-list") {
                val fresh = runCatching { if (mode == BackgroundMode.BING) bing() else ownPhotos() }.getOrNull()
                if (fresh != null) prefs.setPhotoList(mode, fresh)
                act.runOnUiThread {
                    if (act.isFinishing || act.isDestroyed || prefs.backgroundMode != mode) return@runOnUiThread
                    if (!fresh.isNullOrEmpty()) showPhotos(fresh)
                }
            }
        }
    }

    fun stop() {
        aurora.stop()
        photos.stop()
    }

    private fun showAurora() {
        photos.stop()
        photos.visibility = View.GONE
        aurora.visibility = View.VISIBLE
        aurora.start()
    }

    private fun showPhotos(urls: List<String>) {
        aurora.stop()
        aurora.visibility = View.GONE
        photos.visibility = View.VISIBLE
        photos.start(urls, prefs.slideshowMinutes * 60_000L)
    }

    /** Bing's photo of the day for the last 8 days, in full HD. */
    private fun bing(): List<String> {
        val json = JSONObject(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-IE"))
        val images = json.getJSONArray("images")
        return (0 until images.length()).map { i ->
            val base = images.getJSONObject(i).getString("urlbase")
            "https://www.bing.com${base}_1920x1080.jpg"
        }
    }

    /** Your own photos: the wallpapers/ folder in the GitHub repo, listed in wallpapers.json by the build. */
    private fun ownPhotos(): List<String> {
        val update = prefs.updateUrl
        if (update.isBlank()) return emptyList()
        val listUrl = update.substringBeforeLast('/') + "/wallpapers.json"
        val arr = JSONArray(get(listUrl))
        return (0 until arr.length()).map { arr.getString(it) }.shuffled()
    }

    private fun get(url: String): String {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "HomeBase-TV-Launcher")
            when (val code = conn.responseCode) {
                in 200..299 -> return conn.inputStream.bufferedReader().use { it.readText() }
                301, 302, 303, 307, 308 -> {
                    current = URL(URL(current), conn.getHeaderField("Location")).toString()
                    conn.disconnect()
                }
                else -> throw java.io.IOException("HTTP $code")
            }
        }
        throw java.io.IOException("Too many redirects")
    }

    companion object {
        private const val REFRESH_MS = 6L * 60 * 60 * 1000
    }
}
