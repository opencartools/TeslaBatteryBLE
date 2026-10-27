// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SampleThinnerTest {
    private fun battery(
        seconds: Long,
        level: Int = 50,
        state: ChargingStateKind = ChargingStateKind.Disconnected,
        vehicleId: String = "carA",
        range: Float? = null,
    ) = BatterySample(seconds * 1000, level, state, chargeLimit = 80, vehicleId = vehicleId, ratedRangeMiles = range)

    private fun drive(
        seconds: Long,
        shift: ShiftStateKind? = ShiftStateKind.P,
        odometer: Int = 100,
    ) = DriveSample(seconds * 1000, "carA", shift, speed = null, power = null, odometerInHundredthsOfAMile = odometer)

    private fun List<BatterySample>.seconds() = map { it.timestampMillis / 1000 }

    @Test
    fun `parked readings keep one each five minutes`() {
        val kept = (0L..1200L step 10).map { battery(it) }.thinnedBy(batteryThinner())
        assertEquals(listOf(0L, 300, 600, 900, 1200), kept.seconds())
    }

    @Test
    fun `a level step keeps the readings on both sides of it`() {
        val readings = (0L..200L step 10).map { battery(it, level = if (it < 120) 50 else 49) }
        val kept = readings.thinnedBy(batteryThinner())
        assertEquals(listOf(0L, 110, 120), kept.seconds())
    }

    @Test
    fun `charging keeps one reading a minute and both edges of the session`() {
        val readings =
            (0L..300L step 10).map {
                battery(it, state = if (it in 100L..200L) ChargingStateKind.Charging else ChargingStateKind.Disconnected)
            }
        val kept = readings.thinnedBy(batteryThinner())
        assertEquals(listOf(0L, 90, 100, 160, 200, 210), kept.seconds())
    }

    @Test
    fun `each car is thinned on its own`() {
        val readings = listOf(battery(0), battery(10, vehicleId = "carB"), battery(20), battery(30, vehicleId = "carB"))
        assertEquals(listOf(0L, 10), readings.thinnedBy(batteryThinner()).seconds())
    }

    @Test
    fun `a late reply is kept and does not move the thinner`() {
        val thinner = batteryThinner()
        val kept = listOf(battery(0), battery(100), battery(50), battery(200), battery(300)).thinnedBy(thinner)
        assertEquals(listOf(0L, 50, 300), kept.seconds())
    }

    @Test
    fun `a resumed thinner continues from the earlier run`() {
        val thinner = batteryThinner()
        thinner.resume(kept = battery(0), latest = battery(250))
        val kept = listOf(battery(260), battery(300), battery(310, level = 49)).thinnedBy(thinner)
        assertEquals(listOf(300L, 310), kept.seconds())
    }

    @Test
    fun `drives keep gear changes and a reading each half minute in gear`() {
        val readings =
            listOf(drive(0), drive(10), drive(20, ShiftStateKind.D), drive(35, ShiftStateKind.D), drive(50, ShiftStateKind.D)) +
                listOf(drive(60, ShiftStateKind.P), drive(70))
        val kept = readings.thinnedBy(driveThinner())
        assertEquals(listOf(0L, 10, 20, 50, 60), kept.map { it.timestampMillis / 1000 })
    }

    @Test
    fun `an odometer change out of gear keeps both sides`() {
        val readings = listOf(drive(0), drive(100), drive(200, odometer = 500), drive(300, odometer = 500))
        val kept = readings.thinnedBy(driveThinner())
        assertEquals(listOf(0L, 100, 200), kept.map { it.timestampMillis / 1000 })
    }

    @Test
    fun `a hole in the reads keeps the reading before it`() {
        val kept = listOf(battery(0), battery(10), battery(20), battery(20 + 3600), battery(3630)).thinnedBy(batteryThinner())
        assertEquals(listOf(0L, 20, 3620), kept.seconds())
    }

    @Test
    fun `a moving rated range keeps one reading each half minute`() {
        val readings = (0L..120L step 10).map { battery(it, range = 150f - it / 10f) }
        assertEquals(listOf(0L, 30, 60, 90, 120), readings.thinnedBy(batteryThinner()).seconds())
    }

    @Test
    fun `parked drive readings keep one each two minutes`() {
        val kept = (0L..300L step 10).map { drive(it) }.thinnedBy(driveThinner())
        assertEquals(listOf(0L, 120, 240), kept.map { it.timestampMillis / 1000 })
    }
}
