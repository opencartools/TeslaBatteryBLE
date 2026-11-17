// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.dzid26.teslable.R

/** What a shaded stretch means: the glyph its icon carries, and the explanation the icon opens. */
internal enum class BandKind(
    val glyph: String,
    @param:StringRes val hint: Int,
) {
    /** The parked drain's power-down stretches. */
    PARKED("P", R.string.history_power_down_sign_hint),
}

/** A stretch of the plot to shade, in hours since the window start, and the kind that names it. */
internal data class Band(
    val startHours: Double,
    val endHours: Double,
    val kind: BandKind,
)

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

/**
 * [bands] as icons: one per kind, on the part of the band that is on screen, centred under the
 * plot's top [top]. A band narrower than the full icon scales it down to fit, with [IconStyle.airPx]
 * of clearance each side, down to [IconStyle.minSizePx]; a band too narrow for even that is left
 * plain. The band is cut to [visible] first, so a stretch wider than the view keeps its icon on the
 * plot instead of centring it on the hidden part.
 */
internal fun bandIcons(
    bands: List<Band>,
    visible: ClosedFloatingPointRange<Double>,
    canvasX: (Double) -> Float,
    top: Float,
    style: IconStyle,
): List<BandIcon> =
    bands.mapNotNull { band ->
        val start = canvasX(band.startHours.coerceIn(visible.start, visible.endInclusive))
        val end = canvasX(band.endHours.coerceIn(visible.start, visible.endInclusive))
        val left = minOf(start, end)
        val right = maxOf(start, end)
        val size = minOf(style.sizePx, right - left - 2 * style.airPx)
        if (size < style.minSizePx) return@mapNotNull null
        val iconLeft = (left + right) / 2 - size / 2
        BandIcon(band.kind, iconLeft, top, iconLeft + size, top + size)
    }
