// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

/** One point on the chart: distance in the display unit, battery percent, regeneration, trip and when it was read. */
internal class Plotted(
    val distance: Double,
    val value: Float,
    val regenerating: Boolean,
    val trip: Int,
    val timestampMillis: Long,
)

/** The stretches of regeneration: each maximal run of regenerating readings of one trip, a single reading included. */
internal fun regenRuns(points: List<Plotted>): List<List<Plotted>> {
    val runs = mutableListOf<MutableList<Plotted>>()
    var previous: Plotted? = null
    for (point in points) {
        if (point.regenerating) {
            val open = previous?.takeIf { it.regenerating && it.trip == point.trip }
            if (open != null) runs.last() += point else runs += mutableListOf(point)
        }
        previous = point
    }
    return runs
}
