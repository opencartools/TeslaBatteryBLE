// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveSessionsTest {
    private fun drive(
        minutes: Long,
        shift: ShiftStateKind,
        odometer: Int?,
    ) = DriveSample(minutes * 60_000L, "car", shift, speed = null, power = null, odometerInHundredthsOfAMile = odometer)

    private fun parked(
        minutes: Long,
        odometer: Int?,
    ) = drive(minutes, ShiftStateKind.P, odometer)

    private fun moving(
        minutes: Long,
        odometer: Int?,
    ) = drive(minutes, ShiftStateKind.D, odometer)

    private fun battery(
        minutes: Long,
        level: Int,
        range: Float,
    ) = BatterySample(minutes * 60_000L, level, ChargingStateKind.Disconnected, null, "car", ratedRangeMiles = range)

    private fun List<DriveSession>.minutes() = map { it.startMillis / 60_000 to it.endMillis / 60_000 }

    @Test
    fun `a drive runs from the first to the last reading in gear, its odometer from parked to parked`() {
        val drives = listOf(parked(0, 1000), moving(1, 1000), moving(5, 1300), moving(9, 1600), parked(10, 1650), parked(30, 1650))
        val session = driveSessions(drives).single()
        assertEquals(1L to 9L, session.startMillis / 60_000 to session.endMillis / 60_000)
        assertEquals(1000 to 1650, session.startOdometer to session.endOdometer)
        assertEquals(6.5, session.distanceMiles, 1e-9)
        assertEquals("drive-car-60000", session.id)
    }

    @Test
    fun `a short stop stays inside the drive and a long one splits it`() {
        val shortStop = listOf(moving(0, 0), parked(2, 100), parked(8, 100), moving(9, 100), moving(12, 300), parked(13, 300))
        assertEquals(listOf(0L to 12L), driveSessions(shortStop).minutes())
        val longStop = listOf(moving(0, 0), parked(2, 100), parked(20, 100), moving(21, 100), moving(24, 300), parked(25, 300))
        assertEquals(listOf(0L to 0L, 21L to 24L), driveSessions(longStop).minutes())
    }

    @Test
    fun `a hole in the reads while in gear does not split the drive`() {
        val drives = listOf(moving(0, 0), moving(20, 1500), moving(21, 1600), parked(22, 1600))
        assertEquals(listOf(0L to 21L), driveSessions(drives).minutes())
    }

    @Test
    fun `an odometer change between parked readings is an unseen drive`() {
        val drives = listOf(parked(0, 1000), parked(60, 1000), parked(240, 2500), parked(300, 2500))
        val session = driveSessions(drives).single()
        assertTrue(session.unseen)
        assertEquals(60L to 240L, session.startMillis / 60_000 to session.endMillis / 60_000)
        assertEquals(15.0, session.distanceMiles, 1e-9)
    }

    @Test
    fun `each end takes the nearest battery reading`() {
        val drives = listOf(parked(0, 0), moving(1, 0), moving(30, 2000), parked(31, 2000))
        val battery = listOf(battery(0, 80, 250f), battery(15, 75, 236f), battery(31, 70, 222f))
        val session = driveSessions(drives, battery).single()
        assertEquals(80f to 70f, session.socStartPercent to session.socEndPercent)
        assertEquals(250f to 222f, session.startRangeMiles to session.endRangeMiles)
    }

    @Test
    fun `the newest drive is in progress while the car is in gear`() {
        val drives = listOf(parked(0, 0), moving(1, 0), moving(5, 300))
        assertEquals(listOf(true), driveSessions(drives, emptyList(), null, nowMillis = 6 * 60_000L).map { it.inProgress })
        assertEquals(listOf(false), driveSessions(drives, emptyList(), null, nowMillis = 60 * 60_000L).map { it.inProgress })
    }
}
