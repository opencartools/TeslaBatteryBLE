// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.withSave
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.math.abs

/*
 * Two fingers pan and zoom a chart sideways, as Android charts and maps do: spreading them widens what is under them,
 * moving them together slides it. Only the x axis zooms; the y axis fits whatever is on screen. One finger stays the
 * cursor (ChartScrub.kt).
 */

/** The part of a chart's full x range on screen, as fractions of it: 0 to 1 is the whole chart. */
@Stable
internal class ChartViewport(
    start: Double = 0.0,
    end: Double = 1.0,
) {
    var start by mutableDoubleStateOf(start)
        private set
    var end by mutableDoubleStateOf(end)
        private set

    val isFull: Boolean get() = start <= 0.0 && end >= 1.0

    /** Back to the whole chart: a tap on the range chip already chosen. */
    fun reset() {
        start = 0.0
        end = 1.0
    }

    /*
     * The plotting area in the chart's canvas, px, kept current by [ViewportTracker] while drawing. Deliberately not
     * Compose state: only the gesture handler reads them, and a state write from draw would recompose the chart on
     * every frame.
     */
    var layerLeft = 0f
    var layerWidth = 0f

    /** False in a right-to-left layout, where x runs from the right edge. */
    var ltr = true

    /** [lo] to [hi], the chart's full x range, cut to what is on screen. */
    fun visible(
        lo: Double,
        hi: Double,
    ): ClosedFloatingPointRange<Double> = (lo + start * (hi - lo))..(lo + end * (hi - lo))

    /**
     * One step of a two-finger gesture: the point under the fingers' previous midpoint [previousX] moves to [x], and the
     * view widens by the change in the fingers' horizontal distance. Spans under [minFingerSpan] px zoom nothing, so
     * fingers stacked vertically only pan.
     */
    fun transform(
        previousX: Float,
        x: Float,
        previousSpan: Float,
        span: Float,
        minFingerSpan: Float,
    ) {
        if (layerWidth <= 0f) return
        val scale = if (previousSpan >= minFingerSpan && span >= minFingerSpan) span / previousSpan else 1f
        val (newStart, newEnd) = panZoom(start, end, viewFraction(previousX), viewFraction(x), scale.toDouble())
        start = newStart
        end = newEnd
    }

    /** Where canvas x [px] falls across the plotting area, 0 at its start edge and 1 at its end edge. */
    private fun viewFraction(px: Float): Double {
        val fraction = ((px - layerLeft) / layerWidth).toDouble()
        return if (ltr) fraction else 1.0 - fraction
    }
}

/**
 * The view [start] to [end] (fractions of the full range) after a two-finger step: the full-range point at [from]
 * across the view lands at [to], and the view's width shrinks by [scale] (above 1 zooms in). The width stays between
 * [MIN_VIEW_SPAN] and the whole chart, and the view stays inside it.
 */
internal fun panZoom(
    start: Double,
    end: Double,
    from: Double,
    to: Double,
    scale: Double,
): Pair<Double, Double> {
    val span = end - start
    val anchor = start + from * span
    val newSpan = (span / scale).coerceIn(MIN_VIEW_SPAN, 1.0)
    val newStart = (anchor - to * newSpan).coerceIn(0.0, 1.0 - newSpan)
    return newStart to newStart + newSpan
}

/** Follows a two-finger gesture from [first], the event that brought the second finger, until every finger lifts. */
internal suspend fun AwaitPointerEventScope.followPinch(
    viewport: ChartViewport,
    first: PointerEvent,
    minFingerSpan: Float,
) {
    var event = first
    while (true) {
        val fingers = event.changes.filter { it.pressed && it.previousPressed }
        if (fingers.size >= 2) {
            val (a, b) = fingers
            viewport.transform(
                previousX = (a.previousPosition.x + b.previousPosition.x) / 2,
                x = (a.position.x + b.position.x) / 2,
                previousSpan = abs(a.previousPosition.x - b.previousPosition.x),
                span = abs(a.position.x - b.position.x),
                minFingerSpan = minFingerSpan,
            )
        }
        // Consumed, so the page does not scroll under a pinch.
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
        event = awaitPointerEvent()
    }
}

/** Keeps the zoom across a rotation, like the cursor; the plotting area is measured again on the next draw. */
internal val ChartViewportSaver =
    Saver<ChartViewport, DoubleArray>(save = { doubleArrayOf(it.start, it.end) }, restore = { ChartViewport(it[0], it[1]) })

/** A viewport that a change of any of [keys] (the range or window) resets, and that survives a rotation. */
@Composable
internal fun rememberChartViewport(vararg keys: Any?): ChartViewport =
    rememberSaveable(*keys, saver = ChartViewportSaver) { ChartViewport() }

/** Keeps [viewport]'s idea of the plotting area current, so a finger's px maps onto the chart. Draws nothing. */
internal class ViewportTracker(
    private val viewport: ChartViewport,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        viewport.layerLeft = context.layerBounds.left
        viewport.layerWidth = context.layerBounds.width
        viewport.ltr = context.isLtr
    }
}

/**
 * [decoration] cut to the plotting area while [viewport] is zoomed: Vico clips its lines but not decorations, which
 * would otherwise spill over the axes. At full view nothing is cut, so dots at the edges stay whole.
 */
internal class ClippedWhenZoomed(
    private val decoration: Decoration,
    private val viewport: ChartViewport,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) = clipped(context) { decoration.drawUnderLayers(context) }

    override fun drawOverLayers(context: CartesianDrawingContext) = clipped(context) { decoration.drawOverLayers(context) }

    private fun clipped(
        context: CartesianDrawingContext,
        draw: () -> Unit,
    ) {
        if (viewport.isFull) return draw()
        val bounds = context.layerBounds
        context.canvas.withSave {
            context.canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            draw()
        }
    }
}

/** The first index whose value is at least [x] (or above it, with [after]): a binary search of the ascending list. */
internal fun List<Double>.insertionPoint(
    x: Double,
    after: Boolean,
): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] < x || (after && this[mid] == x)) low = mid + 1 else high = mid
    }
    return low
}

/** The narrowest view, as a fraction of the whole chart: a fiftieth, so a week still zooms to a few hours. */
internal const val MIN_VIEW_SPAN = 0.02
