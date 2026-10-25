package com.homebase.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV doesn't let you pick a different home app, and the Home button is
 * wired to the stock launcher. This service watches for the stock home screen
 * appearing (Home button, boot, leaving an app) and immediately opens HomeBase
 * instead. It only receives events from the stock launcher packages listed in
 * res/xml/home_watcher.xml and reads nothing on screen.
 *
 * Turn it on with adb (see README) or from Launcher options → Home button.
 */
class HomeWatcherService : AccessibilityService() {

    private val stockLaunchers = setOf(
        "com.google.android.apps.tv.launcherx",   // Google TV home
        "com.google.android.tvlauncher",          // Android TV home
        "com.google.android.leanbacklauncher"     // older Android TV home
    )

    private var lastLaunch = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in stockLaunchers) return
        if (!Prefs(this).takeover) return

        // Debounce: the stock launcher fires several events as it opens
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return
        lastLaunch = now

        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        runCatching { startActivity(intent) }
    }

    override fun onInterrupt() {}
}
