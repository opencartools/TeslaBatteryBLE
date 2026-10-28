// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveRouteTest {
    private fun route(
        carMinutes: Long,
        miles: Float? = 4.7f,
        phoneMinutes: Long? = null,
    ) = RouteSample(
        timestampMillis = carMinutes * MINUTE,
        vehicleId = "",
        destination = "Home",
        milesToArrival = miles,
        minutesToArrival = 12f,
        trafficDelayMinutes = 0f,
        energyAtArrivalPercent = 44f,
        readAtMillis = phoneMinutes?.let { it * MINUTE },
    )

    @Test
    fun `a recent route is live`() {
        val latest = route(carMinutes = 100)
        assertEquals(latest, activeRoute(latest, nowMillis = 105 * MINUTE))
    }

    @Test
    fun `no route is no trip`() {
        assertNull(activeRoute(null, nowMillis = 101 * MINUTE))
    }

    @Test
    fun `a route with no distance left has ended`() {
        assertNull(activeRoute(route(carMinutes = 100, miles = null), nowMillis = 101 * MINUTE))
    }

    @Test
    fun `a route not refreshed for ten minutes is a finished trip`() {
        val latest = route(carMinutes = 100)
        assertEquals(latest, activeRoute(latest, nowMillis = 110 * MINUTE))
        assertNull(activeRoute(latest, nowMillis = 111 * MINUTE))
    }

    @Test
    fun `a route the car stopped reporting stays for the trip end gap`() {
        val ended = route(carMinutes = 100, phoneMinutes = 100).copy(endedAtMillis = 101 * MINUTE)
        assertEquals(ended, activeRoute(ended, nowMillis = 103 * MINUTE))
        assertNull(activeRoute(ended, nowMillis = 103 * MINUTE + 1))
    }

    @Test
    fun `recency is judged on the phone clock when the car clock is off`() {
        // The car says 90 minutes, the phone read it at 100: ten minutes old at 105, not fifteen.
        val latest = route(carMinutes = 90, phoneMinutes = 100)
        assertEquals(latest, activeRoute(latest, nowMillis = 105 * MINUTE))
        assertNull(activeRoute(latest, nowMillis = 111 * MINUTE))
    }

    @Test
    fun `a trip keeps its longest remaining distance as its length`() {
        val first = route(carMinutes = 100, miles = 10f).continuing(null)
        val second = route(carMinutes = 103, miles = 6f).continuing(first)
        val rerouted = route(carMinutes = 106, miles = 8f).continuing(second)
        assertEquals(10f, first.tripStartMiles)
        assertEquals(10f, second.tripStartMiles)
        assertEquals(10f, rerouted.tripStartMiles)
        assertEquals(0.2f, rerouted.tripProgress()!!, 1e-6f)
    }

    @Test
    fun `the total time is what has passed since the first reading plus the time left`() {
        val first = route(carMinutes = 100, miles = 10f).copy(minutesToArrival = 26f).continuing(null)
        val later = route(carMinutes = 110, miles = 5f).copy(minutesToArrival = 14f).continuing(first)
        assertEquals(100 * MINUTE, later.tripStartedAtMillis)
        assertEquals(24 * MINUTE, later.tripTotalMillis())
        assertNull(route(carMinutes = 100).tripTotalMillis())
    }

    @Test
    fun `a longer route right after arriving starts a new live trip`() {
        val arrived = route(carMinutes = 100, miles = 0.4f).copy(tripStartMiles = 10f, tripStartedAtMillis = 90 * MINUTE)
        val home = route(carMinutes = 104, miles = 12f).continuing(arrived)
        assertEquals(12f, home.tripStartMiles)
        assertEquals(104 * MINUTE, home.tripStartedAtMillis)
    }

    @Test
    fun `a reading after one with no distance starts a new live trip`() {
        val ended = route(carMinutes = 100, miles = null).copy(tripStartMiles = 10f, tripStartedAtMillis = 90 * MINUTE)
        val next = route(carMinutes = 104, miles = 5f).continuing(ended)
        assertEquals(5f, next.tripStartMiles)
        assertEquals(104 * MINUTE, next.tripStartedAtMillis)
    }

    @Test
    fun `traffic delay shows in whole minutes only when there is one`() {
        assertEquals(3, route(carMinutes = 100).copy(trafficDelayMinutes = 2.6f).trafficMinutes())
        assertNull(route(carMinutes = 100).copy(trafficDelayMinutes = 0.2f).trafficMinutes())
        assertNull(route(carMinutes = 100).copy(trafficDelayMinutes = null).trafficMinutes())
    }

    @Test
    fun `the traffic share is the delay over the whole trip's time`() {
        val first = route(carMinutes = 100, miles = 10f).copy(minutesToArrival = 20f).continuing(null)
        val later = route(carMinutes = 110, miles = 5f).copy(minutesToArrival = 10f, trafficDelayMinutes = 6f).continuing(first)
        // 10 minutes driven plus 10 left is 20; 6 of them are traffic.
        assertEquals(0.3f, later.trafficShare()!!, 0.001f)
        assertNull(first.trafficShare())
    }

    @Test
    fun `a long gap starts a new trip`() {
        val first = route(carMinutes = 100, miles = 10f).continuing(null)
        val later = route(carMinutes = 130, miles = 3f).continuing(first)
        assertEquals(3f, later.tripStartMiles)
        assertEquals(0f, later.tripProgress())
    }

    @Test
    fun `progress is unknown without a length and complete at arrival`() {
        assertNull(route(carMinutes = 100).tripProgress())
        val arrived = route(carMinutes = 100, miles = 0f).copy(tripStartMiles = 5f)
        assertEquals(1f, arrived.tripProgress())
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
