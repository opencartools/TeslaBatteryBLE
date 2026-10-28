// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripTest {
    private fun reading(
        minute: Long,
        miles: Float?,
        destination: String? = "Home",
        minutesLeft: Float? = null,
        odometer: Int? = null,
    ) = RouteSample(
        timestampMillis = minute * MINUTE,
        vehicleId = "",
        destination = destination,
        milesToArrival = miles,
        minutesToArrival = minutesLeft,
        trafficDelayMinutes = 0f,
        energyAtArrivalPercent = 44f,
        odometerInHundredthsOfAMile = odometer,
    )

    @Test
    fun `readings a few minutes apart are one trip with its longest distance as the length`() {
        val trips = segmentTrips(listOf(reading(0, 10f), reading(3, 7f), reading(6, 1f, minutesLeft = 5f)))
        assertEquals(1, trips.size)
        assertEquals(10f, trips.single().lengthMiles)
        assertEquals("Home", trips.single().destination)
        assertEquals(6 * MINUTE + 5 * MINUTE, trips.single().durationMillis)
    }

    @Test
    fun `a gap over ten minutes or a new destination starts another trip`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 10f),
                    reading(3, 7f),
                    reading(60, 5f, "Work"),
                    reading(63, 3f, "Work"),
                    reading(66, 9f, "Gym"),
                    reading(69, 6f, "Gym"),
                ),
            )
        assertEquals(listOf("Home", "Work", "Gym"), trips.map { it.destination })
    }

    @Test
    fun `a single reading is not a trip`() {
        assertEquals(emptyList(), segmentTrips(listOf(reading(0, 3f))))
    }

    @Test
    fun `the trip whose newest reading is the live route is marked in progress`() {
        val readings = listOf(reading(0, 10f), reading(3, 7f), reading(60, 5f), reading(63, 3f))
        val trips = segmentTrips(readings)
        assertEquals(listOf(false, true), trips.markingLive(readings.last()).map { it.inProgress })
        assertEquals(listOf(false, false), trips.markingLive(null).map { it.inProgress })
    }

    @Test
    fun `a finished trip arrives at the battery reading after it, else the car's prediction`() {
        val trip =
            segmentTrips(
                listOf(reading(0, 10f, minutesLeft = 20f), reading(10, 5f, minutesLeft = 10f), reading(20, 0f, minutesLeft = 0f)),
            ).single()
        val samples =
            listOf(
                BatterySample(-5 * MINUTE, 60, null, null, ratedRangeMiles = 180f),
                BatterySample(22 * MINUTE, 50, null, null, ratedRangeMiles = 150f),
            )
        val summary = trip.summary(samples, scale, ratedKwhPerMile = 0.25f, pricePerKwh = 0.30f)
        assertEquals(50f, summary.arrivalPercent)
        // 10 mi in 20 minutes.
        assertEquals(30f, summary.averageMilesPerHour!!, 0.01f)
        assertEquals(44f, trip.summary(emptyList(), scale, null, null).arrivalPercent)
    }

    @Test
    fun `a cancelled trip counts the miles driven and the time spent, not the plan`() {
        val trip = segmentTrips(listOf(reading(0, 10f, minutesLeft = 20f), reading(10, 6f, minutesLeft = 12f))).single()
        assertEquals(false, trip.arrived)
        assertEquals(4f, trip.distanceMiles)
        assertEquals(10 * MINUTE, trip.durationMillis)
        val samples =
            listOf(
                BatterySample(-5 * MINUTE, 60, null, null, ratedRangeMiles = 180f),
                BatterySample(12 * MINUTE, 57, null, null, ratedRangeMiles = 171f),
            )
        // The car's arrival prediction was for the destination it never reached.
        assertNull(trip.summary(emptyList(), scale, null, null).arrivalPercent)
        assertEquals(57f, trip.summary(samples, scale, null, null).arrivalPercent)
    }

    @Test
    fun `a finished trip's distance comes from the odometer when it has one`() {
        val trip =
            segmentTrips(
                listOf(
                    reading(0, 10f, minutesLeft = 20f, odometer = 100_000),
                    reading(10, 1f, minutesLeft = 2f, odometer = 100_480),
                ),
            ).single()
        assertEquals(true, trip.arrived)
        assertEquals(4.8f, trip.distanceMiles!!, 0.001f)
    }

    @Test
    fun `the planned length and time stay on a cancelled trip`() {
        val trip = segmentTrips(listOf(reading(0, 10f, minutesLeft = 20f), reading(10, 6f, minutesLeft = 12f))).single()
        assertEquals(10f, trip.lengthMiles)
        assertEquals(20 * MINUTE, trip.plannedMillis)
        assertEquals(4f, trip.distanceMiles)
    }

    @Test
    fun `a trip in progress shows the whole route and its predicted time`() {
        val trip =
            segmentTrips(
                listOf(reading(0, 10f, minutesLeft = 20f), reading(5, 7f, minutesLeft = 14f)),
            ).single().copy(inProgress = true)
        assertEquals(10f, trip.distanceMiles)
        assertEquals(19 * MINUTE, trip.durationMillis)
    }

    @Test
    fun `a finished trip is priced from the two battery readings even without a pinned scale`() {
        val trip =
            segmentTrips(
                listOf(reading(0, 10f, minutesLeft = 20f), reading(10, 5f, minutesLeft = 10f), reading(20, 0f, minutesLeft = 0f)),
            ).single()
        val samples =
            listOf(
                BatterySample(-5 * MINUTE, 60, null, null, ratedRangeMiles = 180f),
                BatterySample(22 * MINUTE, 50, null, null, ratedRangeMiles = 150f),
            )
        val estimate = trip.summary(samples, scale = null, ratedKwhPerMile = 0.25f, pricePerKwh = 0.30f).estimate
        // 30 rated miles at 0.25 kWh/mi.
        assertEquals(7.5f, estimate.energyKwh!!, 0.001f)
        assertEquals(2.25f, estimate.costAtHomePrice!!, 0.001f)
        assertEquals(150f, estimate.arrivalRatedMiles)
    }

    @Test
    fun `a new route right after arriving is another trip even under the same name`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 10f, "Work"),
                    reading(8, 0.4f, "Work"),
                    reading(12, 11f, "Work"),
                    reading(20, 3f, "Work"),
                ),
            )
        assertEquals(2, trips.size)
        assertEquals(10f, trips[0].lengthMiles)
        assertEquals(11f, trips[1].lengthMiles)
    }

    @Test
    fun `a reading with no distance ends the trip so the next route is another one`() {
        // As on the phone: the route to work ends with readings that still name it but carry no distance,
        // and the route home starts a few minutes later.
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 4.7f, "Work"),
                    reading(10, 0.1f, "Work"),
                    reading(11, null, "Work"),
                    reading(12, null, null),
                    reading(18, 4.7f, "Home"),
                    reading(28, 0.1f, "Home"),
                ),
            )
        assertEquals(2, trips.size)
        assertEquals("Work", trips[0].destination)
        assertEquals("Home", trips[1].destination)
    }

    @Test
    fun `a navigation left active while parked is not a trip`() {
        val parked =
            segmentTrips(
                listOf(
                    reading(0, 4.7f, odometer = 7_730_000),
                    reading(20, 4.7f, odometer = 7_730_001),
                ),
            )
        assertEquals(emptyList(), parked)
        val driven = segmentTrips(listOf(reading(0, 4.7f, odometer = 7_730_000), reading(10, 1f, odometer = 7_730_400)))
        assertEquals(1, driven.size)
    }

    @Test
    fun `a re-route partway is still one trip`() {
        val trips = segmentTrips(listOf(reading(0, 10f), reading(5, 6f), reading(10, 9f), reading(15, 3f)))
        assertEquals(1, trips.size)
    }

    @Test
    fun `a waypoint leg stays inside the trip it interrupted`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 22f, "Depot", odometer = 231_500),
                    reading(6, 20f, "Depot", odometer = 231_700),
                    reading(12, 17.9f, "Depot", odometer = 231_900),
                    reading(12, 5.7f, "Shop A", odometer = 231_900),
                    reading(21, 0.02f, "Shop A", odometer = 232_100),
                    reading(22, 12.5f, "Depot", odometer = 232_100),
                    reading(30, 12.3f, "Depot", odometer = 232_100),
                    reading(30, 1.8f, "Shop B", odometer = 232_100),
                    reading(37, 0.03f, "Shop B", odometer = 232_290),
                    reading(37, 10.6f, "Depot", odometer = 232_290),
                    reading(42, 8f, "Depot", odometer = 232_500),
                    reading(52, 5f, "Depot", odometer = 232_800),
                    reading(59, 0.02f, "Depot", odometer = 233_360),
                ),
            )
        val trip = trips.single()
        assertEquals("Depot", trip.destination)
        assertEquals(18.6f, trip.distanceMiles!!, 0.01f)
    }

    @Test
    fun `waypoint legs can nest`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 20f, "A", odometer = 1_000),
                    reading(5, 15f, "A", odometer = 1_500),
                    reading(5, 6f, "B", odometer = 1_500),
                    reading(9, 4f, "B", odometer = 1_700),
                    reading(9, 3f, "C", odometer = 1_700),
                    reading(12, 0.1f, "C", odometer = 1_900),
                    reading(12, 9f, "B", odometer = 1_900),
                    reading(15, 0.1f, "B", odometer = 2_200),
                    reading(15, 9f, "A", odometer = 2_200),
                    reading(25, 0.1f, "A", odometer = 3_000),
                ),
            )
        assertEquals(listOf("A"), trips.map { it.destination })
    }

    @Test
    fun `a waypoint close to the destination is still inside the trip`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 4f, "Work", odometer = 1_000),
                    reading(4, 1.1f, "Work", minutesLeft = 5.2f, odometer = 1_359),
                    reading(5, 0.5f, "Shop", odometer = 1_360),
                    reading(7, 0.02f, "Shop", odometer = 1_410),
                    reading(8, 0.9f, "Work", odometer = 1_410),
                    reading(11, 0.02f, "Work", odometer = 1_466),
                ),
            )
        assertEquals(listOf("Work"), trips.map { it.destination })
    }

    @Test
    fun `a waypoint dropped before it is reached is still inside the trip`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 10f, "Work", odometer = 1_000),
                    reading(4, 7f, "Work", odometer = 1_300),
                    reading(5, 3f, "Shop", odometer = 1_300),
                    reading(8, 2f, "Shop", odometer = 1_400),
                    reading(9, 5f, "Work", odometer = 1_400),
                    reading(14, 0.02f, "Work", odometer = 1_900),
                ),
            )
        assertEquals(listOf("Work"), trips.map { it.destination })
    }

    @Test
    fun `the old destination named once while parked at the waypoint is not the trip resuming`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 8f, "Home", odometer = 1_000),
                    reading(5, 4f, "Home", odometer = 1_400),
                    reading(5, 2f, "Shop", odometer = 1_400),
                    reading(9, 0.02f, "Shop", odometer = 1_620),
                    reading(10, 2.2f, "Home", odometer = 1_620),
                    reading(11, 2.2f, "Home", odometer = 1_621),
                ),
            )
        assertEquals(listOf("Home", "Shop"), trips.map { it.destination })
    }

    @Test
    fun `a trip home then out again within minutes is not a waypoint`() {
        val trips =
            segmentTrips(
                listOf(
                    reading(0, 5f, "Home"),
                    reading(5, 0.1f, "Home"),
                    reading(8, 3f, "Gym"),
                    reading(11, 0.1f, "Gym"),
                    reading(14, 4f, "Home"),
                    reading(18, 0.1f, "Home"),
                ),
            )
        assertEquals(listOf("Home", "Gym", "Home"), trips.map { it.destination })
    }

    @Test
    fun `energy rates need a distance worth dividing by`() {
        val short = segmentTrips(listOf(reading(0, 0.4f, minutesLeft = 2f), reading(2, 0f, minutesLeft = 0f))).single()
        val samples = listOf(BatterySample(-5 * MINUTE, 60, null, null, ratedRangeMiles = 180f))
        assertNull(short.summary(samples, scale, 0.25f, 0.30f).kwhPerMile)
    }

    @Test
    fun `an in-progress trip has no battery reading after it yet`() {
        val trip =
            segmentTrips(
                listOf(reading(0, 10f, minutesLeft = 20f), reading(5, 7f, minutesLeft = 14f)),
            ).single().copy(inProgress = true)
        val samples = listOf(BatterySample(30 * MINUTE, 40, null, null, ratedRangeMiles = 120f))
        assertEquals(44f, trip.summary(samples, scale, null, null).arrivalPercent)
    }

    private companion object {
        const val MINUTE = 60_000L
        val scale = FullRangeScale(milesPerPercent = 3.0f, offsetMiles = 0f, standardErrorMiles = 0.1f, samples = 40, pinned = true)
    }
}
