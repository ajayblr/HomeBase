package com.homebase.tvlauncher

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/** Grid adapter for the All apps screen. */
class TileAdapter(private val ctx: Context, private val tileHeight: Int) : BaseAdapter() {

    private var items: List<Tile> = emptyList()
    private val visuals = HashMap<String, Pair<Drawable, Boolean>>()

    fun submit(list: List<Tile>) {
        items = list
        notifyDataSetChanged()
    }

    fun clearCache() = visuals.clear()

    override fun getCount() = items.size
    override fun getItem(position: Int): Tile = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.tile, parent, false).also {
            it.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileHeight)
            it.clipToOutline = true
        }
        v.scaleX = 1f
        v.scaleY = 1f
        bind(ctx, v, items[position], tileHeight, visuals)
        return v
    }

    companion object {
        /** 16:9 tile height that fits [columns] tiles across the screen. */
        fun tileHeight(ctx: Context, columns: Int, sidePaddingDp: Int, spacingDp: Int): Int {
            val dm = ctx.resources.displayMetrics
            val usable = dm.widthPixels - (2 * sidePaddingDp + (columns - 1) * spacingDp) * dm.density
            return (usable / columns * 9f / 16f).toInt()
        }

        /**
         * Fills a tile.xml view for any kind of tile. Shared by the home rows and All apps.
         * Returns true if the tile shows its own name inside it (icon tiles); banner tiles don't.
         */
        fun bind(
            ctx: Context,
            v: View,
            tile: Tile,
            tileHeight: Int,
            cache: MutableMap<String, Pair<Drawable, Boolean>>
        ): Boolean {
            val image = v.findViewById<ImageView>(R.id.tile_image)
            val label = v.findViewById<TextView>(R.id.tile_label)
            val badge = v.findViewById<TextView>(R.id.tile_badge)
            when (tile) {
                is Tile.App -> {
                    val (drawable, isBanner) = cache.getOrPut(tile.entry.pkg) { AppRepo.visual(ctx, tile.entry) }
                    image.setImageDrawable(drawable)
                    label.text = tile.entry.label
                    if (isBanner) {
                        image.scaleType = ImageView.ScaleType.CENTER_CROP
                        image.setPadding(0, 0, 0, 0)
                        label.visibility = View.GONE
                    } else {
                        image.scaleType = ImageView.ScaleType.FIT_CENTER
                        val pad = tileHeight / 5
                        image.setPadding(pad, pad / 2, pad, pad + pad / 2)
                        label.visibility = View.VISIBLE
                    }
                    badge.visibility = if (tile.pinned) View.VISIBLE else View.GONE
                    return !isBanner
                }
                is Tile.Action -> {
                    image.setImageResource(tile.iconRes)
                    image.scaleType = ImageView.ScaleType.FIT_CENTER
                    val pad = tileHeight / 4
                    image.setPadding(pad, pad / 2, pad, pad + pad / 3)
                    label.text = tile.label
                    label.visibility = View.VISIBLE
                    badge.visibility = View.GONE
                    return true
                }
            }
        }
    }
}

/** Gently enlarges the selected tile in the All apps grid. */
class FocusZoom : AdapterView.OnItemSelectedListener {
    private var current: View? = null

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        current?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        view?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(120)?.start()
        current = view
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        current?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        current = null
    }
}
