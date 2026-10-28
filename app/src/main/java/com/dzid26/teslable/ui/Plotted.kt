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

/** The pairs of adjacent readings of one trip where the percent rose and the later one was regenerating. */
internal fun regenGains(points: List<Plotted>): List<List<Plotted>> =
    points.zipWithNext().filter { (a, b) -> a.trip == b.trip && b.regenerating && b.value > a.value }.map { it.toList() }
