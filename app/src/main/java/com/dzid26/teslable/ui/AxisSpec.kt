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
) {
    val range = paddedRange(yMin, yMax)
    val gridlines = generateSequence(yMin) { it + yStep }.takeWhile { it <= yMax + yStep / 2 }.toList()

    fun rangeProvider() = CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = xMax, minY = range.start, maxY = range.endInclusive)
}

internal fun axisSpec(points: List<Plotted>): AxisSpec {
    val span = points.last().distance
    val xStep = niceStep(span / X_LABELS)
    val lowest = points.minOf { it.value.toDouble() } - Y_PAD
    val highest = points.maxOf { it.value.toDouble() } + Y_PAD
    val yStep = maxOf(niceStep((highest - lowest) / Y_LABELS), Y_MIN_STEP)
    val yMaxRaw = ceil(highest / yStep) * yStep
    return AxisSpec(
        xStep = xStep,
        xMax = ceil(span / xStep) * xStep,
        yStep = yStep,
        yMin = (floor(lowest / yStep) * yStep).coerceAtLeast(0.0),
        yMax = minOf(yMaxRaw, PERCENT_FULL),
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
