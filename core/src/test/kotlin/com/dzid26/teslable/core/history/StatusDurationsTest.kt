// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatusDurationsTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun sample(
        at: Long,
        asleep: Boolean = false,
        locked: Boolean = true,
        userPresent: Boolean = false,
        vehicle: String = "car",
    ) = StatusSample(at, vehicle, asleep = asleep, userPresent = userPresent, locked = locked)

    @Test
    fun `no samples for the vehicle gives no durations`() {
        assertNull(statusDurations(emptyList(), "car", 10 * hour))
        assertNull(statusDurations(listOf(sample(0, vehicle = "other")), "car", 10 * hour))
    }

    @Test
    fun `a flag held since its change shows the time since the first record with the new value`() {
        val samples =
            listOf(
                sample(0, asleep = false),
                sample(10_000, asleep = true),
                sample(10_000 + 15 * minute, asleep = true),
            )
        val durations = assertNotNull(statusDurations(samples, "car", 10_000 + 72 * minute))
        assertEquals(true, durations.asleep)
        assertEquals(72 * minute, durations.asleepForMillis)
    }

    @Test
    fun `a flag with no earlier different value has no duration`() {
        val samples = listOf(sample(0), sample(15 * minute), sample(30 * minute))
        val durations = assertNotNull(statusDurations(samples, "car", hour))
        assertNull(durations.asleepForMillis)
        assertNull(durations.lockedForMillis)
    }

    @Test
    fun `asleep and locked have their own since-times`() {
        val samples =
            listOf(
                sample(0, locked = false, userPresent = true),
                sample(10_000, locked = true, userPresent = true),
                sample(20_000, locked = true, userPresent = false),
            )
        val now = 3 * hour
        val durations = assertNotNull(statusDurations(samples, "car", now))
        assertEquals(now - 10_000, durations.lockedForMillis)
        assertNull(durations.asleepForMillis)
    }

    @Test
    fun `a flag that flipped back uses the latest change`() {
        val samples =
            listOf(
                sample(0, locked = true),
                sample(10_000, locked = false),
                sample(20_000, locked = true),
            )
        val durations = assertNotNull(statusDurations(samples, "car", 20_000 + 2 * hour))
        assertEquals(2 * hour, durations.lockedForMillis)
    }

    @Test
    fun `a change seen only after a long gap is unknown`() {
        val samples = listOf(sample(0, asleep = false), sample(3 * hour, asleep = true))
        val durations = assertNotNull(statusDurations(samples, "car", 4 * hour))
        assertNull(durations.asleepForMillis)
    }

    @Test
    fun `a change at the gap limit still counts`() {
        val samples = listOf(sample(0, asleep = false), sample(STATUS_CHANGE_MAX_GAP_MILLIS, asleep = true))
        val durations = assertNotNull(statusDurations(samples, "car", 2 * hour))
        assertEquals(2 * hour - STATUS_CHANGE_MAX_GAP_MILLIS, durations.asleepForMillis)
    }

    @Test
    fun `a change over the gap limit after the previous record has no duration`() {
        val samples = listOf(sample(0, asleep = false), sample(STATUS_CHANGE_MAX_GAP_MILLIS + 1, asleep = true))
        assertNull(assertNotNull(statusDurations(samples, "car", 2 * hour)).asleepForMillis)
    }

    @Test
    fun `a change seen live 12 minutes after a heartbeat record gets a duration`() {
        val samples = listOf(sample(0, asleep = false), sample(12 * minute, asleep = true))
        val durations = assertNotNull(statusDurations(samples, "car", 12 * minute + 72 * minute))
        assertEquals(72 * minute, durations.asleepForMillis)
    }

    @Test
    fun `the gap limit stays sixteen minutes whatever the heartbeat is`() {
        assertEquals(16 * minute, STATUS_CHANGE_MAX_GAP_MILLIS)
    }

    @Test
    fun `durations are exact and the compact text drops the ones under a minute`() {
        val samples = listOf(sample(0, asleep = false), sample(10_000, asleep = true))
        val short = assertNotNull(statusDurations(samples, "car", 10_000 + minute - 1)).asleepForMillis
        assertEquals(minute - 1, short)
        assertNull(compactDuration(short!!))
        assertEquals("1min", compactDuration(assertNotNull(statusDurations(samples, "car", 10_000 + minute)).asleepForMillis!!))
    }

    @Test
    fun `a since-time in the future is unknown`() {
        val samples = listOf(sample(hour, asleep = false), sample(hour + 10_000, asleep = true))
        assertNull(assertNotNull(statusDurations(samples, "car", 0)).asleepForMillis)
    }

    @Test
    fun `other vehicles and unsorted samples are handled`() {
        val samples =
            listOf(
                sample(20_000, asleep = true),
                sample(5_000, asleep = false, vehicle = "other"),
                sample(0, asleep = false),
                sample(30_000, asleep = false, vehicle = "other"),
            )
        val durations = assertNotNull(statusDurations(samples, "car", 20_000 + hour))
        assertEquals(hour, durations.asleepForMillis)
    }

    @Test
    fun `compact durations`() {
        assertNull(compactDuration(59_999))
        assertEquals("1min", compactDuration(minute))
        assertEquals("59min", compactDuration(hour - 1))
        assertEquals("1h", compactDuration(hour))
        assertEquals("1h12m", compactDuration(hour + 12 * minute))
        assertEquals("3h", compactDuration(3 * hour))
        assertEquals("23h59m", compactDuration(24 * hour - 1))
        assertEquals("1d", compactDuration(24 * hour))
        assertEquals("2d4h", compactDuration(52 * hour + 30 * minute))
    }
}
