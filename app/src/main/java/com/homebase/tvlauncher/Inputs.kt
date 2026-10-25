package com.homebase.tvlauncher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/**
 * The TV's physical inputs (HDMI 1-3, AV) as home-screen tiles.
 * Uses the standard Android TV input framework, so names set on the TV
 * (or reported by HDMI-CEC, e.g. "PlayStation 5") show up automatically.
 */
object Inputs {

    data class Input(val id: String, val label: String, val type: Int)

    fun list(ctx: Context): List<Input> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val infos = runCatching { tim.tvInputList }.getOrNull() ?: return emptyList()
        return infos
            .filter { it.isPassthroughInput && !isHidden(ctx, it) }
            // Skip CEC child devices: their parent HDMI port is already listed
            .filter { it.parentId == null || infos.none { p -> p.id == it.parentId && p.isPassthroughInput } }
            .map { Input(it.id, label(ctx, it), it.type) }
            .sortedWith(compareBy({ typeOrder(it.type) }, { it.label }))
    }

    fun open(ctx: Context, inputId: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
        if (AppRepo.safeStart(ctx, intent)) return true
        // Fallback: open TCL's own TV app, which remembers the last input
        return AppRepo.launch(ctx, "com.tcl.tv")
    }

    private fun label(ctx: Context, info: TvInputInfo): String {
        val custom = runCatching { info.loadCustomLabel(ctx) }.getOrNull()?.toString()
        if (!custom.isNullOrBlank()) return custom
        return runCatching { info.loadLabel(ctx).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: when (info.type) {
                TvInputInfo.TYPE_HDMI -> "HDMI"
                TvInputInfo.TYPE_COMPOSITE -> "AV"
                else -> "Input"
            }
    }

    private fun isHidden(ctx: Context, info: TvInputInfo) = runCatching { info.isHidden(ctx) }.getOrDefault(false)

    private fun typeOrder(type: Int) = when (type) {
        TvInputInfo.TYPE_HDMI -> 0
        TvInputInfo.TYPE_COMPONENT -> 1
        TvInputInfo.TYPE_COMPOSITE -> 2
        else -> 3
    }
}
