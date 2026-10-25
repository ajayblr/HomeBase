package com.homebase.tvlauncher

import android.content.Context
import android.content.pm.ApplicationInfo

/** The rows on the home screen, in display order. */
enum class Category(val titleRes: Int) {
    STREAMING(R.string.row_streaming),
    LIVE(R.string.row_live),
    MUSIC(R.string.row_music),
    GAMES(R.string.row_games),
    APPS(R.string.row_apps);
}

object Categories {

    /** Known apps. Anything not listed falls back to the category the app declares. */
    private val KNOWN = mapOf(
        // Streaming
        "com.netflix.ninja" to Category.STREAMING,
        "com.amazon.amazonvideo.livingroom" to Category.STREAMING,
        "com.disney.disneyplus" to Category.STREAMING,
        "com.apple.atve.androidtv.appletv" to Category.STREAMING,
        "com.google.android.youtube.tv" to Category.STREAMING,
        "com.google.android.youtube.tvkids" to Category.STREAMING,
        "tv.wuaki.apptv" to Category.STREAMING,
        "com.plexapp.android" to Category.STREAMING,
        "org.jellyfin.androidtv" to Category.STREAMING,
        "org.xbmc.kodi" to Category.STREAMING,
        "com.holdapps.oneplayer" to Category.STREAMING,
        "com.hbo.hbonow" to Category.STREAMING,
        "com.wbd.stream" to Category.STREAMING,
        "com.nowtv.ie" to Category.STREAMING,
        "com.skyshowtime.skyshowtime.google" to Category.STREAMING,
        "com.paramount.android.pplus" to Category.STREAMING,
        "com.dazn" to Category.STREAMING,
        // Live TV / Irish & UK broadcasters
        "com.tcl.tv" to Category.LIVE,
        "com.twentyfouri.tvbridge.rte" to Category.LIVE,
        "ie.virginmedia.player" to Category.LIVE,
        "com.google.android.tv" to Category.LIVE,
        "com.pluto.tv" to Category.LIVE,
        "tv.pluto.android" to Category.LIVE,
        // Music & radio
        "com.google.android.youtube.tvmusic" to Category.MUSIC,
        "com.spotify.tv.android" to Category.MUSIC,
        "uk.co.freeview.bbc_sounds" to Category.MUSIC,
        "com.aspiro.tidal" to Category.MUSIC,
        "com.amazon.music.tv" to Category.MUSIC,
        "tunein.player" to Category.MUSIC,
        // Games
        "com.google.android.play.games" to Category.GAMES,
        "com.nvidia.geforcenow" to Category.GAMES,
        "com.microsoft.xcloud" to Category.GAMES
    )

    fun of(ctx: Context, pkg: String, overrides: Map<String, Category>): Category {
        overrides[pkg]?.let { return it }
        KNOWN[pkg]?.let { return it }
        if (pkg.startsWith("uk.co.freeview.")) return Category.LIVE
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: return Category.APPS
        return when (ai.category) {
            ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> Category.STREAMING
            ApplicationInfo.CATEGORY_AUDIO -> Category.MUSIC
            ApplicationInfo.CATEGORY_GAME -> Category.GAMES
            else -> if (ai.flags and ApplicationInfo.FLAG_IS_GAME != 0) Category.GAMES else Category.APPS
        }
    }
}
