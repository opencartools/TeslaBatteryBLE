// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.health.TripEstimate
import com.dzid26.teslable.core.health.chargeSessionCost
import com.dzid26.teslable.core.health.tripEstimate

/**
 * One navigation (#207), finished or in progress: the route readings the car sent while it was
 * guiding to one destination, with no gap over [ROUTE_FRESH_MILLIS] between them.
 * Held in memory from the drive log, so the destination name is shown but never
 * copied into a history view of its own.
 */
data class Trip(
    /** The first reading: the trip as the car saw it at the start. */
    val first: RouteSample,
    /** The last reading: the car's closest prediction of the arrival. */
    val last: RouteSample,
    /** The longest remaining distance seen, in miles: the planned length of the trip, kept even when [distanceMiles] is what was driven. */
    val lengthMiles: Float?,
    /** True while this is the route being navigated now: its totals are the car's prediction so far. */
    val inProgress: Boolean = false,
) {
    /** The phone clock at the start of the trip, millis. */
    val startMillis: Long get() = first.observedAtMillis()

    /** The destination the car named, or null when it sent none. */
    val destination: String? get() = (last.destination ?: first.destination)?.takeIf { it.isNotBlank() }

    /**
     * The trip's planned time, from the first reading: the car's estimate before any driving.
     * Not shown today; kept so a plan-versus-actual comparison needs no new data.
     */
    val plannedMillis: Long?
        get() = first.minutesToArrival?.takeIf { it.isFinite() && it >= 0f }?.let { (it * MILLIS_PER_MINUTE).toLong() }

    /**
     * True when the last reading was already close to the destination (within two miles or
     * 15% of the route, or three minutes), so the trip reached it; the car stops sending the
     * route on arrival, so the last reading is never exactly zero. A trip that was cancelled
     * partway is not. Inferred from the readings, not reported by the car.
     */
    val arrived: Boolean
        get() {
            val miles = last.milesToArrival?.takeIf { it.isFinite() && it >= 0f }
            val minutes = last.minutesToArrival?.takeIf { it.isFinite() && it >= 0f }
            val closeByMiles = miles != null && reachedDestination(miles, lengthMiles ?: 0f)
            return closeByMiles || (minutes != null && minutes <= ARRIVED_MINUTES)
        }

    /**
     * The distance in miles: for a trip in progress the whole route as planned, for a finished one
     * what was actually driven, from the odometer when both readings carry it, else the route
     * length less what was still left at the last reading. A cancelled trip is therefore short.
     */
    val distanceMiles: Float?
        get() {
            val start = first.odometerInHundredthsOfAMile
            val end = last.odometerInHundredthsOfAMile
            val remaining = last.milesToArrival?.takeIf { it.isFinite() && it >= 0f } ?: 0f
            return when {
                inProgress -> lengthMiles
                start != null && end != null && end >= start -> (end - start) / HUNDREDTHS_PER_MILE
                lengthMiles != null -> (lengthMiles - remaining).coerceAtLeast(0f)
                else -> null
            }
        }

    /**
     * From the first reading to the last, plus the time the car still gave when the trip is in
     * progress or reached its destination; a cancelled trip counts only the time it was driven.
     */
    val durationMillis: Long
        get() {
            val elapsed = last.observedAtMillis() - first.observedAtMillis()
            val left = last.minutesToArrival?.takeIf { it.isFinite() && it > 0f } ?: 0f
            return elapsed + if (inProgress || arrived) (left * MILLIS_PER_MINUTE).toLong() else 0L
        }
}

/**
 * The finished trips in [readings], oldest first. A trip is two or more readings of
 * the same destination, each within [ROUTE_FRESH_MILLIS] of the one before; a single
 * reading is a navigation cancelled at once and is left out. A waypoint leg (the car names the
 * waypoint, then goes back to the real destination) stays inside the trip it interrupted.
 */
fun segmentTrips(readings: List<RouteSample>): List<Trip> {
    val runs = mutableListOf<MutableList<RouteSample>>()
    var runMiles = 0f
    var open = false
    for (reading in readings.sortedBy { it.observedAtMillis() }) {
        val miles = reading.milesToArrival?.takeIf { it.isFinite() && it >= 0f }
        val previous = runs.lastOrNull()?.last()
        when {
            // A reading with no distance left is the route ending (the car still names it for a moment): it closes the trip.
            miles == null -> open = false
            open && previous != null && !startsNewTrip(previous, reading, runMiles) -> {
                runs.last() += reading
                runMiles = maxOf(runMiles, miles)
            }
            else -> {
                runs += mutableListOf(reading)
                runMiles = miles
                open = true
            }
        }
    }
    return runs
        .withWaypointsMerged()
        .filter { it.size >= 2 }
        .map { run ->
            Trip(run.first(), run.last(), run.mapNotNull { it.milesToArrival }.maxOrNull())
        }.filter { it.moved() }
}

/**
 * False for a navigation the car sat through without moving (a route left active while parked): the
 * odometer at its first and last readings is the same. A trip without odometer readings counts as moved.
 */
private fun Trip.moved(): Boolean {
    val start = first.odometerInHundredthsOfAMile
    val end = last.odometerInHundredthsOfAMile
    return start == null || end == null || (end - start) / HUNDREDTHS_PER_MILE >= MIN_TRIP_MILES
}

