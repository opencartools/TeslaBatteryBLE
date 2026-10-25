// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * Where along the whole trip, 0 to 1, the car's level is predicted to fall below [TRIP_RESERVE_PERCENT]: the
 * trip bar colors the stretch from there to the end as low. [currentPercent] is the level now and the arrival
 * level is the car's own; the level is taken to fall evenly over the distance left. Null when the level
 * stays above the reserve, or when the progress, the current level or the arrival level is unknown.
 */
fun RouteSample.tripLowFrom(currentPercent: Float?): Float? {
    val progress = tripProgress()
    val arrival = energyAtArrivalPercent?.takeIf { it.isFinite() }
    val current = currentPercent?.takeIf { it.isFinite() }
    return when {
        progress == null || arrival == null || current == null || arrival >= TRIP_RESERVE_PERCENT -> null
        current <= TRIP_RESERVE_PERCENT -> progress
        else -> progress + (1f - progress) * (current - TRIP_RESERVE_PERCENT) / (current - arrival)
    }
}

/** The level under which the trip bar shows the battery as low. */
const val TRIP_RESERVE_PERCENT = 20f
