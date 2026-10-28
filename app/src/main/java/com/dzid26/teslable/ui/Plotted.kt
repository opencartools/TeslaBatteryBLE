// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

/** One point on the chart: distance in the display unit, battery percent, regeneration, trip and when it was read. */
internal class Plotted(
    val distance: Double,
    val value: Float,
    val regenerating: Boolean,
    val trip: Int,
    val timestampMillis: Long,
    val ratedMiles: Float? = null,
)

/**
 * The pairs of adjacent readings of one trip that at least one regenerates, with the rated range not lower at the second: the
 * regeneration held the range up between them.
 */
internal fun regenHolds(points: List<Plotted>): List<List<Plotted>> =
    points.zipWithNext().filter { (a, b) -> a.trip == b.trip && (a.regenerating || b.regenerating) && b.holds(a) }.map { it.toList() }

/** True when both readings carry a rated range and this one is not lower than [earlier]. */
private fun Plotted.holds(earlier: Plotted): Boolean = ratedMiles != null && earlier.ratedMiles != null && ratedMiles >= earlier.ratedMiles
