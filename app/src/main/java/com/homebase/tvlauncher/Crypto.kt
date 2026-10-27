package com.homebase.tvlauncher

import android.app.Activity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Crypto prices in euro from CoinGecko's free public API (no key, no account).
 * Refreshed at most every 5 minutes; the last prices are cached so they show
 * instantly when the home screen opens.
 */
object Crypto {

    private const val REFRESH_MS = 5L * 60 * 1000

    /** Shown left to right. CoinGecko id -> ticker symbol. */
    val COINS = linkedMapOf("cardano" to "ADA", "bitcoin" to "BTC")

    data class Quote(val symbol: String, val eur: Double, val change24h: Double?)

    fun load(act: Activity, onResult: (List<Quote>?) -> Unit) {
        val prefs = Prefs(act)
        parse(prefs.cryptoCache)?.let { onResult(it) }
        if (System.currentTimeMillis() - prefs.cryptoFetchedAt < REFRESH_MS) return
        prefs.cryptoFetchedAt = System.currentTimeMillis() // don't pile up requests if offline
        thread(name = "crypto") {
            val raw = runCatching { fetch() }.getOrNull()
            val parsed = raw?.let { parse(it) }
            if (parsed != null) prefs.cryptoCache = raw
            act.runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) onResult(parsed ?: parse(prefs.cryptoCache))
            }
        }
    }

    private fun fetch(): String {
        val ids = COINS.keys.joinToString(",")
        val conn = URL("https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=eur&include_24hr_change=true")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "HomeBase-TV-Launcher")
        return try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): List<Quote>? = runCatching {
        if (raw.isBlank()) return null
        val json = JSONObject(raw)
        COINS.mapNotNull { (id, symbol) ->
            val o = json.optJSONObject(id) ?: return@mapNotNull null
            val change = o.optDouble("eur_24h_change")
            Quote(symbol, o.getDouble("eur"), change.takeUnless { it.isNaN() })
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
