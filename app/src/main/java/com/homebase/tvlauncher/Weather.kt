package com.homebase.tvlauncher

import android.app.Activity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Current weather from Open-Meteo (free, no API key, no account).
 * Refreshed at most every 30 minutes; the last reading is cached so the
 * home screen shows something instantly after a reboot.
 */
object Weather {

    private const val REFRESH_MS = 30L * 60 * 1000

    data class Now(val tempC: Int, val code: Int, val isDay: Boolean)

    fun describe(now: Now): Pair<String, String> {
        val (icon, text) = when (now.code) {
            0 -> (if (now.isDay) "☀️" else "🌙") to "Clear"
            1 -> (if (now.isDay) "🌤️" else "🌙") to "Mostly clear"
            2 -> "⛅" to "Partly cloudy"
            3 -> "☁️" to "Cloudy"
            45, 48 -> "🌫️" to "Fog"
            51, 53, 55, 56, 57 -> "🌦️" to "Drizzle"
            61, 63, 66 -> "🌧️" to "Rain"
            65, 67 -> "🌧️" to "Heavy rain"
            71, 73, 75, 77 -> "🌨️" to "Snow"
            80, 81 -> "🌦️" to "Showers"
            82 -> "⛈️" to "Heavy showers"
            85, 86 -> "🌨️" to "Snow showers"
            95, 96, 99 -> "⛈️" to "Thunderstorm"
            else -> "🌡️" to ""
        }
        return icon to text
    }

    /** Shows cached weather right away, then refreshes in the background if stale. */
    fun load(act: Activity, force: Boolean = false, onResult: (Now?, String) -> Unit) {
        val prefs = Prefs(act)
        cached(prefs)?.let { onResult(it, prefs.weatherCity) }
        val stale = System.currentTimeMillis() - prefs.weatherFetchedAt > REFRESH_MS
        if (!force && !stale) return
        thread(name = "weather") {
            val now = runCatching { fetch(prefs.weatherLat, prefs.weatherLon) }.getOrNull()
            if (now != null) {
                prefs.weatherCache = "${now.tempC},${now.code},${if (now.isDay) 1 else 0}"
                prefs.weatherFetchedAt = System.currentTimeMillis()
            }
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) onResult(now ?: cached(prefs), prefs.weatherCity)
            }
        }
    }

    /** Looks up a city name. Returns (display name, lat, lon) or null. Call off the UI thread. */
    fun geocode(city: String): Triple<String, Double, Double>? {
        val q = URLEncoder.encode(city.trim(), "UTF-8")
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
        val r = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Triple(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    private fun cached(prefs: Prefs): Now? {
        val parts = prefs.weatherCache.split(',')
        if (parts.size != 3) return null
        return runCatching { Now(parts[0].toInt(), parts[1].toInt(), parts[2] == "1") }.getOrNull()
    }

    private fun fetch(lat: Double, lon: Double): Now {
        val json = JSONObject(
            get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&timezone=auto")
        ).getJSONObject("current")
        return Now(
            tempC = json.getDouble("temperature_2m").roundToInt(),
            code = json.getInt("weather_code"),
            isDay = json.optInt("is_day", 1) == 1
        )
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "HomeBase-TV-Launcher")
        return try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
