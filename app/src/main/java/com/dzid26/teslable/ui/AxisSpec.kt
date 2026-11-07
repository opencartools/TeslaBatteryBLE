// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Where the chart's axes start, end and step, in miles driven and in the plotted unit. */
internal class AxisSpec(
    val xStep: Double,
    val xMax: Double,
    val yStep: Double,
    val yMin: Double,
    val yMax: Double,
    /** Where the x axis starts: 0 at full view, further in once two fingers zoom it. */
    val xMin: Double = 0.0,
) {
    val range = paddedRange(yMin, yMax)
    val gridlines = generateSequence(yMin) { it + yStep }.takeWhile { it <= yMax + yStep / 2 }.toList()

    fun rangeProvider() = CartesianLayerRangeProvider.fixed(minX = xMin, maxX = xMax, minY = range.start, maxY = range.endInclusive)
}

internal fun axisSpec(points: List<Plotted>): AxisSpec {
    val span = points.last().distance
    val xStep = niceStep(span / X_LABELS)
    return fitted(points.map { it.value.toDouble() }, xMin = 0.0, xMax = ceil(span / xStep) * xStep, xStep = xStep)
}

/**
 * What [viewport] shows of the full axes [this], over [points] (by ascending distance, [distances] theirs): the
 * distances on screen, labelled in round steps, and a percent axis fitted to the points there and the nearest one past
 * each edge, which the line runs to. At full view, [this] unchanged.
 */
internal fun AxisSpec.view(
    points: List<Plotted>,
    distances: List<Double>,
    viewport: ChartViewport,
): AxisSpec {
    if (viewport.isFull) return this
    val shown = viewport.visible(0.0, xMax)
    val from = (distances.insertionPoint(shown.start, after = false) - 1).coerceAtLeast(0)
    val to = distances.insertionPoint(shown.endInclusive, after = true).coerceAtMost(points.lastIndex)
    val values = points.subList(from, to + 1).map { it.value.toDouble() }
    val xStep = niceStep((shown.endInclusive - shown.start) / X_LABELS)
    return fitted(values, xMin = shown.start, xMax = shown.endInclusive, xStep = xStep)
}

/** Axes over [xMin] to [xMax] with labels every [xStep], and a percent axis in round steps around [values]. */
private fun fitted(
    values: List<Double>,
    xMin: Double,
    xMax: Double,
    xStep: Double,
): AxisSpec {
    val lowest = values.min() - Y_PAD
    val highest = values.max() + Y_PAD
    val yStep = maxOf(niceStep((highest - lowest) / Y_LABELS), Y_MIN_STEP)
    val yMaxRaw = ceil(highest / yStep) * yStep
    return AxisSpec(
        xStep = xStep,
        xMax = xMax,
        yStep = yStep,
        yMin = (floor(lowest / yStep) * yStep).coerceAtLeast(0.0),
        yMax = minOf(yMaxRaw, PERCENT_FULL),
        xMin = xMin,
    )
}

/** 1, 2 or 5 times a power of ten: the axis step nearest above [raw]. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0.0) return 1.0
    val magnitude = DECADE.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, HALF_DECADE, DECADE).first { it * magnitude >= raw } * magnitude
}

private const val HALF_DECADE = 5.0
private const val DECADE = 10.0
private const val X_LABELS = 4
private const val Y_LABELS = 4
private const val Y_MIN_STEP = 1.0
private const val Y_PAD = 1.0
private const val PERCENT_FULL = 100.0
