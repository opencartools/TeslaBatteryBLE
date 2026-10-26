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