/** These trips with the one whose newest reading is [live], the route being navigated now, marked in progress. */
fun List<Trip>.markingLive(live: RouteSample?): List<Trip> =
    if (live == null) this else map { it.copy(inProgress = it.last.observedAtMillis() == live.observedAtMillis()) }

/**
 * What a trip took: the totals as of its last reading. The level and the energy come from the
 * battery readings around the trip, and from the fitted full range only when a reading is
 * missing; an input that is missing hides its figure rather than showing a guess.
 */
data class TripSummary(
    /** The energy, cost and arrival level (see [tripEstimate]) on the trip's distance. */
    val estimate: TripEstimate,
    /** The level the trip ended at: the battery reading just after it, else the car's prediction when it reached its destination. */
    val arrivalPercent: Float?,
    /** The distance shown: driven for a finished trip, the whole route for one in progress. */
    val distanceMiles: Float?,
    /** Distance over duration, miles per hour. */
    val averageMilesPerHour: Float?,
    /** The energy over the distance, kWh per mile. */
    val kwhPerMile: Float?,
)

/**
 * The summary of this trip from the battery readings, the full-range scale, the learned rated
 * constant and the home price. A finished trip with a battery reading before and after it is
 * priced from those two rated ranges, so it does not depend on the scale being pinned.
 */
fun Trip.summary(
    samples: List<BatterySample>,
    scale: FullRangeScale?,
    ratedKwhPerMile: Float?,
    pricePerKwh: Float?,
): TripSummary {
    val before = sampleBefore(samples, first.timestampMillis)
    val after = if (inProgress) null else sampleAfter(samples, last.timestampMillis + if (arrived) durationLeftMillis() else 0L)
    val estimate = estimateFor(before, after, scale, ratedKwhPerMile, pricePerKwh)
    val miles = distanceMiles?.takeIf { it > 0f }
    val hours = durationMillis.takeIf { it >= MILLIS_PER_MINUTE }?.let { it / MILLIS_PER_HOUR }
    return TripSummary(
        estimate = estimate,
        arrivalPercent = estimate.arrivalPercent,
        distanceMiles = distanceMiles,
        averageMilesPerHour = if (miles != null && hours != null) miles / hours else null,
        kwhPerMile = if (miles != null && miles >= MIN_RATE_MILES) estimate.energyKwh?.div(miles) else null,
    )
}

private fun Trip.estimateFor(
    before: BatterySample?,
    after: BatterySample?,
    scale: FullRangeScale?,
    ratedKwhPerMile: Float?,
    pricePerKwh: Float?,
): TripEstimate {
    val beforeMiles = before?.ratedRangeMiles
    val afterMiles = after?.ratedRangeMiles
    if (after != null && beforeMiles != null && afterMiles != null) {
        val usedMiles = beforeMiles - afterMiles
        val constant = ratedKwhPerMile?.takeIf { it.isFinite() && it > 0f }
        val energyKwh = constant?.let { usedMiles * it }?.takeIf { it > 0f }
        return TripEstimate(distanceMiles, after.soc(scale), afterMiles, energyKwh, chargeSessionCost(energyKwh, pricePerKwh))
    }
    // The car's last prediction, while it still points at where the trip went (in progress, or arrived).
    val predicted = (last.energyAtArrivalPercent ?: first.energyAtArrivalPercent).takeIf { inProgress || arrived }
    return tripEstimate(
        first.copy(milesToArrival = distanceMiles, energyAtArrivalPercent = after?.soc(scale) ?: predicted),
        beforeMiles,
        scale,
        ratedKwhPerMile,
        pricePerKwh,
    )
}

private fun Trip.durationLeftMillis(): Long {
    val minutes = last.minutesToArrival?.takeIf { it.isFinite() && it > 0f } ?: 0f
    return (minutes * MILLIS_PER_MINUTE).toLong()
}

/** The newest reading at or before [atMillis], if it is under half an hour old. */
private fun sampleBefore(
    samples: List<BatterySample>,
    atMillis: Long,
): BatterySample? =
    samples
        .filter { it.timestampMillis <= atMillis }
        .maxByOrNull { it.timestampMillis }
        ?.takeIf { atMillis - it.timestampMillis <= SAMPLE_MAX_AGE_MILLIS }

/** The first reading at or after [atMillis], if it is under half an hour later. */
private fun sampleAfter(
    samples: List<BatterySample>,
    atMillis: Long,
): BatterySample? =
    samples
        .filter { it.timestampMillis >= atMillis }
        .minByOrNull { it.timestampMillis }
        ?.takeIf { it.timestampMillis - atMillis <= SAMPLE_MAX_AGE_MILLIS }

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000f
private const val HUNDREDTHS_PER_MILE = 100f

/** A navigation that moved the car less than this is not a trip. */
private const val MIN_TRIP_MILES = 0.1f
private const val ARRIVED_MINUTES = 3f
private const val SAMPLE_MAX_AGE_MILLIS = 30 * MILLIS_PER_MINUTE

/** A rate over less than this is noise: the efficiency of a few hundred metres means nothing. */
private const val MIN_RATE_MILES = 1f
