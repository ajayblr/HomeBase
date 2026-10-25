package com.homebase.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings

/** One launchable app on the TV. */
data class AppEntry(val pkg: String, val activity: String, val label: String)

/** What a grid tile shows: an app, or one of HomeBase's own buttons. */
sealed class Tile {
    data class App(val entry: AppEntry, val pinned: Boolean = false) : Tile()
    data class Action(val id: String, val label: String, val iconRes: Int) : Tile()
}

object AppRepo {

    /**
     * Apps that start the first time HomeBase runs, in this order, if installed.
     * Edit this list to change the out-of-the-box home screen.
     */
    private val DEFAULT_PINS = listOf(
        "com.google.android.youtube.tv",          // YouTube
        "com.netflix.ninja",                      // Netflix
        "com.amazon.amazonvideo.livingroom",      // Prime Video
        "com.disney.disneyplus",                  // Disney+
        "com.apple.atve.androidtv.appletv",       // Apple TV
        "com.spotify.tv.android",                 // Spotify
        "com.plexapp.android",                    // Plex
        "com.google.android.youtube.tvmusic",     // YouTube Music
        "com.android.vending"                     // Play Store
    )

    /** Every app with a TV (leanback) or regular launcher entry, sorted by name. */
    fun allApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val result = LinkedHashMap<String, AppEntry>()
        // Leanback entry first, so TV apps use their TV activity and banner
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                if (result.containsKey(ai.packageName)) continue
                result[ai.packageName] = AppEntry(ai.packageName, ai.name, ri.loadLabel(pm).toString())
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    fun defaultPins(ctx: Context): List<String> {
        val installed = allApps(ctx).map { it.pkg }.toSet()
        val pins = DEFAULT_PINS.filter { it in installed }
        return pins.ifEmpty { installed.take(8) }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Prefs(ctx).addRecent(pkg)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openAppInfo(ctx: Context, pkg: String) {
        safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
    }

    fun uninstall(ctx: Context, pkg: String) {
        safeStart(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    fun safeStart(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent); true
    } catch (e: Exception) {
        false
    }

    /** Wide TV banner if the app has one (isBanner = true), otherwise its square icon. */
    fun visual(ctx: Context, e: AppEntry): Pair<Drawable, Boolean> {
        val pm = ctx.packageManager
        val cn = ComponentName(e.pkg, e.activity)
        val banner = runCatching { pm.getActivityBanner(cn) ?: pm.getApplicationBanner(e.pkg) }.getOrNull()
        if (banner != null) return banner to true
        val icon = runCatching { pm.getActivityIcon(cn) }.getOrNull() ?: pm.defaultActivityIcon
        return icon to false
    }
}
