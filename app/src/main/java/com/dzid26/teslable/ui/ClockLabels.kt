// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import java.time.Instant
import java.time.ZoneId

/** Round clock steps for the time axis: [stepHours] apart, counted from [originHours] (a local midnight). */
internal data class ClockSteps(
    val originHours: Double,
    val stepHours: Double,
) {
    /** The steps strictly inside [range], hours since the window start. */
    fun inside(range: ClosedFloatingPointRange<Double>): List<Double> =
        roundSteps((range.start - originHours)..(range.endInclusive - originHours), stepHours).map { it + originHours }
}

/**
 * The clock steps for a view [spanHours] wide whose window starts at [windowStart]: the smallest round step that
 * leaves about [CLOCK_LABELS] labels, counted from the local midnight before the window.
 */
internal fun clockSteps(
    windowStart: Long,
    spanHours: Double,
    zone: ZoneId = ZoneId.systemDefault(),
): ClockSteps {
    val step = CLOCK_STEP_HOURS.firstOrNull { it >= spanHours / CLOCK_LABELS } ?: CLOCK_STEP_HOURS.last()
    val midnight =
        Instant
            .ofEpochMilli(windowStart)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    return ClockSteps((midnight - windowStart) / MILLIS_PER_HOUR, step)
}

/** Labels and gridlines at [clock]'s steps inside the plotted range; the rest is [placer]'s. */
internal class ClockLabels(
    private val placer: HorizontalAxis.ItemPlacer,
    private val clock: ClockSteps,
) : HorizontalAxis.ItemPlacer by placer {
    override fun getLabelValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ): List<Double> = clock.inside(context.ranges.minX..context.ranges.maxX)

    override fun getLineValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ): List<Double> = getLabelValues(context, visibleXRange, fullXRange, maxLabelWidth)
}

/** Round label steps for a zoomed time axis, hours: 5, 10, 15 and 30 minutes, then hours, days and weeks. */
private val CLOCK_STEP_HOURS =
    listOf(1.0 / 12, 1.0 / 6, 0.25, 0.5, 1.0, 2.0, 3.0, 6.0, 12.0, 24.0, 48.0, 168.0, 336.0, 720.0)

/** About this many labels across a zoomed time axis. */
private const val CLOCK_LABELS = 3

private const val MILLIS_PER_HOUR = 3_600_000.0

/** Round clock times for the x labels while zoomed in; null at the full view, which keeps its aligned labels. */
@Composable
internal fun rememberClockSteps(
    window: Window,
    view: BatteryView,
    viewport: ChartViewport,
): ClockSteps? =
    remember(window, view.maxX - view.minX, viewport.isFull) {
        if (viewport.isFull) null else clockSteps(window.start, view.maxX - view.minX)
    }
