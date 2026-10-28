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

/** The pairs of adjacent readings of one trip where the rated range rose: energy came in between them. */
internal fun rangeGains(points: List<Plotted>): List<List<Plotted>> =
    points
        .zipWithNext()
        .filter { (a, b) -> a.trip == b.trip && a.ratedMiles != null && b.ratedMiles != null && b.ratedMiles > a.ratedMiles }
        .map { it.toList() }
