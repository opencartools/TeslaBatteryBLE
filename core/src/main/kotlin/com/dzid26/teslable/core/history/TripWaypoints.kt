// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * These runs with every waypoint leg folded into the trip it interrupted. While a waypoint is ahead
 * the car names it as the destination, and once there (or once it is dropped) it names the real
 * destination again, so a trip with stops arrives as A, B, A, or A, B, C, A, or nested as A, B, C, B, A.
 * A real leg to A (one that drove or reached its destination) carries on the first earlier leg to A
 * that had not reached it and ended within [WAYPOINT_STOP_MILLIS], when at least one real stop lies
 * between. Legs that are not real, routes only named while parked, never start or end a stop.
 */
internal fun List<MutableList<RouteSample>>.withWaypointsMerged(): List<MutableList<RouteSample>> {
    val trips = mutableListOf<Leg>()
    for (leg in map(::Leg)) {
        val anchor = trips.indexOfFirst { it.isResumedBy(leg) }
        val stops = if (anchor >= 0) trips.subList(anchor + 1, trips.size) else mutableListOf()
        if (leg.isReal && stops.any { it.isReal }) {
            trips[anchor].absorb(stops, leg)
        } else {
            trips += leg
        }
    }
    return trips.map { it.readings }
}

/** One run of route readings that name the same destination: a whole trip, or one leg of a trip with stops. */
private class Leg(
    val readings: MutableList<RouteSample>,
) {
    val destination: String? get() = readings.first().destination

    /** Whether the last reading was within a few hundred feet, or a minute, of the destination: tighter than [Trip.arrived]. */
    val reached: Boolean
        get() {
            val last = readings.last()
            val miles = last.milesToArrival?.takeIf { it.isFinite() && it >= 0f }
            val minutes = last.minutesToArrival?.takeIf { it.isFinite() && it >= 0f }
            return (miles != null && miles <= REACHED_MILES) || (minutes != null && minutes <= REACHED_MINUTES)
        }

    /** Whether the car drove at least [MIN_LEG_MILES] on this leg; true without odometer readings. */
    val drove: Boolean
        get() {
            val start = readings.first().odometerInHundredthsOfAMile
            val end = readings.last().odometerInHundredthsOfAMile
            return start == null || end == null || (end - start) / HUNDREDTHS_PER_MILE >= MIN_LEG_MILES
        }

    /** Drove or reached its destination: a leg of a trip, not a route only named while parked. */
    val isReal: Boolean get() = drove || reached

    /** Whether [later] names this leg's destination again soon after, with this trip unfinished: the trip carrying on after stops. */
    fun isResumedBy(later: Leg): Boolean =
        destination != null &&
            destination == later.destination &&
            !reached &&
            later.readings.first().observedAtMillis() - readings.last().observedAtMillis() <= WAYPOINT_STOP_MILLIS

    /** Takes in the [stops] between and the [resumed] leg, removing the stops from the list they belong to. */
    fun absorb(
        stops: MutableList<Leg>,
        resumed: Leg,
    ) {
        stops.forEach { readings += it.readings }
        stops.clear()
        readings += resumed.readings
    }
}

private const val HUNDREDTHS_PER_MILE = 100f

/** A leg this close to its destination had reached it, so the same name later is a new trip. */
private const val REACHED_MILES = 0.25f
private const val REACHED_MINUTES = 1f

/** A leg that drove less than this, and did not reach its destination, is not a real leg. */
private const val MIN_LEG_MILES = 0.25f

/** The longest stop that still keeps a trip together, the same as the drive chart's parked gap. */
private const val WAYPOINT_STOP_MILLIS = 30 * 60_000L
