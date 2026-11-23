// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeepSleepTest {
    private fun sample(
        minutes: Long,
        percent: Int = 70,
        state: ChargingStateKind? = ChargingStateKind.Disconnected,
    ) = BatterySample(
        timestampMillis = minutes * MINUTE,
        batteryLevel = percent,
        chargingState = state,
        chargeLimit = null,
    )

    private fun status(
        minutes: Long,
        asleep: Boolean = true,
    ) = StatusSample(
        timestampMillis = minutes * MINUTE,
        vehicleId = "",
        asleep = asleep,
        userPresent = false,
        locked = true,
    )

    /** A status [seconds] after the log's start, for runs too short to round to a whole number of minutes. */
    private fun statusAtSeconds(
        seconds: Long,
        asleep: Boolean = true,
    ) = StatusSample(
        timestampMillis = seconds * 1_000L,
        vehicleId = "",
        asleep = asleep,
        userPresent = false,
        locked = true,
    )

    /** Asleep readings every ten minutes from [from] to [to], then an awake one [to] + 5 when [wake] is set. */
    private fun asleepFor(
        from: Long,
        to: Long,
        wake: Boolean = true,
    ) = (from..to step 10).map { status(it) } + listOfNotNull(status(to + 5, asleep = false).takeIf { wake })

    private fun drives(
        samples: List<BatterySample>,
        odometer: (BatterySample) -> Int = { ODOMETER },
    ) = samples.map {
        DriveSample(
            timestampMillis = it.timestampMillis,
            vehicleId = "",
            shiftState = null,
            speed = null,
            power = null,
            odometerInHundredthsOfAMile = odometer(it),
        )
    }

    @Test
    fun `the longest asleep run wins and ends at the wake reading`() {
        val statuses = asleepFor(0, 60) + asleepFor(100, 700)
        val sleep = assertNotNull(longestDeepSleep(emptyList(), emptyList(), statuses))
        assertEquals(100 * MINUTE, sleep.startMillis)
        assertEquals(705 * MINUTE, sleep.endMillis)
        assertEquals(605 * MINUTE, sleep.durationMillis)
    }

    @Test
    fun `an awake reading inside ends the run`() {
        val statuses = asleepFor(0, 100) + status(105, asleep = false) + asleepFor(110, 130)
        assertEquals(105 * MINUTE, assertNotNull(longestDeepSleep(emptyList(), emptyList(), statuses)).endMillis)
    }

    @Test
    fun `a hole between asleep readings splits the run`() {
        val statuses = asleepFor(0, 100, wake = false) + asleepFor(400, 560)
        val sleep = assertNotNull(longestDeepSleep(emptyList(), emptyList(), statuses))
        assertEquals(400 * MINUTE, sleep.startMillis)
        assertEquals(565 * MINUTE, sleep.endMillis)
    }

    @Test
    fun `an awake reading after a hole ends the run at the last asleep reading`() {
        val statuses = asleepFor(0, 100, wake = false) + status(400, asleep = false)
        assertEquals(100 * MINUTE, assertNotNull(longestDeepSleep(emptyList(), emptyList(), statuses)).endMillis)
    }

    @Test
    fun `a run shorter than the sleep latency does not count`() {
        assertNull(longestDeepSleep(emptyList(), emptyList(), listOf(status(0), statusAtSeconds(60, asleep = false))))
    }

    @Test
    fun `a run as long as the sleep latency counts`() {
        val statuses = listOf(status(0), statusAtSeconds(90, asleep = false))
        assertEquals(90_000L, assertNotNull(longestDeepSleep(emptyList(), emptyList(), statuses)).durationMillis)
    }

    @Test
    fun `no status log means no deep sleep`() {
        assertNull(longestDeepSleep(listOf(sample(0), sample(600)), emptyList(), emptyList()))
    }

    @Test
    fun `the drain is the points lost between the reads either side of the run`() {
        val samples = listOf(sample(-5, 70), sample(710, 68))
        val sleep = assertNotNull(longestDeepSleep(samples, drives(samples), asleepFor(0, 700)))
        assertEquals(2.0, sleep.drop?.percent)
        assertEquals(2.0 / (715.0 / 60), assertNotNull(sleep.percentPerHour), 1e-9)
    }

    @Test
    fun `far reads give a ceiling and no drain when the car moved or charged`() {
        val statuses = asleepFor(0, 700)
        val far = listOf(sample(-200, 70), sample(710, 68))
        val ceiling = assertNotNull(assertNotNull(longestDeepSleep(far, drives(far), statuses)).drop)
        assertEquals(true, ceiling.ceiling)
        assertEquals(2.0, ceiling.percent)
        val samples = listOf(sample(-5, 70), sample(710, 68))
        val moved = drives(samples) { if (it.timestampMillis < 0) ODOMETER else ODOMETER + 100 }
        assertNull(assertNotNull(longestDeepSleep(samples, moved, statuses)).drop)
        val charging = listOf(sample(-5, 70), sample(710, 68, ChargingStateKind.Charging))
        assertNull(assertNotNull(longestDeepSleep(charging, drives(charging), statuses)).drop)
    }

    @Test
    fun `a sleep with no read after it has no drain`() {
        val samples = listOf(sample(-5, 70))
        assertNull(assertNotNull(longestDeepSleep(samples, drives(samples), asleepFor(0, 700))).drop)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val ODOMETER = 1_234_500
    }
}
