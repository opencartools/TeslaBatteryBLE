// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * The active navigation route of one DriveState reading (#207), derived from a
 * logged [BleRecord] like [DriveSample] and on the car's own clock. Present only
 * while the car navigates.
 *
 * This read model keeps the destination name for live display only and leaves the
 * coordinates out. The raw DriveState, destination and coordinates included, is
 * still in the car's `.drive.pblog` (ADR-0008), so the log is where that location
 * data lives. [toString] redacts the name so a log line or a failed assertion
 * never prints it.
 */
data class RouteSample(
    /** The car's own `DriveState.timestamp`. */
    val timestampMillis: Long,
    /** Which vehicle the reading came from, as in [DriveSample.vehicleId]. */
    val vehicleId: String,
    /** `active_route_destination`: for the on-screen card; not copied into diagnostics or exports. */
    val destination: String?,
    /** `active_route_miles_to_arrival`: remaining route distance in miles. */
    val milesToArrival: Float?,
    /** `active_route_minutes_to_arrival`: remaining time. */
    val minutesToArrival: Float?,
    /** `active_route_traffic_minutes_delay`: traffic delay included in the remaining time. */
    val trafficDelayMinutes: Float?,
    /**
     * `active_route_energy_at_arrival`: the car's predicted battery level on arrival.
     * Read as a percent (Fleet Telemetry maps it to `ExpectedEnergyPercentAtTripArrival`);
     * the unit is not documented in the proto and still needs a real-car log to confirm.
     */
    val energyAtArrivalPercent: Float?,
    /**
     * The phone's clock when the reply was read (`device_timestamp`), or null when the record
     * has none. [timestampMillis] is the car's clock, which can be off by minutes; how recent a
     * route is, against the phone's own clock, is judged on this one.
     */
    val readAtMillis: Long? = null,
    /**
     * The longest remaining distance seen since this navigation began: the length of the trip
     * the progress bar measures against. Null on a bare reading; set by [continuing].
     */
    val tripStartMiles: Float? = null,
    /** The phone clock at this navigation's first reading, so the trip's total time is known; set by [continuing]. */
    val tripStartedAtMillis: Long? = null,
    /**
     * The phone clock when the car first answered without a route after this one: the navigation ended, and the trip
     * stays up for [TRIP_END_GAP_MILLIS] more (see [activeRoute]). Null while the car still reports this route.
     */
    val endedAtMillis: Long? = null,
    /** The car's odometer in this reading (`odometer_in_hundredths_of_a_mile`), for the distance actually driven. */
    val odometerInHundredthsOfAMile: Int? = null,
) {
    /** Whether this route is still the trip at [nowMillis]: the car still reports it, or stopped within [TRIP_END_GAP_MILLIS]. */
    fun isHeldAt(nowMillis: Long): Boolean = endedAtMillis.let { endedAt -> endedAt == null || nowMillis - endedAt <= TRIP_END_GAP_MILLIS }

    override fun toString(): String {
        val shownDestination = if (destination != null) "<redacted>" else "null"
        return "RouteSample(timestampMillis=$timestampMillis, vehicleId=$vehicleId, destination=$shownDestination, " +
            "milesToArrival=$milesToArrival, minutesToArrival=$minutesToArrival, " +
            "trafficDelayMinutes=$trafficDelayMinutes, energyAtArrivalPercent=$energyAtArrivalPercent)"
    }
}
