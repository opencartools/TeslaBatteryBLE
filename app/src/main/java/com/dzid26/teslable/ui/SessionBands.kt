// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A session on the time chart: its span (the car's clock, millis), its colour, and whether it is picked. */
internal data class SessionBand(
    val startMillis: Long,
    val endMillis: Long,
    val color: Color,
    val selected: Boolean,
)

/**
 * The sessions behind the battery line: a faint full-height band per drive (orange) and charge (green), the picked one
 * stronger. A band is at least [MIN_BAND_WIDTH] wide, so a five-minute drive still shows on a week. Tapping one picks
 * that session (`proposals/sessions-and-chart.md`).
 */
internal class SessionBands(
    private val bands: List<SessionBand>,
    private val window: Window,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        if (bands.isEmpty()) return
        with(context) {
            val bounds = layerBounds
            val paint = Paint()
            val minWidth = MIN_BAND_WIDTH.pixels
            bands.forEach { band ->
                val a = canvasX(window.hours(band.startMillis))
                val b = canvasX(window.hours(band.endMillis))
                var left = min(a, b)
                var right = max(a, b)
                if (right - left < minWidth) {
                    val middle = (left + right) / 2
                    left = middle - minWidth / 2
                    right = middle + minWidth / 2
                }
                paint.color = band.color.copy(alpha = if (band.selected) SELECTED_ALPHA else BAND_ALPHA)
                canvas.drawRect(left, bounds.top, right, bounds.bottom, paint)
            }
        }
    }
}

/**
 * The x a tap at canvas [px] landed on, between [minX] and [maxX] (the chart's x range on screen), and how far
 * [slopPx] reaches in that unit; null until the chart has been laid out.
 */
internal fun ChartViewport.tapX(
    px: Float,
    minX: Double,
    maxX: Double,
    slopPx: Float,
): Pair<Double, Double>? {
    if (layerWidth <= 0f) return null
    val span = maxX - minX
    return (minX + viewFraction(px) * span) to (slopPx / layerWidth) * span
}

/**
 * [onTap] in the chart's terms: the canvas x of a tap turned into the time under it, with a finger's reach. The
 * handler stays the same object while the view pans and zooms, so the gesture under way is not restarted.
 */
@Composable
internal fun rememberTapMillis(
    onTap: ((millis: Long, slopMillis: Long) -> Boolean)?,
    viewport: ChartViewport,
    view: BatteryView,
    window: Window,
): ((Float) -> Boolean)? {
    val slopPx = with(LocalDensity.current) { TAP_REACH.toPx() }
    val toMillis by rememberUpdatedState { px: Float ->
        viewport.tapX(px, view.minX, view.maxX, slopPx)?.let { (hours, slopHours) ->
            window.start + (hours * MILLIS_PER_HOUR).roundToLong() to (slopHours * MILLIS_PER_HOUR).roundToLong()
        }
    }
    val tap by rememberUpdatedState(onTap)
    val hasTap = onTap != null
    return remember(hasTap) {
        if (!hasTap) {
            null
        } else {
            { px: Float -> toMillis(px)?.let { (millis, slop) -> tap?.invoke(millis, slop) } == true }
        }
    }
}

private const val MILLIS_PER_HOUR = 3_600_000.0

/** How far either side of a tap a session still counts as hit: about half a fingertip. */
private val TAP_REACH = 12.dp

private const val BAND_ALPHA = 0.10f
private const val SELECTED_ALPHA = 0.28f
private val MIN_BAND_WIDTH = 3.dp
