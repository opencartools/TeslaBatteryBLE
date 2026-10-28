// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.math.roundToInt

/**
 * The route the car is navigating right now, or null (#207).
 *
 * [latestRoute] is the car's newest drive reply, if it carried route fields
 * (the store drops a car's route as soon as a reply arrives without one, so a
 * finished or cancelled trip is gone). It counts as live while it still has a
 * distance left and was read recently, judged on the phone's clock
 * ([RouteSample.readAtMillis], else the car's own timestamp) against
 * [nowMillis], the phone's wall clock: the car answers a drive read every few
 * minutes while it navigates, so an older reading is a trip that ended with
 * the phone out of range. A route the car stopped reporting ([RouteSample.endedAtMillis]) stays for
 * [TRIP_END_GAP_MILLIS] after that, so a trip lost to a glitch or just finished does not vanish at once.
 */
fun activeRoute(
    latestRoute: RouteSample?,
    nowMillis: Long,
): RouteSample? =
    latestRoute?.takeIf { route ->
        route.milesToArrival != null &&
            nowMillis - route.observedAtMillis() <= ROUTE_FRESH_MILLIS &&
            route.isHeldAt(nowMillis)
    }

/**
 * This reading as the next one of a navigation whose previous reading was [previous] (null when
 * none, or when the previous reply carried no route): it keeps the longest remaining distance
 * seen so far as the trip's length, so a re-route that lengthens the trip stretches the bar
 * instead of running it backwards. A reading more than [ROUTE_FRESH_MILLIS] after the previous
 * one starts a new trip.
 */
fun RouteSample.continuing(previous: RouteSample?): RouteSample {
    val miles = milesToArrival?.takeIf { it.isFinite() && it >= 0f }
    val sameTrip = previous?.milesToArrival != null && !startsNewTrip(previous, this, previous.tripStartMiles ?: 0f)
    val start = listOfNotNull(if (sameTrip) previous.tripStartMiles else null, miles).maxOrNull()
    val startedAt = if (sameTrip) previous.tripStartedAtMillis ?: previous.observedAtMillis() else observedAtMillis()
    return copy(tripStartMiles = start, tripStartedAtMillis = startedAt)
}

/**
 * The whole trip's time as the car sees it now: what has passed since the navigation began plus
 * the time it still gives. Null without a start or a time left.
 */
fun RouteSample.tripTotalMillis(): Long? {
    val started = tripStartedAtMillis
    val left = minutesToArrival?.takeIf { it.isFinite() && it >= 0f }
    return if (started != null && left != null) observedAtMillis() - started + (left * MILLIS_PER_MINUTE).toLong() else null
}

/**
 * The traffic delay on this route in whole minutes, or null when there is none (or under half a
 * minute). The car's own minutes to arrival already count the traffic, as its navigation shows the
 * ETA with traffic; this is the share of them it blames on traffic, for "(+3m traffic)" next to the
 * time. The trip card and the notification both read it here.
 */
fun RouteSample.trafficMinutes(): Int? =
    trafficDelayMinutes
        ?.takeIf { it.isFinite() }
        ?.roundToInt()
        ?.takeIf { it > 0 }

/**
 * The traffic delay as a share of the whole trip's time, 0 to 1, or null without a delay. The whole
 * trip is [tripTotalMillis] (time already driven plus the time left), else the time left alone.
 */
fun RouteSample.trafficShare(): Float? {
    val delay = trafficMinutes() ?: return null
    val totalMillis = tripTotalMillis() ?: minutesToArrival?.takeIf { it.isFinite() && it > 0f }?.let { (it * MILLIS_PER_MINUTE).toLong() }
    return totalMillis?.takeIf { it > 0L }?.let { (delay * MILLIS_PER_MINUTE / it.toFloat()).coerceIn(0f, 1f) }
}

/** How far along the trip is, 0 to 1, or null when the length or the remaining distance is unknown or zero. */
fun RouteSample.tripProgress(): Float? {
    val start = tripStartMiles?.takeIf { it > 0f }
    val left = milesToArrival?.takeIf { it.isFinite() && it >= 0f }
    return if (start != null && left != null) (1f - left / start).coerceIn(0f, 1f) else null
}

/**
 * Whether [next] begins another navigation than [previous]: a gap over [ROUTE_FRESH_MILLIS], a different
 * destination name, or a longer route appearing right after the car had reached the last one (a new
 * trip started within minutes of arriving, the car naming nothing or the same place). [tripMiles] is the
 * length of the trip [previous] belongs to.
 */
internal fun startsNewTrip(
    previous: RouteSample,
    next: RouteSample,
    tripMiles: Float,
): Boolean =
    next.observedAtMillis() - previous.observedAtMillis() > ROUTE_FRESH_MILLIS ||
        namesDiffer(previous.destination, next.destination) ||
        restartedAfterArrival(previous, next, tripMiles)

private fun restartedAfterArrival(
    previous: RouteSample,
    next: RouteSample,
    tripMiles: Float,
): Boolean {
    val left = previous.milesToArrival?.takeIf { it.isFinite() && it >= 0f }
    val ahead = next.milesToArrival?.takeIf { it.isFinite() && it >= 0f }
    return left != null && ahead != null && reachedDestination(left, tripMiles) && ahead > left + RESTART_MILES
}

/**
 * Whether a last reading [remainingMiles] from the destination on a trip of [tripMiles] was already there:
 * within two miles or 15% of the route. The car stops sending the route on arrival, so the last reading is
 * never exactly zero. Inferred from the readings, not reported by the car.
 */
internal fun reachedDestination(
    remainingMiles: Float,
    tripMiles: Float,
): Boolean = remainingMiles <= maxOf(ARRIVED_MILES, tripMiles * ARRIVED_FRACTION)

private fun namesDiffer(
    a: String?,
    b: String?,
): Boolean = a != null && b != null && a != b

private const val ARRIVED_MILES = 2f
private const val ARRIVED_FRACTION = 0.15f

/** A route this much longer than where the last one ended is a new trip, not a re-route. */
private const val RESTART_MILES = 1f

/** The phone clock when this reading arrived, else the car's own timestamp. */
fun RouteSample.observedAtMillis(): Long = readAtMillis ?: timestampMillis

/** A route reading older than this is a finished trip: the car answers a drive read every few minutes while it navigates. */
internal const val ROUTE_FRESH_MILLIS = 10 * 60_000L

/**
 * How long a trip is kept after the car stops reporting it: the trip card and notification stay up, and a trip to the
 * same place is not announced again.
 */
const val TRIP_END_GAP_MILLIS = 2 * 60_000L

private const val MILLIS_PER_MINUTE = 60_000L
