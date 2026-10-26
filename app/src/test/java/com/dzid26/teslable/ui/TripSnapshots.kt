// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.core.health.tripEstimate
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.RouteSample
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Paparazzi snapshots of the trip cards (#207): the live trip card and the past-trips card,
 * in the card flow of [CardSnapshots] (light, dark and Jelly Star width) with a fixed clock.
 * The full-screen render with a live route stays with the other screens in [CardSnapshots].
 */
class TripSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Dates read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun tripCardNavigating() = snapTrip("trip-card_navigating")

    @Test
    fun tripCardTraffic() = snapTrip("trip-card_traffic", tripRoute(trafficDelayMinutes = 2.4f))

    @Test
    fun tripCardHeavyTraffic() = snapTrip("trip-card_heavy-traffic", tripRoute(trafficDelayMinutes = 8f))

    @Test
    fun tripCardNoPrice() = snapTrip("trip-card_no-price", pricePerKwh = null)

    @Test
    fun tripCardUnpinnedScale() = snapTrip("trip-card_unpinned", scale = null)

    @Test
    fun tripCardNoDestination() = snapTrip("trip-card_no-destination", tripRoute(destination = null))

    @Test
    fun tripCardLongDestination() =
        snapTrip(
            "trip-card_long-destination",
            tripRoute(destination = "Hotel Restaurant Zur Alten Post am Marktplatz, Obere Hauptstrasse"),
        )

    // From 63% now to 5% on arrival: the bar turns red where the level falls under the reserve.
    private fun tripRoute(
        destination: String? = "Home",
        trafficDelayMinutes: Float = 0f,
        arrivalPercent: Float = 44f,
    ) = RouteSample(
        NOW,
        BLE_NAME,
        destination,
        4.7f,
        12.2f,
        trafficDelayMinutes,
        arrivalPercent,
        tripStartMiles = 11.4f,
        tripStartedAtMillis = NOW - TRIP_ELAPSED_MILLIS,
    )

    private fun snapTrip(
        name: String,
        route: RouteSample = tripRoute(),
        pricePerKwh: Float? = 0.30f,
        scale: FullRangeScale? = demoTripScale,
    ) {
        paparazzi.snapCard(name) {
            TripCard(
                route,
                tripEstimate(route, currentRatedMiles = 125f, fullRange = scale, ratedKwhPerMile = 0.25f, pricePerKwh = pricePerKwh),
            )
        }
    }

    @Test
    fun pastTripsCardTwoTrips() = snapPastTrips("past-trips-card_two-trips")

    @Test
    fun pastTripsCardNoPrice() = snapPastTrips("past-trips-card_no-price", pricePerKwh = null)

    @Test
    fun pastTripsCardInProgress() = snapPastTrips("past-trips-card_in-progress", inProgress = true)

    private fun snapPastTrips(
        name: String,
        pricePerKwh: Float? = 0.30f,
        inProgress: Boolean = false,
    ) {
        val trips = demoPastTrips(NOW).let { all -> if (inProgress) all.dropLast(1) + all.last().copy(inProgress = true) else all }
        val samples =
            listOf(
                BatterySample(NOW - DEMO_TRIP_AGO_MILLIS - 5 * 60_000L, 60, null, null, ratedRangeMiles = 162f),
                BatterySample(NOW - DEMO_TRIP_AGO_MILLIS + 20 * 60_000L, 56, null, null, ratedRangeMiles = 152f),
            )
        paparazzi.snapCard(name) {
            PastTripsCard(trips, samples, demoTripScale, ratedKwhPerMile = 0.25f, pricePerKwh = pricePerKwh)
        }
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val BLE_NAME = "S1f094173C"
        const val TRIP_ELAPSED_MILLIS = 14 * 60_000L
    }
}
