// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * These runs with every waypoint leg folded into the run it interrupted. While a waypoint is
 * ahead the car names it as the destination, and once reached it names the next one again, so a
 * trip with stops arrives as A, B, A, or nested as A, B, C, B, A. A run that names a destination
 * seen before is the end of a waypoint span when the earlier run had not quite reached its own
 * destination, every run between reached its own or drove on (a waypoint dropped mid-drive counts, a
 * route picked and abandoned while parked does not), the later one drives on or has
 * more of the trip after it (not just the old destination named once while parked at the waypoint),
 * and each hand-over is within [ROUTE_FRESH_MILLIS].
 */
internal fun List<MutableList<RouteSample>>.withWaypointsMerged(): List<MutableList<RouteSample>> {
    val merged = mutableListOf<MutableList<RouteSample>>()
    forEachIndexed { index, run ->
        val start = waypointSpanStart(merged, run, resumes = run.drivesOn() || getOrNull(index + 1).startsWithin(run))
        if (start == null) {
            merged += run
        } else {
            val interrupted = merged[start]
            for (leg in merged.subList(start + 1, merged.size)) interrupted += leg
            interrupted += run
            while (merged.size > start + 1) merged.removeAt(merged.size - 1)
        }
    }
    return merged
}

/** The index of the run [run] resumes after waypoint legs, or null when it begins a trip of its own. */
private fun waypointSpanStart(
    runs: List<List<RouteSample>>,
    run: List<RouteSample>,
    resumes: Boolean,
): Int? {
    val destination = run.first().destination ?: return null
    val start = runs.indexOfLast { it.last().destination == destination }.takeIf { it in 0 until runs.size - 1 }
    val chain = start?.let { runs.subList(it, runs.size) + listOf(run) }
    val resumed =
        chain != null &&
            !chain.first().last().isAtDestination() &&
            resumes &&
            chain.drop(1).dropLast(1).all { it.drivesOn() || it.asTrip().arrived } &&
            chain.zipWithNext().all { (a, b) -> b.first().observedAtMillis() - a.last().observedAtMillis() <= ROUTE_FRESH_MILLIS }
    return start.takeIf { resumed }
}

/** Whether the car was within a few hundred feet, or a minute, of its destination: tighter than [Trip.arrived]. */
private fun RouteSample.isAtDestination(): Boolean {
    val miles = milesToArrival?.takeIf { it.isFinite() && it >= 0f }
    val minutes = minutesToArrival?.takeIf { it.isFinite() && it >= 0f }
    return (miles != null && miles <= AT_DESTINATION_MILES) || (minutes != null && minutes <= AT_DESTINATION_MINUTES)
}

private fun List<RouteSample>.asTrip() = Trip(first(), last(), mapNotNull { it.milesToArrival }.maxOrNull())

/** Whether this run, if any, starts within [ROUTE_FRESH_MILLIS] of the end of [previous]. */
private fun List<RouteSample>?.startsWithin(previous: List<RouteSample>): Boolean =
    this != null && first().observedAtMillis() - previous.last().observedAtMillis() <= ROUTE_FRESH_MILLIS

/** False for a run that only names the old destination again while parked at the waypoint: under [MIN_RESUMED_MILES] driven. */
private fun List<RouteSample>.drivesOn(): Boolean {
    val start = first().odometerInHundredthsOfAMile
    val end = last().odometerInHundredthsOfAMile
    return start == null || end == null || (end - start) / HUNDREDTHS_PER_MILE >= MIN_RESUMED_MILES
}

private const val HUNDREDTHS_PER_MILE = 100f

/** A run this close to its destination had reached it, so the next one is not the same trip resuming. */
private const val AT_DESTINATION_MILES = 0.25f
private const val AT_DESTINATION_MINUTES = 1f

/** The old destination named again after a waypoint counts as the trip resuming only once it has driven this far. */
private const val MIN_RESUMED_MILES = 0.25f
