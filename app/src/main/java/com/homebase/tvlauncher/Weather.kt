package com.homebase.tvlauncher

import android.app.Activity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Weather from Open-Meteo (free, no API key, no account): current conditions,
 * today's high/low and the next few hours. Refreshed at most every 30 minutes;
 * the last response is cached so the home screen shows it instantly after a reboot.
 */
object Weather {

    private const val REFRESH_MS = 30L * 60 * 1000

    data class Hour(val label: String, val tempC: Int, val code: Int, val isDay: Boolean)

    data class Forecast(
        val tempC: Int,
        val code: Int,
        val isDay: Boolean,
        val highC: Int?,
        val lowC: Int?,
        val rainChance: Int?,
        val hours: List<Hour>
    )

    fun icon(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "☀️" else "🌙"
        1 -> if (isDay) "🌤️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 66, 65, 67 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "🌨️"
        80, 81 -> "🌦️"
        82, 95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    fun text(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 66 -> "Rain"
        65, 67 -> "Heavy rain"
        71, 73, 75, 77 -> "Snow"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> ""
    }

    /** Shows cached weather right away, then refreshes in the background if stale. */
    fun load(act: Activity, force: Boolean = false, onResult: (Forecast?, String) -> Unit) {
        val prefs = Prefs(act)
        parse(prefs.weatherCache)?.let { onResult(it, prefs.weatherCity) }
        val stale = System.currentTimeMillis() - prefs.weatherFetchedAt > REFRESH_MS
        if (!force && !stale) return
        thread(name = "weather") {
            val raw = runCatching { fetch(prefs.weatherLat, prefs.weatherLon) }.getOrNull()
            val parsed = raw?.let { parse(it) }
            if (parsed != null) {
                prefs.weatherCache = raw
                prefs.weatherFetchedAt = System.currentTimeMillis()
            }
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) onResult(parsed ?: parse(prefs.weatherCache), prefs.weatherCity)
            }
        }
    }

    /** Looks up a city name. Returns (display name, lat, lon) or null. Call off the UI thread. */
    fun geocode(city: String): Triple<String, Double, Double>? {
        val q = URLEncoder.encode(city.trim(), "UTF-8")
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
        val r = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val area = r.optString("admin2").ifBlank { r.optString("admin1") }
        val name = if (area.isNotBlank() && area != r.getString("name")) "${r.getString("name")}, $area" else r.getString("name")
        return Triple(name, r.getDouble("latitude"), r.getDouble("longitude"))
    }

    private fun fetch(lat: Double, lon: Double): String = get(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=2&timezone=auto"
    )

    private fun parse(raw: String): Forecast? = runCatching {
        if (raw.isBlank()) return null
        val json = JSONObject(raw)
        val cur = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        val hourly = json.optJSONObject("hourly")

        // Next 5 hours from "now" (times are local to the location, "yyyy-MM-ddTHH:mm")
        val hours = mutableListOf<Hour>()
        if (hourly != null) {
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            val days = hourly.optJSONArray("is_day")
            val nowKey = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Date())
            var startAt = (0 until times.length()).indexOfFirst { times.getString(it).startsWith(nowKey) }
            if (startAt < 0) startAt = 0
            for (i in (startAt + 1)..(startAt + 5)) {
                if (i >= times.length()) break
                val hh = times.getString(i).substringAfter('T').substringBefore(':')
                hours += Hour(hh, temps.getDouble(i).roundToInt(), codes.getInt(i), (days?.optInt(i, 1) ?: 1) == 1)
            }
        }

        Forecast(
            tempC = cur.getDouble("temperature_2m").roundToInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            highC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeUnless { it.isNaN() }?.roundToInt(),
            lowC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeUnless { it.isNaN() }?.roundToInt(),
            rainChance = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1)?.takeIf { it >= 0 },
            hours = hours
        )
    }.getOrNull()

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
