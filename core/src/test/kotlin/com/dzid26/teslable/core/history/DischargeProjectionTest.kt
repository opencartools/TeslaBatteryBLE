// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DischargeProjectionTest {
    private fun sample(
        minutes: Long,
        percent: Int,
        state: ChargingStateKind? = ChargingStateKind.Disconnected,
    ) = BatterySample(
        timestampMillis = minutes * MINUTE,
        batteryLevel = percent,
        chargingState = state,
        chargeLimit = null,
    )

    private fun drive(
        minutes: Long,
        odometer: Int?,
        shift: ShiftStateKind? = null,
    ) = DriveSample(
        timestampMillis = minutes * MINUTE,
        vehicleId = "",
        shiftState = shift,
        speed = null,
        power = null,
        odometerInHundredthsOfAMile = odometer,
    )

    /** A drive read right after each battery sample, all at one odometer: the car never moved. */
    private fun stationary(samples: List<BatterySample>) = samples.map { drive(it.timestampMillis / MINUTE, ODOMETER) }

    /** Projects a minute after the last sample, over a 6 h range unless told otherwise. */
    private fun project(
        samples: List<BatterySample>,
        drives: List<DriveSample> = stationary(samples),
        rangeMillis: Long = 6 * HOUR,
        nowMillis: Long = samples.last().timestampMillis + MINUTE,
        context: ProjectionContext = ProjectionContext(),
    ) = dischargeProjection(samples, drives, rangeMillis, nowMillis, context)

    @Test
    fun `carries the parked rate half the range forward`() {
        // 10 points over two hours -> 15 points lower over the three-hour horizon.
        val projection = assertNotNull(project(listOf(sample(0, 80), sample(60, 75), sample(120, 70))))
        assertEquals(55, projection.projectedPercent)
        assertEquals(3 * HOUR, projection.horizonMillis)
        assertEquals(120 * MINUTE + 3 * HOUR, projection.projectedAtMillis)
    }

    @Test
    fun `the horizon is a round figure of the selected range`() {
        val samples = listOf(sample(0, 80), sample(60, 75), sample(120, 70))
        assertEquals(12 * HOUR, assertNotNull(project(samples, rangeMillis = 24 * HOUR)).horizonMillis)
        // Half of a week is 3.5 days: three days. A month looks a week ahead.
        assertEquals(72 * HOUR, assertNotNull(project(samples, rangeMillis = 7 * 24 * HOUR)).horizonMillis)
        assertEquals(7 * 24 * HOUR, assertNotNull(project(samples, rangeMillis = 30 * 24 * HOUR)).horizonMillis)
        assertEquals(3 * HOUR, projectionHorizonMillis(6 * HOUR))
        assertEquals(48 * HOUR, projectionHorizonMillis(4 * 24 * HOUR))
    }

    @Test
    fun `only the selected range feeds the rate`() {
        // The 95% reading is older than the 6 h range: 2 points over the last two hours remain.
        val samples = listOf(sample(0, 95), sample(400, 80), sample(460, 79), sample(520, 78))
        assertEquals(75, assertNotNull(project(samples)).projectedPercent)
    }

    @Test
    fun `a long overnight gap with an unchanged odometer counts`() {
        // Two overnight gaps, each 5 points over ten hours; the drive reads come a minute after each wake-up.
        val samples = listOf(sample(-600, 85), sample(0, 80), sample(600, 75))
        val drives = listOf(drive(-599, ODOMETER), drive(1, ODOMETER), drive(601, ODOMETER))
        // 10 points over 20 h, 0.5 %/h, carried over the 12 h horizon of a day range from 75.
        assertEquals(69, assertNotNull(project(samples, drives, rangeMillis = 24 * HOUR)).projectedPercent)
    }

    @Test
    fun `a gap with an odometer change is a drive and does not count`() {
        // Parked 80 -> 79, an unseen drive to 70 (5 miles on the odometer), parked 70 -> 69.
        val samples = listOf(sample(0, 80), sample(60, 79), sample(300, 70), sample(360, 69))
        val drives = listOf(drive(0, ODOMETER), drive(60, ODOMETER), drive(300, ODOMETER + 500), drive(360, ODOMETER + 500))
        // Two parked hours lost 2 points: 1 %/h, 3 points over the horizon.
        assertEquals(66, assertNotNull(project(samples, drives)).projectedPercent)
    }

    @Test
    fun `a pair read in gear does not count even with a steady odometer`() {
        // Stopped at a light in D: 36 -> 35 -> 34 in 20 min is driving, not parked drain.
        val samples = listOf(sample(0, 80), sample(60, 79), sample(70, 78), sample(80, 77), sample(140, 76), sample(200, 75))
        val drives =
            listOf(
                drive(0, ODOMETER, ShiftStateKind.P),
                drive(60, ODOMETER, ShiftStateKind.P),
                drive(70, ODOMETER, ShiftStateKind.D),
                drive(80, ODOMETER, ShiftStateKind.D),
                drive(140, ODOMETER, ShiftStateKind.P),
                drive(200, ODOMETER, ShiftStateKind.P),
            )
        // Only the 0-60 and 140-200 pairs are parked: 2 points over two hours, 1 %/h, 3 over the horizon from 75.
        assertEquals(72, assertNotNull(project(samples, drives, nowMillis = 201 * MINUTE)).projectedPercent)
    }

    @Test
    fun `a charging pair does not count`() {
        val samples =
            listOf(
                sample(0, 60),
                sample(60, 59),
                sample(90, 70, ChargingStateKind.Charging),
                sample(120, 80, ChargingStateKind.Charging),
                sample(150, 80, ChargingStateKind.Complete),
                sample(210, 79),
            )
        // Two parked hours lost 2 points; the charge in between does not offset them.
        assertEquals(76, assertNotNull(project(samples)).projectedPercent)
    }

    @Test
    fun `a missing drive sample excludes the pair`() {
        // No drive read near the 120 min sample (the one at 125 is past the tolerance),
        // and the one at 180 has no odometer: only the two pairs before 120 are known parked.
        val samples = listOf(sample(-60, 81), sample(0, 80), sample(60, 79), sample(120, 70), sample(180, 69))
        val drives = listOf(drive(-60, ODOMETER), drive(0, ODOMETER), drive(60, ODOMETER), drive(125, ODOMETER), drive(180, null))
        // Two parked hours lost 2 points: 1 %/h, 3 points over the horizon from 69.
        assertEquals(66, assertNotNull(project(samples, drives)).projectedPercent)
        // With no drive samples at all nothing is known parked.
        assertNull(project(samples, emptyList()))
    }

    @Test
    fun `a parked rounding blip cancels out`() {
        // 80 -> 79 -> 80 -> 79 -> 78 over four one-hour pairs: the blip back to 80
        // cancels its drop, leaving 2 points over four hours, 0.5 %/h; summing only the drops would say 3.
        val samples = listOf(sample(0, 80), sample(60, 79), sample(120, 80), sample(180, 79), sample(240, 78))
        // Six points over the 12 h horizon of a day range.
        assertEquals(72, assertNotNull(project(samples, rangeMillis = 24 * HOUR)).projectedPercent)
    }

    @Test
    fun `a sleeping car keeps its projection until the horizon has passed`() {
        // No reads while asleep: the last sample is 8 h old, inside the 12 h horizon of a day range.
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 78))
        assertNotNull(project(samples, rangeMillis = 24 * HOUR, nowMillis = (240 + 8 * 60) * MINUTE))
        // Past the horizon the whole line would lie in the past.
        assertNull(project(samples, rangeMillis = 24 * HOUR, nowMillis = (240 + 13 * 60) * MINUTE))
    }

    @Test
    fun `still projects the parked drain while charging`() {
        val samples =
            listOf(
                sample(0, 60),
                sample(60, 59),
                sample(120, 58),
                sample(150, 70, ChargingStateKind.Charging),
                sample(180, 80, ChargingStateKind.Charging),
            )
        val projection = assertNotNull(project(samples))
        // Two parked hours lost 2 points: 1 %/h, 3 points down from the latest 80.
        assertEquals(77, projection.projectedPercent)
        assertEquals(1.0, projection.percentPerHour, 1e-9)
    }

    @Test
    fun `null when the last sample is older than the horizon`() {
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 78))
        // Two hours old, inside the 3 h horizon of a 6 h range.
        assertNotNull(project(samples, nowMillis = (240 + 2 * 60) * MINUTE))
        // Four hours old, past it.
        assertNull(project(samples, nowMillis = (240 + 4 * 60) * MINUTE))
    }

    @Test
    fun `null when the percent rose`() {
        assertNull(project(listOf(sample(0, 70), sample(120, 75))))
    }

    @Test
    fun `null with no power-down pair`() {
        // Five minutes is not a power-down pair.
        assertNull(project(listOf(sample(0, 80), sample(5, 79))))
    }

    private val scale = FullRangeScale(milesPerPercent = 3.0f, offsetMiles = 0f, standardErrorMiles = 0.3f, samples = 20, pinned = true)

    private fun ranged(
        minutes: Long,
        percent: Int,
        miles: Float,
    ) = sample(minutes, percent).copy(ratedRangeMiles = miles)

    @Test
    fun `the range sees a drain the whole level hides`() {
        // The level sits at 80 all day, but the rated range slips 0.9 mi
        // (0.3 points at 3 mi per point) every two hours.
        val samples =
            listOf(
                ranged(0, 80, 241.5f),
                ranged(120, 80, 240.6f),
                ranged(240, 80, 239.7f),
                ranged(360, 80, 238.8f),
            )
        val projection = assertNotNull(project(samples, context = ProjectionContext(scale = scale), rangeMillis = 24 * HOUR))
        // 0.9 points over 6 h = 0.15 %/h = 3.6 %/day; 12 h ahead is 1.8 points down from 79.6.
        assertEquals(0.15, projection.percentPerHour, 1e-6)
        assertEquals(77.8f, projection.projectedSoc, 0.01f)
        // Without the scale the integer levels see no drop at all.
        assertNull(project(samples, rangeMillis = 24 * HOUR))
    }

    @Test
    fun `the band top widens with noisy pairs`() {
        val steady =
            listOf(
                ranged(0, 80, 241.5f),
                ranged(120, 80, 240.6f),
                ranged(240, 80, 239.7f),
                ranged(360, 80, 238.8f),
            )
        val noisy =
            listOf(
                ranged(0, 80, 241.5f),
                ranged(120, 80, 240.0f),
                ranged(240, 80, 239.7f),
                ranged(360, 80, 238.8f),
            )
        val twoPairs = steady.take(3)
        val short = assertNotNull(project(twoPairs, context = ProjectionContext(scale = scale), rangeMillis = 24 * HOUR))
        // Two pairs have no statistical margin: the band top is the line.
        assertEquals(short.projectedSoc, short.highSoc!!, 0.01f)

        val tidy = assertNotNull(project(steady, context = ProjectionContext(scale = scale), rangeMillis = 24 * HOUR))
        val loose = assertNotNull(project(noisy, context = ProjectionContext(scale = scale), rangeMillis = 24 * HOUR))
        val tidyWidth = tidy.highSoc!! - tidy.lowSoc!!
        val looseWidth = loose.highSoc!! - loose.lowSoc!!
        assertEquals(true, tidyWidth > 0f)
        assertEquals(true, looseWidth > tidyWidth)
    }

    /** The climate running at [minutes]: a car kept busy there, not sleeping. */
    private fun climate(minutes: Long) = ActivitySample(minutes * MINUTE, "", ActivityKind.CLIMATE, true)

    @Test
    fun `the sleep rate comes from the pairs nobody was at the car`() {
        // Two unattended pairs lose a point each; the last pair has someone at the car and loses 3.
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 78), sample(360, 75))
        val busy = listOf(climate(300))
        val projection = assertNotNull(project(samples, context = ProjectionContext(activity = busy), rangeMillis = 24 * HOUR))
        // Quiet: 2 points over 4 h = 0.5 %/h is the line; mixed: 5 points over 6 h.
        assertEquals(0.5, projection.percentPerHour, 1e-9)
        assertEquals(5.0 / 6.0, projection.mixedPercentPerHour!!, 1e-9)
        // 12 h at 0.5 %/h from 75 is 69; the beam's lower edge reaches the mixed line, 65.
        assertEquals(69f, projection.projectedSoc, 0.01f)
        assertTrue(projection.lowSoc!! <= 65f)
        // A single quiet pair is not enough to call a rate.
        val one = listOf(sample(0, 80), sample(120, 79), sample(240, 76))
        val oneQuiet = listOf(climate(180))
        assertNull(project(one, context = ProjectionContext(activity = oneQuiet), rangeMillis = 24 * HOUR))
    }

    @Test
    fun `without a status log every pair is power-down`() {
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 76))
        // Without a status log every pair is quiet, so the line is the mixed rate and the band has no width.
        val projection = assertNotNull(project(samples, rangeMillis = 24 * HOUR))
        assertEquals(projection.percentPerHour, projection.mixedPercentPerHour!!, 1e-9)
        assertEquals(projection.lowSoc, projection.highSoc)
        // The climate running in both gaps rules the power-down rate out.
        val busy = listOf(climate(60), climate(180))
        assertNull(project(samples, context = ProjectionContext(activity = busy), rangeMillis = 24 * HOUR))
    }

    @Test
    fun `someone in the car leaves no power-down pair`() {
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 78), sample(360, 75))
        // Present in both of the first two gaps: only the unlocked-and-away last pair would be left, one pair.
        val statuses =
            listOf(
                StatusSample(60 * MINUTE, "", asleep = false, userPresent = true, locked = false),
                StatusSample(180 * MINUTE, "", asleep = false, userPresent = true, locked = false),
            )
        assertNull(project(samples, context = ProjectionContext(statuses = statuses), rangeMillis = 24 * HOUR))
    }

    @Test
    fun `sentry running makes a long locked gap no sleep`() {
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 78), sample(360, 75))
        val sentry =
            listOf(
                ActivitySample(-5 * MINUTE, "", ActivityKind.SENTRY, true),
                ActivitySample(60 * MINUTE, "", ActivityKind.SENTRY, true),
                ActivitySample(180 * MINUTE, "", ActivityKind.SENTRY, true),
                ActivitySample(300 * MINUTE, "", ActivityKind.SENTRY, true),
            )
        // Sentry was on throughout the gaps: no power-down pair, so no line.
        assertNull(project(samples, context = ProjectionContext(activity = sentry), rangeMillis = 24 * HOUR))
    }

    @Test
    fun `short quiet pairs are an awake car and leave no power-down pair`() {
        // Idle, but only minutes apart: the car was still awake. Only the last pair is an hour or more.
        val samples = listOf(sample(0, 80), sample(10, 79), sample(20, 78), sample(30, 77), sample(240, 76))
        assertNull(project(samples, context = ProjectionContext(), rangeMillis = 24 * HOUR))
    }

    @Test
    fun `a charge that only just began does not cut off the parked stretch before it`() {
        val begun = sample(360, 74, ChargingStateKind.Charging).copy(chargeEnergyAdded = 0.02f)
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 78), begun)
        // The begun pair counts: 6 points over 6 h, 1 %/h.
        assertEquals(1.0, assertNotNull(project(samples, rangeMillis = 24 * HOUR)).percentPerHour, 1e-9)
        // A charge well under way at the read cuts the last pair off: 2 points over 4 h, 0.5 %/h.
        val going = begun.copy(chargeEnergyAdded = 5f)
        assertEquals(
            0.5,
            assertNotNull(project(listOf(sample(0, 80), sample(120, 79), sample(240, 78), going), rangeMillis = 24 * HOUR)).percentPerHour,
            1e-9,
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val ODOMETER = 1_234_500
    }
}
