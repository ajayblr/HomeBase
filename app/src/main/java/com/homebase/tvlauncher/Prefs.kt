package com.homebase.tvlauncher

import android.content.Context

/** Small wrapper around SharedPreferences for everything HomeBase remembers. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("homebase", Context.MODE_PRIVATE)

    /** Package names pinned to the home screen, in display order. */
    var pinned: List<String>
        get() = (sp.getString("pinned", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("pinned", v.joinToString(",")).apply()

    var firstRun: Boolean
        get() = sp.getBoolean("first_run", true)
        set(v) = sp.edit().putBoolean("first_run", v).apply()

    /** When on, HomeBase reopens whenever the stock Google TV home screen appears. */
    var takeover: Boolean
        get() = sp.getBoolean("takeover", true)
        set(v) = sp.edit().putBoolean("takeover", v).apply()

    /** URL of version.json. Defaults to the value baked in at build time. */
    var updateUrl: String
        get() = sp.getString("update_url", null) ?: BuildConfig.UPDATE_URL
        set(v) = sp.edit().putString("update_url", v.trim()).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    fun pin(pkg: String) {
        if (pkg !in pinned) pinned = pinned + pkg
    }

    fun unpin(pkg: String) {
        pinned = pinned - pkg
    }

    /** Move a pinned app by [delta] places (-1 = left, +1 = right). Returns its new index. */
    fun move(pkg: String, delta: Int): Int {
        val list = pinned.toMutableList()
        val from = list.indexOf(pkg)
        if (from < 0) return -1
        val to = (from + delta).coerceIn(0, list.size - 1)
        list.removeAt(from)
        list.add(to, pkg)
        pinned = list
        return to
    }
}
