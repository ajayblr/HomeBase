package com.homebase.tvlauncher

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** One row on the home screen. */
data class HomeRow(val key: String, val title: String, val tiles: List<Tile>)

/** Stable identity of a tile, used to put focus back after the rows are rebuilt. */
fun Tile.key(): String = when (this) {
    is Tile.App -> "app:" + entry.pkg
    is Tile.Action -> "action:" + id
}

/**
 * Builds the home rows into a vertical LinearLayout (inside a ScrollView).
 * Each row is a title plus a horizontally scrolling strip of tiles.
 * D-pad focus, scrolling and zoom are all handled by plain Android views,
 * so it stays fast on low-end TV hardware.
 */
class HomeRows(
    private val ctx: Context,
    private val container: LinearLayout,
    private val tileHeight: Int,
    private val onClick: (row: HomeRow, tile: Tile) -> Unit,
    private val onMenu: (row: HomeRow, tile: Tile) -> Unit
) {
    private val inflater = LayoutInflater.from(ctx)
    private val visuals = HashMap<String, Pair<Drawable, Boolean>>()
    private var signature = ""
    private val density = ctx.resources.displayMetrics.density

    fun clearCache() {
        visuals.clear()
        signature = ""
    }

    /** Rebuilds only if something changed. Keeps focus on the same tile where possible. */
    fun show(rows: List<HomeRow>) {
        val sig = rows.joinToString("|") { r -> r.key + ":" + r.title + ":" + r.tiles.joinToString(",") { it.key() + labelOf(it) } }
        if (sig == signature) return
        signature = sig

        val focusedKey = (container.findFocus()?.tag as? Pair<*, *>)?.second as? String
        container.removeAllViews()

        val tileWidth = tileHeight * 16 / 9
        val gap = (20 * density).toInt()
        var restore: View? = null
        var first: View? = null

        for (row in rows) {
            if (row.tiles.isEmpty()) continue

            val title = inflater.inflate(R.layout.row_title, container, false) as TextView
            title.text = row.title
            container.addView(title)

            val scroller = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                isFillViewport = false
                val vPad = (14 * density).toInt()
                setPadding(0, vPad, 0, vPad)
            }
            val strip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            scroller.addView(strip)
            container.addView(scroller)

            row.tiles.forEachIndexed { i, tile ->
                val v = inflater.inflate(R.layout.tile, strip, false)
                v.layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight).apply {
                    if (i > 0) marginStart = gap
                }
                v.clipToOutline = true
                v.isFocusable = true
                v.isFocusableInTouchMode = true
                v.foreground = ctx.getDrawable(R.drawable.tile_focus)
                v.tag = row.key to tile.key()
                TileAdapter.bind(ctx, v, tile, tileHeight, visuals)

                v.setOnClickListener { onClick(row, tile) }
                v.setOnLongClickListener { onMenu(row, tile); true }
                v.setOnFocusChangeListener { view, has ->
                    val s = if (has) 1.08f else 1f
                    view.animate().scaleX(s).scaleY(s).translationZ(if (has) 12f else 0f).setDuration(130).start()
                }
                strip.addView(v)

                if (first == null) first = v
                if (tile.key() == focusedKey && restore == null) restore = v
            }
        }
        (restore ?: first)?.requestFocus()
    }

    /** The tile that currently has focus, with its row. */
    fun focused(rows: List<HomeRow>): Pair<HomeRow, Tile>? {
        val tag = container.findFocus()?.tag as? Pair<*, *> ?: return null
        val row = rows.firstOrNull { it.key == tag.first } ?: return null
        val tile = row.tiles.firstOrNull { it.key() == tag.second } ?: return null
        return row to tile
    }

    fun focusFirst() {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is HorizontalScrollView) {
                c.scrollTo(0, 0)
                ((c.getChildAt(0) as? LinearLayout)?.getChildAt(0))?.requestFocus()
                return
            }
        }
    }

    private fun labelOf(t: Tile) = when (t) {
        is Tile.App -> t.entry.label
        is Tile.Action -> t.label
    }
}
