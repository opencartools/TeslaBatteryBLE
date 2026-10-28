// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.RouteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripNoticeTest {
    private val inputs =
        TripInputs(
            currentRatedMiles = 130f,
            scale = FullRangeScale(milesPerPercent = 2.7f, offsetMiles = 0f, standardErrorMiles = 0.1f, samples = 40, pinned = true),
        )

    private fun route(
        miles: Float? = 4.7f,
        minutes: Float? = 12.2f,
        delay: Float? = 0f,
        arrival: Float? = 44f,
    ) = RouteSample(0L, "car", "Home", miles, minutes, delay, arrival)

    @Test
    fun `rounds the route to the steps the text shows`() {
        val notice = tripNotice(route(), inputs)!!
        assertEquals(47, notice.milesLeftTenths)
        assertEquals(12, notice.minutesLeft)
        assertEquals("Home", notice.destination)
        assertNull(notice.trafficMinutes)
        assertEquals(44, notice.arrivalPercent)
        // 44% of 270 mi is 118.8 rated miles.
        assertEquals(119, notice.arrivalRatedMiles)
    }

    @Test
    fun `a reading that moves within the shown step is the same notice`() {
        assertEquals(tripNotice(route(minutes = 12.2f), inputs), tripNotice(route(minutes = 12.4f), inputs))
        assertNotEquals(tripNotice(route(minutes = 12.2f), inputs), tripNotice(route(minutes = 11.4f), inputs))
    }

    @Test
    fun `shows the traffic delay only when there is one`() {
        assertEquals(3, tripNotice(route(delay = 3.2f), inputs)!!.trafficMinutes)
        assertNull(tripNotice(route(delay = 0.4f), inputs)!!.trafficMinutes)
    }

    @Test
    fun `hides the rated range without a learned scale and says nothing without a route reading`() {
        assertNull(tripNotice(route(), inputs.copy(scale = null))!!.arrivalRatedMiles)
        assertNull(tripNotice(route(miles = null, minutes = null, arrival = null), inputs))
    }

    @Test
    fun `carries the progress of the trip`() {
        val half = route(arrival = 10f).copy(tripStartMiles = 9.4f)
        assertEquals(50, tripNotice(half, inputs)!!.progressPercent)
        assertNull(tripNotice(route(), inputs)!!.progressPercent)
    }

    @Test
    fun `the driven segment ends under the tracker icon's center, not at the raw progress`() {
        // A 344dp bar and a 20dp icon: the icon's left edge slides over 324dp, so its center is 10dp in at the start.
        assertEquals(29, drivenUnits(percent = 0, barWidthDp = 344))
        assertEquals(500, drivenUnits(percent = 50, barWidthDp = 344))
        assertEquals(971, drivenUnits(percent = 100, barWidthDp = 344))
        // At 3%, the icon's center is at 0.03 * 324 + 10 = 19.7dp of 344dp.
        assertEquals(57, drivenUnits(percent = 3, barWidthDp = 344))
    }
}
