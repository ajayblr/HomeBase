package com.homebase.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast

/** Every installed app. OK opens it; hold OK (or Menu) to pin it to the home screen. */
class AllAppsActivity : Activity() {

    companion object {
        const val COLUMNS = 6
    }

    private lateinit var grid: GridView
    private lateinit var adapter: TileAdapter
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        prefs = Prefs(this)
        grid = findViewById(R.id.grid)
        adapter = TileAdapter(this, TileAdapter.tileHeight(this, COLUMNS, MainActivity.SIDE_PADDING_DP, MainActivity.SPACING_DP))
        grid.adapter = adapter
        grid.onItemSelectedListener = FocusZoom()
        grid.setOnItemClickListener { _, _, pos, _ ->
            val t = adapter.getItem(pos) as Tile.App
            if (!AppRepo.launch(this, t.entry.pkg)) toast(getString(R.string.cannot_open))
        }
        grid.setOnItemLongClickListener { _, _, pos, _ -> showMenu(pos); true }
        grid.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        findViewById<AuroraView>(R.id.aurora).start()
    }

    override fun onPause() {
        findViewById<AuroraView>(R.id.aurora).stop()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val pos = grid.selectedItemPosition
            if (pos >= 0) showMenu(pos)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun refresh() {
        val pinned = prefs.pinned.toSet()
        val apps = AppRepo.allApps(this)
        findViewById<TextView>(R.id.title).text = getString(R.string.all_apps_count, apps.size)
        val keep = grid.selectedItemPosition
        adapter.submit(apps.map { Tile.App(it, pinned = it.pkg in pinned) })
        if (keep in apps.indices) grid.setSelection(keep)
    }

    private fun showMenu(pos: Int) {
        val t = adapter.getItem(pos) as Tile.App
        val pkg = t.entry.pkg
        val options = arrayOf(
            getString(if (t.pinned) R.string.unpin else R.string.pin),
            getString(R.string.app_info),
            getString(R.string.uninstall)
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.entry.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (t.pinned) prefs.unpin(pkg) else prefs.pin(pkg)
                        refresh()
                    }
                    1 -> AppRepo.openAppInfo(this, pkg)
                    2 -> AppRepo.uninstall(this, pkg)
                }
            }.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
