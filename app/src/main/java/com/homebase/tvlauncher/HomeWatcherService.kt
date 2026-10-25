package com.homebase.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * HomeBase's helper service. It does two things:
 *  1. Brings HomeBase back if the stock Android TV home screen ever appears.
 *  2. Puts the TV to sleep (screen off button and sleep timer). Android only lets
 *     accessibility services do this, which is why it lives here.
 *
 * It only receives events from the stock launcher packages listed in
 * res/xml/home_watcher.xml and never reads anything on screen.
 * Turn it on once with adb (see README).
 */
class HomeWatcherService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HomeWatcherService? = null
            private set

        val isRunning get() = instance != null
    }

    private val stockLaunchers = setOf(
        "com.google.android.apps.tv.launcherx",   // Google TV home
        "com.google.android.tvlauncher",          // Android TV home
        "com.google.android.leanbacklauncher"     // older Android TV home
    )

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunch = 0L

    private val sleepWarning = Runnable {
        Toast.makeText(this, R.string.sleep_warning, Toast.LENGTH_LONG).show()
    }
    private val sleepTask = Runnable {
        Prefs(this).sleepAt = 0L
        sleepNow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Re-arm a timer that was running before the service restarted
        val at = Prefs(this).sleepAt
        val left = at - System.currentTimeMillis()
        if (at > 0 && left > 0) arm(left) else Prefs(this).sleepAt = 0L
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Turns the screen off / puts the TV in standby. */
    fun sleepNow(): Boolean {
        return if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }
    }

    fun startSleepTimer(minutes: Int) {
        val ms = minutes * 60_000L
        Prefs(this).sleepAt = System.currentTimeMillis() + ms
        arm(ms)
    }

    fun cancelSleepTimer() {
        Prefs(this).sleepAt = 0L
        handler.removeCallbacks(sleepWarning)
        handler.removeCallbacks(sleepTask)
    }

    private fun arm(ms: Long) {
        handler.removeCallbacks(sleepWarning)
        handler.removeCallbacks(sleepTask)
        if (ms > 70_000L) handler.postDelayed(sleepWarning, ms - 60_000L)
        handler.postDelayed(sleepTask, ms)
    }

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
