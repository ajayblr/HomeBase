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
import android.widget.GridView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val COLUMNS = 5
        const val SIDE_PADDING_DP = 48
        const val SPACING_DP = 20

        private const val ACTION_ALL_APPS = "all_apps"
        private const val ACTION_SETTINGS = "settings"
        private const val ACTION_LAUNCHER = "launcher"
    }

    private lateinit var grid: GridView
    private lateinit var adapter: TileAdapter
    private lateinit var prefs: Prefs
    private lateinit var aurora: AuroraView
    private lateinit var greetingView: TextView
    private lateinit var nameView: TextView
    private lateinit var weatherView: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() {
            updateGreeting()
            aurora.start() // picks up the new time-of-day palette
            Weather.load(this@MainActivity) { now, city -> showWeather(now, city) }
            ui.postDelayed(this, 60_000)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            adapter.clearCache()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        if (prefs.firstRun) {
            prefs.pinned = AppRepo.defaultPins(this)
            prefs.firstRun = false
        }

        aurora = findViewById(R.id.aurora)
        greetingView = findViewById(R.id.greeting)
        nameView = findViewById(R.id.name)
        weatherView = findViewById(R.id.weather)

        grid = findViewById(R.id.grid)
        adapter = TileAdapter(this, TileAdapter.tileHeight(this, COLUMNS, SIDE_PADDING_DP, SPACING_DP))
        grid.adapter = adapter
        grid.onItemSelectedListener = FocusZoom()
        grid.setOnItemClickListener { _, _, pos, _ -> onTileClick(adapter.getItem(pos)) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> showTileMenu(pos); true }

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
        grid.requestFocus()
        Updater.maybeAutoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        aurora.start()
        ui.removeCallbacks(minuteTick)
        ui.post(minuteTick)
    }

    override fun onPause() {
        // Nothing animates or refreshes while an app is in front
        aurora.stop()
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
        greetingView.setText(res)
        val name = prefs.userName
        nameView.text = name
        nameView.visibility = if (name.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showWeather(now: Weather.Now?, city: String) {
        if (now == null) {
            weatherView.visibility = View.GONE
            return
        }
        val (icon, text) = Weather.describe(now)
        weatherView.text = listOf("$icon  ${now.tempC}°", text, city).filter { it.isNotBlank() }.joinToString("  ·  ")
        weatherView.visibility = View.VISIBLE
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already on HomeBase: jump back to the first tile
        if (::grid.isInitialized) {
            grid.setSelection(0)
            grid.requestFocus()
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
            val pos = grid.selectedItemPosition
            if (pos >= 0) showTileMenu(pos)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun refresh() {
        val installed = AppRepo.allApps(this).associateBy { it.pkg }
        val tiles = mutableListOf<Tile>()
        prefs.pinned.mapNotNull { installed[it] }.forEach { tiles += Tile.App(it) }
        tiles += Tile.Action(ACTION_ALL_APPS, getString(R.string.all_apps), R.drawable.ic_apps)
        tiles += Tile.Action(ACTION_SETTINGS, getString(R.string.tv_settings), R.drawable.ic_settings)
        tiles += Tile.Action(ACTION_LAUNCHER, getString(R.string.launcher_options), R.drawable.ic_tune)
        val keep = grid.selectedItemPosition
        adapter.submit(tiles)
        if (keep in tiles.indices) grid.setSelection(keep)
    }

    private fun onTileClick(tile: Tile) {
        when (tile) {
            is Tile.App -> if (!AppRepo.launch(this, tile.entry.pkg)) toast(getString(R.string.cannot_open))
            is Tile.Action -> when (tile.id) {
                ACTION_ALL_APPS -> startActivity(Intent(this, AllAppsActivity::class.java))
                ACTION_SETTINGS -> AppRepo.safeStart(this, Intent(Settings.ACTION_SETTINGS))
                ACTION_LAUNCHER -> showLauncherOptions()
            }
        }
    }

    private fun showTileMenu(pos: Int) {
        val tile = adapter.getItem(pos)
        if (tile !is Tile.App) {
            onTileClick(tile)
            return
        }
        val pkg = tile.entry.pkg
        val options = arrayOf(
            getString(R.string.move_left),
            getString(R.string.move_right),
            getString(R.string.unpin),
            getString(R.string.app_info),
            getString(R.string.uninstall)
        )
        dialog().setTitle(tile.entry.label).setItems(options) { _, which ->
            when (which) {
                0 -> { val to = prefs.move(pkg, -1); refresh(); grid.setSelection(to) }
                1 -> { val to = prefs.move(pkg, +1); refresh(); grid.setSelection(to) }
                2 -> { prefs.unpin(pkg); refresh() }
                3 -> AppRepo.openAppInfo(this, pkg)
                4 -> AppRepo.uninstall(this, pkg)
            }
        }.show()
    }

    private fun showLauncherOptions() {
        val takeoverLabel = getString(if (prefs.takeover) R.string.takeover_on else R.string.takeover_off)
        val options = arrayOf(
            getString(R.string.your_name) + ": " + prefs.userName,
            getString(R.string.weather_location) + ": " + prefs.weatherCity,
            getString(R.string.check_updates),
            getString(R.string.update_source),
            takeoverLabel,
            getString(R.string.reset_home),
            getString(R.string.about_fmt, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        )
        dialog().setTitle(R.string.launcher_options).setItems(options) { _, which ->
            when (which) {
                0 -> editName()
                1 -> editWeatherCity()
                2 -> Updater.check(this, silent = false)
                3 -> editUpdateUrl()
                4 -> {
                    prefs.takeover = !prefs.takeover
                    toast(getString(if (prefs.takeover) R.string.takeover_on else R.string.takeover_off))
                }
                5 -> { prefs.pinned = AppRepo.defaultPins(this); refresh() }
                6 -> {}
            }
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

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
