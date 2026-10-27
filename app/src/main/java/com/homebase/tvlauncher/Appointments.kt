package com.homebase.tvlauncher

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Upcoming appointments (next 30 days) from Subscription Savior.
 *
 * Subscription Savior runs on Google Firebase. HomeBase signs in the same way the
 * website does (Firebase Auth), then reads users/{uid}/appointments from Firestore.
 *
 * Privacy: the password is used once to sign in and is never stored. HomeBase keeps
 * only Firebase's refresh token, in the app's private storage on the TV. Nothing is
 * written to the HomeBase source code or GitHub. The site's public web API key is
 * looked up from the site itself at runtime.
 */
object Appointments {

    private const val SITE = "https://subscriptionsavior.com"
    private const val PROJECT = "studio-4704000177-63ef6"
    private const val REFRESH_MS = 15L * 60 * 1000
    private const val DAYS = 30

    data class Appt(
        val title: String,
        val start: Date,
        val allDay: Boolean,
        val location: String,
        val color: String?
    )

    class AuthException(msg: String) : IOException(msg)

    // ---------- public API ----------

    fun isSignedIn(ctx: Context) = Prefs(ctx).ssRefreshToken.isNotBlank()

    /** Signs in with email + password. Call off the UI thread. Returns null on success, or an error message. */
    fun signIn(ctx: Context, email: String, password: String): String? = try {
        val key = apiKey(ctx)
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("returnSecureToken", true)
        val res = JSONObject(post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$key",
            body.toString(), "application/json"
        ))
        val prefs = Prefs(ctx)
        prefs.ssEmail = email.trim()
        prefs.ssUid = res.getString("localId")
        prefs.ssRefreshToken = res.getString("refreshToken")
        prefs.ssIdToken = res.getString("idToken")
        prefs.ssIdTokenExpiry = System.currentTimeMillis() + (res.optString("expiresIn", "3600").toLong() - 120) * 1000
        prefs.ssCache = ""
        prefs.ssFetchedAt = 0L
        null
    } catch (e: AuthException) {
        friendlyAuthError(e.message ?: "")
    } catch (e: Exception) {
        "Couldn't reach Subscription Savior (${e.message ?: e.javaClass.simpleName})"
    }

    fun signOut(ctx: Context) {
        val p = Prefs(ctx)
        p.ssRefreshToken = ""
        p.ssIdToken = ""
        p.ssUid = ""
        p.ssCache = ""
        p.ssFetchedAt = 0L
    }

    /** Shows cached appointments right away, then refreshes in the background if stale. */
    fun load(act: Activity, force: Boolean = false, onResult: (List<Appt>?) -> Unit) {
        val prefs = Prefs(act)
        if (!isSignedIn(act)) {
            onResult(null)
            return
        }
        parse(prefs.ssCache)?.let { onResult(upcoming(it)) }
        val stale = System.currentTimeMillis() - prefs.ssFetchedAt > REFRESH_MS
        if (!force && !stale) return
        thread(name = "appointments") {
            val raw = runCatching { fetch(act) }
                .onFailure { if (it is AuthException) signOut(act) }
                .getOrNull()
            if (raw != null) {
                prefs.ssCache = raw
                prefs.ssFetchedAt = System.currentTimeMillis()
            }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (!isSignedIn(act)) onResult(null) else parse(prefs.ssCache)?.let { onResult(upcoming(it)) }
            }
        }
    }

    // ---------- Firestore ----------

    private fun fetch(ctx: Context): String {
        val token = idToken(ctx)
        val uid = Prefs(ctx).ssUid
        val from = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val to = (from.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, DAYS) }

        fun range(op: String, d: Date) = JSONObject().put("fieldFilter", JSONObject()
            .put("field", JSONObject().put("fieldPath", "start_at"))
            .put("op", op)
            .put("value", JSONObject().put("timestampValue", iso(d))))

        val query = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "appointments")))
            .put("where", JSONObject().put("compositeFilter", JSONObject()
                .put("op", "AND")
                .put("filters", JSONArray()
                    .put(range("GREATER_THAN_OR_EQUAL", from.time))
                    .put(range("LESS_THAN_OR_EQUAL", to.time)))))
            .put("orderBy", JSONArray().put(JSONObject()
                .put("field", JSONObject().put("fieldPath", "start_at"))
                .put("direction", "ASCENDING")))
            .put("limit", 20))

        val res = JSONArray(post(
            "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents/users/$uid:runQuery",
            query.toString(), "application/json", bearer = token
        ))
        // Keep only the fields we show, so the cache stays small
        val out = JSONArray()
        for (i in 0 until res.length()) {
            val f = res.getJSONObject(i).optJSONObject("document")?.optJSONObject("fields") ?: continue
            out.put(JSONObject()
                .put("title", f.optJSONObject("title")?.optString("stringValue").orEmpty())
                .put("start", f.optJSONObject("start_at")?.optString("timestampValue").orEmpty())
                .put("allDay", f.optJSONObject("all_day")?.optBoolean("booleanValue") ?: false)
                .put("location", f.optJSONObject("location")?.optString("stringValue").orEmpty())
                .put("color", f.optJSONObject("color")?.optString("stringValue").orEmpty()))
        }
        return out.toString()
    }

    private fun parse(raw: String): List<Appt>? = runCatching {
        if (raw.isBlank()) return null
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val start = parseIso(o.optString("start")) ?: return@mapNotNull null
            Appt(
                title = o.optString("title").ifBlank { "Appointment" },
                start = start,
                allDay = o.optBoolean("allDay"),
                location = o.optString("location"),
                color = o.optString("color").takeIf { it.startsWith("#") }
            )
        }
    }.getOrNull()

    /** Drops appointments that have already finished today. */
    private fun upcoming(list: List<Appt>): List<Appt> {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        return list.filter { it.allDay || it.start.time >= cutoff }
    }

    // ---------- Firebase Auth ----------

    /** A valid ID token, refreshed with the stored refresh token when needed. */
    private fun idToken(ctx: Context): String {
        val p = Prefs(ctx)
        if (p.ssIdToken.isNotBlank() && System.currentTimeMillis() < p.ssIdTokenExpiry) return p.ssIdToken
        val refresh = p.ssRefreshToken
        if (refresh.isBlank()) throw AuthException("NOT_SIGNED_IN")
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
        val res = JSONObject(post(
            "https://securetoken.googleapis.com/v1/token?key=${apiKey(ctx)}",
            form, "application/x-www-form-urlencoded"
        ))
        p.ssIdToken = res.getString("id_token")
        p.ssRefreshToken = res.optString("refresh_token", refresh)
        p.ssUid = res.optString("user_id", p.ssUid)
        p.ssIdTokenExpiry = System.currentTimeMillis() + (res.optString("expires_in", "3600").toLong() - 120) * 1000
        return p.ssIdToken
    }

    /** The site's public Firebase web key, read from the site's own scripts and cached. */
    private fun apiKey(ctx: Context): String {
        val p = Prefs(ctx)
        if (p.ssApiKey.isNotBlank()) return p.ssApiKey
        val keyRe = Regex("AIza[0-9A-Za-z_\\-]{35}")
        val html = get("$SITE/login")
        val scripts = Regex("src=\"(/_next/static/chunks/[^\"]+\\.js)\"").findAll(html).map { it.groupValues[1] }.toList()
        // The app/layout chunk usually holds the Firebase config; check it first
        for (src in scripts.sortedByDescending { it.contains("layout") }) {
            val js = runCatching { get(SITE + src) }.getOrNull() ?: continue
            keyRe.find(js)?.let {
                p.ssApiKey = it.value
                return it.value
            }
        }
        throw IOException("Couldn't find the site's app key")
    }

    private fun friendlyAuthError(code: String): String = when {
        code.contains("INVALID_LOGIN_CREDENTIALS") || code.contains("INVALID_PASSWORD") ||
            code.contains("EMAIL_NOT_FOUND") -> "Email or password is incorrect"
        code.contains("TOO_MANY_ATTEMPTS") -> "Too many attempts. Try again in a few minutes"
        code.contains("USER_DISABLED") -> "This account is disabled"
        else -> "Sign-in failed ($code)"
    }

    // ---------- HTTP ----------

    private fun get(url: String): String = request(url, "GET", null, null, null)

    private fun post(url: String, body: String, type: String, bearer: String? = null): String =
        request(url, "POST", body, type, bearer)

    private fun request(url: String, method: String, body: String?, type: String?, bearer: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        // The web key may be restricted to the site, so identify as coming from it
        conn.setRequestProperty("Referer", "$SITE/")
        conn.setRequestProperty("Origin", SITE)
        conn.setRequestProperty("User-Agent", "HomeBase-TV-Launcher")
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", type)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrNull()
                    ?: runCatching { JSONObject(err).getString("error") }.getOrNull()
                    ?: "HTTP $code"
                if (code == 400 || code == 401 || code == 403) throw AuthException(msg)
                throw IOException(msg)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun iso(d: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

    private fun parseIso(s: String): Date? {
        if (s.isBlank()) return null
        // Firestore gives e.g. 2026-10-30T08:15:00Z or 2026-10-30T08:15:00.123456Z
        val clean = s.replace(Regex("\\.\\d+Z$"), "Z")
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(clean)
        }.getOrNull()
    }
}
