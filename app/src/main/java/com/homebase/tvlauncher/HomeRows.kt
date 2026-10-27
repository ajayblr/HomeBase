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
    private val onMenu: (row: HomeRow, tile: Tile) -> Unit,
    private val onRowFocused: (rowIndex: Int) -> Unit = {}
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
        val gap = (36 * density).toInt()
        var restore: View? = null
        var first: View? = null

        var rowIndex = -1
        for (row in rows) {
            if (row.tiles.isEmpty()) continue
            rowIndex++
            val thisRow = rowIndex

            if (row.title.isNotBlank()) {
                val title = inflater.inflate(R.layout.row_title, container, false) as TextView
                title.text = row.title
                container.addView(title)
            }

            val scroller = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                isFillViewport = false
                // Room for the selected tile to lift and grow without being clipped
                val vPad = (18 * density).toInt()
                val hPad = (14 * density).toInt()
                setPadding(hPad, vPad, hPad, (4 * density).toInt())
            }
            val strip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            scroller.addView(strip)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = -(14 * density).toInt()
            lp.marginEnd = -(14 * density).toInt()
            container.addView(scroller, lp)

            row.tiles.forEachIndexed { i, tile ->
                val cell = inflater.inflate(R.layout.home_cell, strip, false)
                cell.layoutParams = LinearLayout.LayoutParams(tileWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = gap
                }
                val v = cell.findViewById<View>(R.id.tile)
                val label = cell.findViewById<TextView>(R.id.cell_label)
                v.layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight)
                v.clipToOutline = true
                v.isFocusable = true
                v.isFocusableInTouchMode = true
                v.foreground = ctx.getDrawable(R.drawable.tile_focus)
                v.tag = row.key to tile.key()
                val namedInside = TileAdapter.bind(ctx, v, tile, tileHeight, visuals)
                label.text = when (tile) {
                    is Tile.App -> tile.entry.label
                    is Tile.Action -> tile.label
                }

                v.setOnClickListener { onClick(row, tile) }
                v.setOnLongClickListener { onMenu(row, tile); true }
                v.setOnFocusChangeListener { view, has ->
                    // Apple TV-style lift: grow, rise above the others, and show the name underneath
                    val s = if (has) 1.12f else 1f
                    view.animate().scaleX(s).scaleY(s).translationZ(if (has) 28f * density else 0f)
                        .setDuration(160).start()
                    if (!namedInside) label.animate().alpha(if (has) 1f else 0f).translationY(if (has) 6f * density else 0f).setDuration(160).start()
                    if (has) onRowFocused(thisRow)
                }
                strip.addView(cell)

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
                ((c.getChildAt(0) as? LinearLayout)?.getChildAt(0))?.findViewById<View>(R.id.tile)?.requestFocus()
                return
            }
        }
    }

    private fun labelOf(t: Tile) = when (t) {
        is Tile.App -> t.entry.label
        is Tile.Action -> t.label
    }
}
