// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripLowStretchTest {
    private fun route(miles: Float? = 4.7f) =
        RouteSample(
            timestampMillis = 0L,
            vehicleId = "",
            destination = "Home",
            milesToArrival = miles,
            minutesToArrival = 12f,
            trafficDelayMinutes = 0f,
            energyAtArrivalPercent = 44f,
        )

    @Test
    fun `the low stretch starts where the level is predicted to cross the reserve`() {
        // Half way; 50% now falling to 10% on arrival: 20% is reached 75% of the way along what is left.
        val half = route(miles = 5f).copy(tripStartMiles = 10f, energyAtArrivalPercent = 10f)
        assertEquals(0.5f + 0.5f * 0.75f, half.tripLowFrom(50f)!!, 1e-6f)
        assertEquals(0.5f, half.tripLowFrom(15f)!!, 1e-6f)
    }

    @Test
    fun `there is no low stretch above the reserve or with an unknown level`() {
        val half = route(miles = 5f).copy(tripStartMiles = 10f, energyAtArrivalPercent = 30f)
        assertNull(half.tripLowFrom(50f))
        assertNull(half.copy(energyAtArrivalPercent = 10f).tripLowFrom(null))
        assertNull(half.copy(energyAtArrivalPercent = null).tripLowFrom(50f))
        assertNull(route().tripLowFrom(50f))
    }
}
