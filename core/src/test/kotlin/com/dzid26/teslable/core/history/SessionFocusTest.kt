// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionFocusTest {
    private fun drive(
        startMinute: Long,
        endMinute: Long,
    ) = DriveSession(startMinute * MINUTE, endMinute * MINUTE, "car", startOdometer = 0, endOdometer = 100)

    private fun route(
        minute: Long,
        destination: String,
    ) = RouteSample(
        timestampMillis = minute * MINUTE,
        vehicleId = "car",
        destination = destination,
        milesToArrival = 5f,
        minutesToArrival = 10f,
        trafficDelayMinutes = 0f,
        energyAtArrivalPercent = 40f,
    )

    private fun trip(
        startMinute: Long,
        endMinute: Long,
        destination: String,
    ) = Trip(route(startMinute, destination), route(endMinute, destination), lengthMiles = 5f)

    @Test
    fun `a short session gets the least margin each side`() {
        assertEquals((60 - 15) * MINUTE..(70 + 15) * MINUTE, focusWindow(drive(60, 70)))
    }

    @Test
    fun `a long session gets a share of its length as margin`() {
        // 600 minutes long: 15% is 90 minutes each side.
        assertEquals((1000 - 90) * MINUTE..(1600 + 90) * MINUTE, focusWindow(drive(1000, 1600)))
    }

    @Test
    fun `a time inside a session finds it`() {
        val sessions = listOf(drive(0, 10), drive(100, 130))
        assertEquals(drive(100, 130), sessions.sessionAt(110 * MINUTE, toleranceMillis = 0))
    }

    @Test
    fun `a time just off a narrow session finds the nearest within the tolerance`() {
        val sessions = listOf(drive(0, 10), drive(30, 32))
        assertEquals(drive(30, 32), sessions.sessionAt(35 * MINUTE, toleranceMillis = 5 * MINUTE))
        assertEquals(drive(0, 10), sessions.sessionAt(12 * MINUTE, toleranceMillis = 5 * MINUTE))
    }

    @Test
    fun `a time far from every session finds none`() {
        assertNull(listOf(drive(0, 10)).sessionAt(60 * MINUTE, toleranceMillis = 5 * MINUTE))
    }

    @Test
    fun `a drive takes the navigation that overlaps it longest`() {
        val trips = listOf(trip(0, 12, "A"), trip(14, 40, "B"), trip(100, 120, "C"))
        assertEquals("B", drive(10, 40).navigation(trips)?.destination)
    }

    @Test
    fun `a route set a moment before setting off still matches`() {
        assertEquals("A", drive(10, 30).navigation(listOf(trip(7, 9, "A")))?.destination)
    }

    @Test
    fun `an unnavigated drive has no navigation`() {
        assertNull(drive(10, 30).navigation(listOf(trip(100, 120, "C"))))
    }

    @Test
    fun `a drive's distance focus stops short of the next drive`() {
        val focus = drive(60, 70).distanceFocus()
        assertEquals(true, 60 * MINUTE - DRIVE_JOIN_MILLIS + 1 in focus)
        assertEquals(false, 70 * MINUTE + DRIVE_JOIN_MILLIS in focus)
    }

    @Test
    fun `inSpan keeps the items whose time falls in the span, both ends included`() {
        val times = listOf(1L, 3L, 5L, 7L, 9L)
        assertEquals(listOf(3L, 5L, 7L), times.inSpan(3L..7L) { it })
        assertEquals(emptyList(), times.inSpan(10L..20L) { it })
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
