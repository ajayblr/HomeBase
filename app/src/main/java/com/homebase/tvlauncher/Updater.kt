package com.homebase.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Over-the-air updates for HomeBase itself.
 *
 * The update source is a small JSON file (version.json):
 *   { "versionCode": 7, "versionName": "1.0.7", "apkUrl": "https://…/HomeBase.apk" }
 *
 * If versionCode is higher than the installed one, HomeBase downloads the APK and
 * hands it to Android's installer, which asks you to confirm on the TV.
 * The GitHub workflow in this project publishes both files on every build.
 */
object Updater {

    private data class Info(val code: Int, val name: String, val apkUrl: String)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Quiet check at most once a day when the home screen starts. */
    fun maybeAutoCheck(act: Activity) {
        val prefs = Prefs(act)
        if (prefs.updateUrl.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < DAY_MS) return
        prefs.lastUpdateCheck = now
        check(act, silent = true)
    }

    fun check(act: Activity, silent: Boolean) {
        val url = Prefs(act).updateUrl
        if (url.isBlank()) {
            if (!silent) toast(act, act.getString(R.string.no_update_source))
            return
        }
        if (!silent) toast(act, act.getString(R.string.checking_updates))
        thread(name = "update-check") {
            val result = runCatching { fetchInfo(url) }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                result.onFailure { e ->
                    if (!silent) toast(act, act.getString(R.string.update_check_failed, e.message ?: e.javaClass.simpleName))
                }
                result.onSuccess { info ->
                    if (info.code <= BuildConfig.VERSION_CODE) {
                        if (!silent) toast(act, act.getString(R.string.up_to_date, BuildConfig.VERSION_NAME))
                    } else {
                        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(R.string.update_available)
                            .setMessage(act.getString(R.string.update_available_msg, info.name, BuildConfig.VERSION_NAME))
                            .setPositiveButton(R.string.install) { _, _ -> download(act, info) }
                            .setNegativeButton(R.string.later, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun download(act: Activity, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.allow_installs)
                .setMessage(R.string.allow_installs_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val ok = AppRepo.safeStart(
                        act,
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
                    )
                    if (!ok) toast(act, act.getString(R.string.allow_installs_adb))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        toast(act, act.getString(R.string.downloading))
        val appCtx = act.applicationContext
        thread(name = "update-download") {
            runCatching {
                val installer = appCtx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(appCtx.packageName)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("HomeBase.apk", 0, -1).use { out ->
                        open(info.apkUrl).use { input -> input.copyTo(out) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(
                        appCtx, sessionId, Intent(appCtx, InstallResultReceiver::class.java), flags
                    )
                    session.commit(pi.intentSender)
                }
            }.onFailure { e ->
                act.runOnUiThread {
                    toast(appCtx, appCtx.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun fetchInfo(url: String): Info {
        val text = open(url).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return Info(
            code = json.getInt("versionCode"),
            name = json.optString("versionName", json.getInt("versionCode").toString()),
            apkUrl = json.getString("apkUrl")
        )
    }

    /** GET with redirects followed (GitHub release links redirect to a CDN host). */
    private fun open(url: String): InputStream {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "HomeBase-TV-Launcher")
            when (val code = conn.responseCode) {
                in 200..299 -> return conn.inputStream
                301, 302, 303, 307, 308 -> {
                    val next = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                    current = URL(URL(current), next).toString()
                    conn.disconnect()
                }
                else -> {
                    conn.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
