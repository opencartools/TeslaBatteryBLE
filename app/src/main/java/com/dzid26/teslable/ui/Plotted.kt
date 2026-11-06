// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

/**
 * One point on the chart: distance in the display unit, battery percent, regeneration, trip, when it was read and the
 * rated range in miles, whose changes decide the green dots and the gradient.
 */
internal class Plotted(
    val distance: Double,
    val value: Float,
    val regenerating: Boolean,
    val trip: Int,
    val timestampMillis: Long,
    val ratedMiles: Float? = null,
)

/**
 * The pairs of adjacent readings of one trip that the car drove between with the rated range not falling, and at least one
 * of them is a green dot ([gainingDots]): the regeneration held the charge up between them.
 */
internal fun gainBands(points: List<Plotted>): List<List<Plotted>> {
    val green = gainingDots(points)
    return points
        .zipWithNext()
        .withIndex()
        .filter { (i, pair) ->
            val (a, b) = pair
            a.trip == b.trip && b.distance > a.distance && b.ratedMiles.notBelow(a.ratedMiles) && (green[i] || green[i + 1])
        }.map { it.value.toList() }
}

/**
 * For each reading, whether its dot is green: it regenerates, or the car moved and the rated range rose from the previous
 * reading of the same trip. A rise while standing is the estimate settling, not regeneration.
 */
internal fun gainingDots(points: List<Plotted>): List<Boolean> =
    points.mapIndexed { i, p ->
        val previous = points.getOrNull(i - 1)?.takeIf { it.trip == p.trip }
        p.regenerating || (previous != null && p.distance > previous.distance && p.ratedMiles.above(previous.ratedMiles))
    }

/** True when both ranges are known and this one is above [earlier]. */
private fun Float?.above(earlier: Float?): Boolean = this != null && earlier != null && this > earlier

/** True when both ranges are known and this one is not below [earlier]. */
private fun Float?.notBelow(earlier: Float?): Boolean = this != null && earlier != null && this >= earlier
