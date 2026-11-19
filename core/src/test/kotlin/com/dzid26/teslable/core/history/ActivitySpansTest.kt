// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ShiftStateKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivitySpansTest {
    private fun reading(
        minutes: Long,
        on: Boolean,
        kind: ActivityKind = ActivityKind.CLIMATE,
    ) = ActivitySample(minutes * MINUTE, "car", kind, on)

    @Test
    fun `an on run is a span from its first reading to the off reading`() {
        val readings = listOf(reading(0, on = false), reading(10, on = true), reading(20, on = true), reading(30, on = false))
        assertEquals(listOf(TimeSpan(10 * MINUTE, 30 * MINUTE)), activitySpans(readings, ActivityKind.CLIMATE))
    }

    @Test
    fun `a hole in the log ends the run at its last on reading`() {
        // The next reading is more than a heartbeat later: the climate may have stopped unseen.
        val readings = listOf(reading(0, on = true), reading(10, on = true), reading(40, on = false))
        assertEquals(listOf(TimeSpan(0, 10 * MINUTE)), activitySpans(readings, ActivityKind.CLIMATE))
    }

    @Test
    fun `an on reading after a hole starts a new span`() {
        val readings = listOf(reading(0, on = true), reading(30, on = true), reading(40, on = false))
        // The lone reading at 0 ends with nothing to show, so only the run from 30 remains.
        assertEquals(listOf(TimeSpan(30 * MINUTE, 40 * MINUTE)), activitySpans(readings, ActivityKind.CLIMATE))
    }

    @Test
    fun `the other kind is ignored`() {
        val readings = listOf(reading(0, on = true, kind = ActivityKind.SENTRY), reading(10, on = false, kind = ActivityKind.SENTRY))
        assertEquals(emptyList(), activitySpans(readings, ActivityKind.CLIMATE))
        assertEquals(listOf(TimeSpan(0, 10 * MINUTE)), activitySpans(readings, ActivityKind.SENTRY))
    }

    @Test
    fun `a lone on reading is no span`() {
        assertEquals(emptyList(), activitySpans(listOf(reading(0, on = true)), ActivityKind.CLIMATE))
    }

    @Test
    fun `a log that ends on leaves the span at its last on reading`() {
        assertEquals(
            listOf(TimeSpan(0, 10 * MINUTE)),
            activitySpans(listOf(reading(0, on = true), reading(10, on = true)), ActivityKind.CLIMATE),
        )
    }

    @Test
    fun `a run the car drove through is left out`() {
        // The climate ran from 0 to 30, but a drive reading shows the car moving at 10: it is the trip's, not parked.
        val readings = listOf(reading(0, on = true), reading(10, on = true), reading(20, on = true), reading(30, on = false))
        val drives =
            listOf(
                drive(5, speed = 0, shift = ShiftStateKind.P),
                drive(10, speed = 40, shift = ShiftStateKind.D),
                drive(25, speed = 0, shift = ShiftStateKind.P),
            )
        assertEquals(emptyList(), parkedActivitySpans(readings, ActivityKind.CLIMATE, drives))
    }

    @Test
    fun `a run while parked is kept`() {
        // Climate on with the car in P at rest: the band is the parked car's own drain.
        val readings = listOf(reading(0, on = true), reading(10, on = true), reading(20, on = false))
        val drives = listOf(drive(5, speed = 0), drive(15, speed = 0))
        assertEquals(listOf(TimeSpan(0, 20 * MINUTE)), parkedActivitySpans(readings, ActivityKind.CLIMATE, drives))
    }

    @Test
    fun `a run with an in gear reading is left out`() {
        // In D even at a standstill the car is mid-drive, not parked.
        val readings = listOf(reading(0, on = true), reading(10, on = false))
        val drives = listOf(drive(5, speed = 0, shift = ShiftStateKind.D))
        assertEquals(emptyList(), parkedActivitySpans(readings, ActivityKind.CLIMATE, drives))
    }

    private fun drive(
        minutes: Long,
        speed: Int,
        shift: ShiftStateKind = ShiftStateKind.P,
    ) = DriveSample(
        timestampMillis = minutes * MINUTE,
        vehicleId = "car",
        shiftState = shift,
        speed = speed,
        power = null,
        odometerInHundredthsOfAMile = null,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
