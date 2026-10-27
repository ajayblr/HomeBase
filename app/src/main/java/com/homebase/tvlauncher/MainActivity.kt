package com.homebase.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import kotlin.concurrent.thread
import android.provider.Settings
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val COLUMNS = 5
        const val SIDE_PADDING_DP = 64
        const val SPACING_DP = 36

        private const val ACTION_ALL_APPS = "all_apps"
        private const val ACTION_SETTINGS = "settings"
        private const val ACTION_LAUNCHER = "launcher"
        private const val ACTION_LIVE_TV = "live_tv"
        private const val ACTION_SLEEP = "sleep"
        private const val ROW_RECENT = "recent"
        private const val MAX_RECENTS = 6
        private const val ACTION_SCREEN_OFF = "screen_off"
        private const val INPUT_PREFIX = "input:"
    }

    private lateinit var homeRows: HomeRows
    private var rows: List<HomeRow> = emptyList()
    private lateinit var prefs: Prefs
    private lateinit var aurora: AuroraView
    private lateinit var backgrounds: Backgrounds
    private lateinit var greetingView: TextView
    private lateinit var scroll: android.widget.ScrollView
    private lateinit var miniClock: View
    private lateinit var sleepView: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() {
            updateGreeting()
            updateSleepStatus()
            if (aurora.visibility == View.VISIBLE) aurora.start() // picks up the time-of-day palette
            Weather.load(this@MainActivity) { now, city -> showWeather(now, city) }
            ui.postDelayed(this, 60_000)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            homeRows.clearCache()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        prefs.applyStyleV2()

        if (prefs.firstRun) {
            prefs.pinned = AppRepo.defaultPins(this)
            prefs.firstRun = false
        }

        aurora = findViewById(R.id.aurora)
        backgrounds = Backgrounds(this, aurora, findViewById(R.id.photos), findViewById(R.id.rain))
        greetingView = findViewById(R.id.greeting)
        scroll = findViewById(R.id.rows_scroll)
        miniClock = findViewById(R.id.mini_clock)

        // Top shelf fills the top ~45% of the screen, like Apple TV
        val shelf = findViewById<View>(R.id.top_shelf)
        shelf.layoutParams = shelf.layoutParams.apply { height = (resources.displayMetrics.heightPixels * 0.45f).toInt() }

        // Small clock fades in top-right once the big one scrolls away
        scroll.viewTreeObserver.addOnScrollChangedListener {
            val target = if (scroll.scrollY > shelf.height / 2) 1f else 0f
            if (miniClock.alpha != target) miniClock.animate().alpha(target).setDuration(200).start()
        }
        sleepView = findViewById(R.id.sleep_status)

        homeRows = HomeRows(
            this,
            findViewById(R.id.rows),
            TileAdapter.tileHeight(this, COLUMNS, SIDE_PADDING_DP, SPACING_DP),
            onClick = { _, tile -> onTileClick(tile) },
            onMenu = { row, tile -> showTileMenu(row, tile) },
            onRowFocused = { index -> if (index == 0) scroll.smoothScrollTo(0, 0) }
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }

        refresh()
        Updater.maybeAutoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        backgrounds.start()
        ui.removeCallbacks(minuteTick)
        ui.post(minuteTick)
    }

    override fun onPause() {
        // Nothing animates or refreshes while an app is in front
        backgrounds.stop()
        ui.removeCallbacks(minuteTick)
        super.onPause()
    }

    private fun updateGreeting() {
        val res = when (AuroraView.currentPhase()) {
            AuroraView.Phase.MORNING -> R.string.good_morning
            AuroraView.Phase.AFTERNOON -> R.string.good_afternoon
            AuroraView.Phase.EVENING -> R.string.good_evening
            AuroraView.Phase.NIGHT -> R.string.good_night
        }
        val name = prefs.userName
        greetingView.text = if (name.isBlank()) getString(res) else getString(R.string.greeting_fmt, getString(res), name)
    }

    private fun showWeather(f: Weather.Forecast?, city: String) {
        val card = findViewById<View>(R.id.weather_card)
        if (f == null) {
            card.visibility = View.INVISIBLE
            return
        }
        findViewById<TextView>(R.id.weather_place).text = city
        findViewById<TextView>(R.id.weather_icon).text = Weather.icon(f.code, f.isDay)
        findViewById<TextView>(R.id.weather_temp).text = "${f.tempC}°"
        findViewById<TextView>(R.id.weather_text).text = Weather.text(f.code)
        val extra = mutableListOf<String>()
        if (f.highC != null && f.lowC != null) extra += getString(R.string.weather_range, f.highC, f.lowC)
        if (f.rainChance != null && f.rainChance > 0) extra += getString(R.string.weather_rain, f.rainChance)
        findViewById<TextView>(R.id.weather_range).text = extra.joinToString("   ")

        val hours = findViewById<LinearLayout>(R.id.weather_hours)
        hours.removeAllViews()
        for (h in f.hours) {
            val item = layoutInflater.inflate(R.layout.weather_hour, hours, false)
            item.findViewById<TextView>(R.id.hour_time).text = h.label
            item.findViewById<TextView>(R.id.hour_icon).text = Weather.icon(h.code, h.isDay)
            item.findViewById<TextView>(R.id.hour_temp).text = "${h.tempC}°"
            hours.addView(item)
        }
        hours.visibility = if (f.hours.isEmpty()) View.GONE else View.VISIBLE
        card.visibility = View.VISIBLE
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already on HomeBase: jump back to the first tile
        if (::homeRows.isInitialized) {
            scroll.scrollTo(0, 0)
            homeRows.focusFirst()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }

    @Deprecated("Back does nothing on a home screen")
    override fun onBackPressed() {
        // Intentionally empty: there is nothing "behind" the home screen
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            homeRows.focused(rows)?.let { (row, tile) -> showTileMenu(row, tile) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun refresh() {
        rows = buildRows()
        homeRows.show(rows)
    }

    private fun buildRows(): List<HomeRow> {
        val installed = AppRepo.allApps(this).associateBy { it.pkg }
        val overrides = prefs.categoryOverrides
        val pinnedApps = prefs.pinned.mapNotNull { installed[it] }
        val byCat = pinnedApps.groupBy { Categories.of(this, it.pkg, overrides) }

        val result = mutableListOf<HomeRow>()
        // Apps opened recently; opening one picks up where you left off
        val recent = prefs.recents.mapNotNull { installed[it] }.take(MAX_RECENTS)
        if (recent.isNotEmpty()) {
            result += HomeRow(ROW_RECENT, getString(R.string.row_recent), recent.map { Tile.App(it) })
        }
        for (cat in Category.values()) {
            val apps = byCat[cat].orEmpty()
            if (apps.isNotEmpty()) {
                result += HomeRow("cat:" + cat.name, getString(cat.titleRes), apps.map { Tile.App(it) })
            }
        }
        val inputTiles = mutableListOf<Tile>()
        Inputs.list(this).forEach { inputTiles += Tile.Action(INPUT_PREFIX + it.id, it.label, R.drawable.ic_input) }
        if (installed.containsKey("com.tcl.tv")) {
            inputTiles += Tile.Action(ACTION_LIVE_TV, getString(R.string.tv_aerial), R.drawable.ic_tv)
        }
        if (inputTiles.isNotEmpty()) {
            // Inputs go straight after Live TV
            val at = result.indexOfFirst { it.key == "cat:" + Category.MUSIC.name || it.key == "cat:" + Category.GAMES.name || it.key == "cat:" + Category.APPS.name }
            val row = HomeRow("inputs", getString(R.string.row_inputs), inputTiles)
            if (at >= 0) result.add(at, row) else result += row
        }
        result += HomeRow("tools", getString(R.string.row_tools), toolTiles())
        return result
    }

    private fun toolTiles(): List<Tile> = listOf(
        Tile.Action(ACTION_ALL_APPS, getString(R.string.all_apps), R.drawable.ic_apps),
        Tile.Action(
            ACTION_SLEEP,
            getString(if (prefs.sleepAt > System.currentTimeMillis()) R.string.sleep_timer_on else R.string.sleep_timer),
            R.drawable.ic_timer
        ),
        Tile.Action(ACTION_SCREEN_OFF, getString(R.string.screen_off), R.drawable.ic_power),
        Tile.Action(ACTION_SETTINGS, getString(R.string.tv_settings), R.drawable.ic_settings),
        Tile.Action(ACTION_LAUNCHER, getString(R.string.launcher_options), R.drawable.ic_tune)
    )

    private fun onTileClick(tile: Tile) {
        when (tile) {
            is Tile.App -> if (!AppRepo.launch(this, tile.entry.pkg)) toast(getString(R.string.cannot_open))
            is Tile.Action -> if (tile.id.startsWith(INPUT_PREFIX)) {
                if (!Inputs.open(this, tile.id.removePrefix(INPUT_PREFIX))) toast(getString(R.string.cannot_open))
            } else when (tile.id) {
                ACTION_SLEEP -> showSleepTimer()
                ACTION_SCREEN_OFF -> withService { it.sleepNow() }
                ACTION_LIVE_TV -> if (!AppRepo.launch(this, "com.tcl.tv")) toast(getString(R.string.cannot_open))
                ACTION_ALL_APPS -> startActivity(Intent(this, AllAppsActivity::class.java))
                ACTION_SETTINGS -> AppRepo.safeStart(this, Intent(Settings.ACTION_SETTINGS))
                ACTION_LAUNCHER -> showLauncherOptions()
            }
        }
    }

    private fun showTileMenu(row: HomeRow, tile: Tile) {
        if (tile !is Tile.App) {
            onTileClick(tile)
            return
        }
        val pkg = tile.entry.pkg
        if (row.key == ROW_RECENT) {
            val pinned = pkg in prefs.pinned
            val items = listOf<Pair<String, () -> Unit>>(
                getString(R.string.remove_recent) to { prefs.removeRecent(pkg); refresh() },
                getString(if (pinned) R.string.unpin else R.string.pin) to {
                    if (pinned) prefs.unpin(pkg) else prefs.pin(pkg)
                    refresh()
                },
                getString(R.string.clear_recents) to { prefs.recents = emptyList(); refresh() },
                getString(R.string.app_info) to { AppRepo.openAppInfo(this, pkg) }
            )
            dialog().setTitle(tile.entry.label)
                .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
                .show()
            return
        }
        val rowPkgs = row.tiles.filterIsInstance<Tile.App>().map { it.entry.pkg }
        val idx = rowPkgs.indexOf(pkg)
        val options = arrayOf(
            getString(R.string.move_left),
            getString(R.string.move_right),
            getString(R.string.move_to_row),
            getString(R.string.unpin),
            getString(R.string.app_info),
            getString(R.string.uninstall)
        )
        dialog().setTitle(tile.entry.label).setItems(options) { _, which ->
            when (which) {
                0 -> if (idx > 0) { prefs.swap(pkg, rowPkgs[idx - 1]); refresh() }
                1 -> if (idx in 0 until rowPkgs.size - 1) { prefs.swap(pkg, rowPkgs[idx + 1]); refresh() }
                2 -> chooseRow(pkg)
                3 -> { prefs.unpin(pkg); refresh() }
                4 -> AppRepo.openAppInfo(this, pkg)
                5 -> AppRepo.uninstall(this, pkg)
            }
        }.show()
    }

    private fun chooseRow(pkg: String) {
        val cats = Category.values()
        val names = cats.map { getString(it.titleRes) }.toTypedArray()
        val current = Categories.of(this, pkg, prefs.categoryOverrides)
        dialog().setTitle(R.string.move_to_row)
            .setSingleChoiceItems(names, cats.indexOf(current)) { d, which ->
                prefs.setCategory(pkg, cats[which])
                d.dismiss()
                refresh()
                toast(getString(R.string.moved_to, names[which]))
            }.show()
    }

    private fun showLauncherOptions() {
        val takeoverLabel = getString(if (prefs.takeover) R.string.takeover_on else R.string.takeover_off)
        val items = listOf<Pair<String, () -> Unit>>(
            getString(R.string.your_name) + ": " + prefs.userName to { editName() },
            getString(R.string.weather_location) + ": " + prefs.weatherCity to { editWeatherCity() },
            getString(R.string.background) + ": " + getString(prefs.backgroundMode.labelRes) to { chooseBackground() },
            getString(R.string.slideshow_every, prefs.slideshowMinutes) to { chooseSlideshowInterval() },
            getString(R.string.check_updates) to { Updater.check(this, silent = false) },
            getString(R.string.update_source) to { editUpdateUrl() },
            takeoverLabel to {
                prefs.takeover = !prefs.takeover
                toast(getString(if (prefs.takeover) R.string.takeover_on else R.string.takeover_off))
            },
            getString(R.string.reset_home) to { prefs.categoryOverrides = emptyMap(); prefs.pinned = AppRepo.defaultPins(this); refresh() },
            getString(R.string.about_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) to {}
        )
        dialog().setTitle(R.string.launcher_options)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun updateSleepStatus() {
        val left = prefs.sleepAt - System.currentTimeMillis()
        if (prefs.sleepAt > 0 && left > 0 && HomeWatcherService.isRunning) {
            val mins = ((left + 59_999) / 60_000).toInt()
            sleepView.text = getString(R.string.sleep_status, mins)
            sleepView.visibility = View.VISIBLE
        } else {
            sleepView.visibility = View.GONE
        }
    }

    private fun showSleepTimer() {
        val choices = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val names = choices.map {
            if (it == 0) getString(R.string.sleep_off) else resources.getQuantityString(R.plurals.minutes, it, it)
        }.toTypedArray()
        dialog().setTitle(R.string.sleep_timer)
            .setItems(names) { _, which ->
                withService { svc ->
                    if (choices[which] == 0) {
                        svc.cancelSleepTimer()
                        toast(getString(R.string.sleep_cancelled))
                    } else {
                        svc.startSleepTimer(choices[which])
                        toast(getString(R.string.sleep_set, names[which]))
                    }
                    updateSleepStatus()
                    refresh()
                }
            }.show()
    }

    /** Runs [block] with the helper service, or explains the one-time setup if it's off. */
    private fun withService(block: (HomeWatcherService) -> Unit) {
        val svc = HomeWatcherService.instance
        if (svc != null) {
            block(svc)
            return
        }
        dialog().setTitle(R.string.service_needed_title)
            .setMessage(getString(R.string.service_needed_msg, packageName))
            .setPositiveButton(R.string.open_accessibility) { _, _ ->
                if (!AppRepo.safeStart(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                    toast(getString(R.string.cannot_open))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseBackground() {
        val modes = BackgroundMode.values()
        val names = modes.map { getString(it.labelRes) }.toTypedArray()
        dialog().setTitle(R.string.background)
            .setSingleChoiceItems(names, modes.indexOf(prefs.backgroundMode)) { d, which ->
                prefs.backgroundMode = modes[which]
                prefs.clearPhotoListTime(modes[which]) // fetch the newest photos now
                d.dismiss()
                backgrounds.start()
                if (modes[which] == BackgroundMode.PHOTOS) toast(getString(R.string.bg_photos_help))
            }.show()
    }

    private fun chooseSlideshowInterval() {
        val choices = intArrayOf(1, 5, 15, 30, 60)
        val names = choices.map { resources.getQuantityString(R.plurals.minutes, it, it) }.toTypedArray()
        dialog().setTitle(R.string.slideshow_title)
            .setSingleChoiceItems(names, choices.indexOf(prefs.slideshowMinutes)) { d, which ->
                prefs.slideshowMinutes = choices[which]
                d.dismiss()
                backgrounds.start()
            }.show()
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(prefs.userName)
            setSingleLine()
        }
        dialog().setTitle(R.string.your_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.userName = input.text.toString()
                updateGreeting()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editWeatherCity() {
        val input = EditText(this).apply {
            setText(prefs.weatherCity)
            setSingleLine()
        }
        dialog().setTitle(R.string.weather_location)
            .setMessage(R.string.weather_location_help)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val query = input.text.toString()
                if (query.isBlank()) return@setPositiveButton
                thread(name = "geocode") {
                    val hit = runCatching { Weather.geocode(query) }.getOrNull()
                    runOnUiThread {
                        if (hit == null) {
                            toast(getString(R.string.weather_not_found, query))
                        } else {
                            prefs.weatherCity = hit.first
                            prefs.weatherLat = hit.second
                            prefs.weatherLon = hit.third
                            prefs.weatherCache = ""
                            toast(getString(R.string.weather_set, hit.first))
                            Weather.load(this, force = true) { now, city -> showWeather(now, city) }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editUpdateUrl() {
        val input = EditText(this).apply {
            setText(prefs.updateUrl)
            hint = "https://…/version.json"
            setSingleLine()
        }
        dialog().setTitle(R.string.update_source)
            .setMessage(R.string.update_source_help)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> prefs.updateUrl = input.text.toString() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dialog() = AlertDialog.Builder(this, R.style.HomeBaseDialog)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
