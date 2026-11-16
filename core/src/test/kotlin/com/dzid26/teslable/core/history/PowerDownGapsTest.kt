// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerDownGapsTest {
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
        odometer: Int = ODOMETER,
    ) = DriveSample(
        timestampMillis = minutes * MINUTE,
        vehicleId = "",
        shiftState = ShiftStateKind.P,
        speed = null,
        power = null,
        odometerInHundredthsOfAMile = odometer,
    )

    /** A drive read right after each battery sample at one odometer: the car never moved. */
    private fun stationary(samples: List<BatterySample>) = samples.map { drive(it.timestampMillis / MINUTE) }

    /** The gaps of a day range unless told otherwise. */
    private fun gaps(
        samples: List<BatterySample>,
        drives: List<DriveSample> = stationary(samples),
        rangeMillis: Long = 24 * HOUR,
        context: ProjectionContext = ProjectionContext(),
    ) = powerDownGaps(samples, drives, rangeMillis, context)

    /** An hour is the shortest gap a locked car spends asleep: it counts. */
    @Test
    fun `an hour between reads is a gap`() {
        assertEquals(listOf(PowerDownGap(0, HOUR)), gaps(listOf(sample(0, 80), sample(60, 79))))
    }

    /** A minute short of it is a car still awake, not a power-down. */
    @Test
    fun `fifty-nine minutes is no gap`() {
        assertEquals(emptyList(), gaps(listOf(sample(0, 80), sample(59, 79))))
    }

    /** Consecutive quiet pairs are one stretch, not two bands meeting at a seam. */
    @Test
    fun `consecutive quiet gaps join into one`() {
        val samples = listOf(sample(0, 80), sample(60, 79), sample(120, 78), sample(180, 77))
        assertEquals(listOf(PowerDownGap(0, 3 * HOUR)), gaps(samples))
    }

    /** A drive in between ends the stretch: the odometer changed, so the car was not powering down. */
    @Test
    fun `a drive splits the gaps`() {
        val samples = listOf(sample(0, 80), sample(60, 79), sample(180, 76), sample(240, 75))
        val drives = listOf(drive(0), drive(60), drive(180, ODOMETER + 500), drive(240, ODOMETER + 500))
        assertEquals(listOf(PowerDownGap(0, HOUR), PowerDownGap(3 * HOUR, 4 * HOUR)), gaps(samples, drives))
    }

    /** Somebody in the car, or the climate running, is not a power-down however long the gap. */
    @Test
    fun `a busy gap is no gap`() {
        val samples = listOf(sample(0, 80), sample(120, 79), sample(240, 76))
        val climate = listOf(ActivitySample(60 * MINUTE, "", ActivityKind.CLIMATE, true))
        assertEquals(listOf(PowerDownGap(2 * HOUR, 4 * HOUR)), gaps(samples, context = ProjectionContext(activity = climate)))
    }

    /** A charge across the gap means the level moved for another reason. */
    @Test
    fun `a charging pair is no gap`() {
        val samples =
            listOf(
                sample(0, 60),
                sample(60, 59),
                sample(150, 70, ChargingStateKind.Charging),
                sample(210, 69),
            )
        // Only the pair before the charge is a quiet parked gap; both pairs that touch the charge are not.
        assertEquals(listOf(PowerDownGap(0, HOUR)), gaps(samples))
    }

    /** The gaps follow the rate's own window: a pair whose first read is before it is not fitted from. */
    @Test
    fun `a gap that straddles the range's start is left out`() {
        val samples = listOf(sample(0, 80), sample(60, 79), sample(120, 78))
        assertEquals(listOf(PowerDownGap(0, 2 * HOUR)), gaps(samples))
        // A 90 min range starts at the read 30 min before the last one: only the second pair is in it.
        val inRange = gaps(samples, rangeMillis = 90 * MINUTE)
        assertEquals(listOf(PowerDownGap(HOUR, 2 * HOUR)), inRange)
        val windowStart = samples.last().timestampMillis - 90 * MINUTE
        assertTrue(inRange.all { it.startMillis >= windowStart && it.endMillis <= samples.last().timestampMillis })
    }

    /** The bands do not need a line: a quiet gap is listed even when the projection is too old to draw. */
    @Test
    fun `a quiet gap is a gap although the projection is gone`() {
        val samples = listOf(sample(0, 80), sample(120, 79))
        // A day range looks 12 h ahead; a last read 13 h old leaves no line.
        val now = samples.last().timestampMillis + 13 * HOUR
        assertNull(dischargeProjection(samples, stationary(samples), 24 * HOUR, now))
        assertEquals(listOf(PowerDownGap(0, 2 * HOUR)), gaps(samples))
    }

    @Test
    fun `no samples means no gaps`() {
        assertEquals(emptyList(), gaps(emptyList()))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val ODOMETER = 1_234_500
    }
}
