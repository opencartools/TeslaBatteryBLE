// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.dzid26.teslable.R

/** Where a kind's mark sits in the plot: the top or the bottom, so overlapping bands keep both marks apart. */
internal enum class BandSlot { TOP, BOTTOM }

/** Sleep reads as night, climate as amber; both are fixed, like the parking sign's blue. */
internal val ASLEEP_TINT = Color(0xFF5C6BC0)
internal val CLIMATE_TINT = Color(0xFFFFB300)
private val PARKING_BLUE = Color(0xFF1565C0)

/**
 * What a shaded stretch means: the mark its icon draws, its tile's tint, and the explanation the icon opens.
 * Declaration order is the paint order: a later kind covers an earlier one.
 */
internal enum class BandKind(
    val slot: BandSlot,
    val tile: Color,
    @param:StringRes val hint: Int,
) {
    /** The parked drain's power-down stretches: a parking sign at the bottom. */
    PARKED(BandSlot.BOTTOM, PARKING_BLUE, R.string.history_power_down_sign_hint),

    /** Stretches the status log says the car was asleep: a moon at the top. */
    ASLEEP(BandSlot.TOP, ASLEEP_TINT, R.string.history_asleep_band_hint),

    /** Stretches the cabin climate ran: a snowflake at the top. */
    CLIMATE(BandSlot.TOP, CLIMATE_TINT, R.string.history_climate_band_hint),
}

/** A stretch of the plot to shade, in hours since the window start, and the kind that names it. */
internal data class Band(
    val startHours: Double,
    val endHours: Double,
    val kind: BandKind,
    /** The parts of this band the log lost, in the band's hours; drawn as stripes. */
    val unseen: List<ClosedFloatingPointRange<Double>> = emptyList(),
) {
    /** The parts of the band in [from]..[to] the log witnessed, with the unseen holes cut out. */
    fun witnessedIn(
        from: Double,
        to: Double,
    ): List<ClosedFloatingPointRange<Double>> {
        val parts = mutableListOf<ClosedFloatingPointRange<Double>>()
        var start = from
        unseen.forEach { hole ->
            val holeStart = maxOf(hole.start, from)
            val holeEnd = minOf(hole.endInclusive, to)
            if (holeEnd > holeStart) {
                if (holeStart > start) parts += start..holeStart
                start = maxOf(start, holeEnd)
            }
        }
        if (to > start) parts += start..to
        return parts
    }
}

/** How big a band's icon is, how far under the plot's top it sits, and the air and floor it scales between, px. */
internal data class IconStyle(
    val sizePx: Float,
    val insetPx: Float,
    val airPx: Float,
    val minSizePx: Float,
)

/** An icon drawn on a band, in the chart's px. */
internal data class BandIcon(
    val kind: BandKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Whether a touch at [position] is on this icon, with [slop] px of finger allowance around it. */
    fun touches(
        position: Offset,
        slop: Float,
    ): Boolean {
        val target = Rect(left, top, right, bottom).inflate(slop)
        return position in target
    }
}

/**
 * The band icons of the last draw and the one whose explanation is open. The icons are written
 * while drawing, like the chart's other measured geometry, so only touches read them; [open] is
 * state, since opening the explanation is a tap and a tap may recompose.
 */
@Stable
internal class BandIcons {
    var icons: List<BandIcon> = emptyList()

    var open by mutableStateOf<BandIcon?>(null)

    /** The icon a touch at [position] lands on, with [slop] px of finger allowance; null for none. */
    fun iconAt(
        position: Offset,
        slop: Float,
    ): BandIcon? = icons.firstOrNull { it.touches(position, slop) }
}

/** The plot's top and bottom edges, px: where the two kinds of slot put their marks. */
internal data class IconEdges(
    val top: Float,
    val bottom: Float,
)

/**
 * [bands] as icons: one per kind, on the part of the band that is on screen, at its kind's slot —
 * under [edges]' top for the top slot, above its bottom for the bottom one. The mark sits on the
 * widest stretch of the band the log witnessed, so a bridged sleep's mark avoids its striped hole.
 * A band narrower than the full icon scales it down to fit, with [IconStyle.airPx] of clearance each
 * side, down to [IconStyle.minSizePx]; a band too narrow for even that is left plain. The band is
 * cut to [visible] first, so a stretch wider than the view keeps its icon on the plot instead of
 * centring it on the hidden part.
 */
internal fun bandIcons(
    bands: List<Band>,
    visible: ClosedFloatingPointRange<Double>,
    canvasX: (Double) -> Float,
    edges: IconEdges,
    style: IconStyle,
): List<BandIcon> =
    bands.mapNotNull { band ->
        val from = band.startHours.coerceIn(visible.start, visible.endInclusive)
        val to = band.endHours.coerceIn(visible.start, visible.endInclusive)
        val shown = if (to > from) band.witnessedIn(from, to).maxByOrNull { it.endInclusive - it.start } else null
        if (shown == null) return@mapNotNull null
        val start = canvasX(shown.start)
        val end = canvasX(shown.endInclusive)
        val left = minOf(start, end)
        val right = maxOf(start, end)
        val size = minOf(style.sizePx, right - left - 2 * style.airPx)
        if (size < style.minSizePx) return@mapNotNull null
        val iconLeft = (left + right) / 2 - size / 2
        val iconTop = if (band.kind.slot == BandSlot.TOP) edges.top else edges.bottom - size
        BandIcon(band.kind, iconLeft, iconTop, iconLeft + size, iconTop + size)
    }
