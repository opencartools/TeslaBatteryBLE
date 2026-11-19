// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.test.Test
import kotlin.test.assertEquals

class AsleepSpansTest {
    private fun status(
        minutes: Long,
        asleep: Boolean = true,
    ) = StatusSample(minutes * MINUTE, "car", asleep = asleep, userPresent = false, locked = true)

    @Test
    fun `a run of asleep readings is a span`() {
        val statuses = listOf(status(0), status(15), status(30), status(45, asleep = false))
        assertEquals(listOf(TimeSpan(0, 45 * MINUTE)), asleepSpans(statuses))
    }

    @Test
    fun `a waking that is not heard ends the run at its last asleep reading`() {
        // A hole longer than a heartbeat: the car may have woken unseen, so the span stops at the last reading.
        val statuses = listOf(status(0), status(15), status(60), status(75, asleep = false))
        assertEquals(listOf(TimeSpan(0, 15 * MINUTE), TimeSpan(60 * MINUTE, 75 * MINUTE)), asleepSpans(statuses))
    }

    @Test
    fun `a run under ten minutes is not a sleep`() {
        assertEquals(emptyList(), asleepSpans(listOf(status(0), status(5, asleep = false))))
    }

    @Test
    fun `awake readings alone are no span`() {
        assertEquals(emptyList(), asleepSpans(listOf(status(0, asleep = false), status(15, asleep = false))))
    }

    @Test
    fun `the runs of asleep readings are the deep sleeps`() {
        // The stats card reads the same runs through deepSleeps; the band API must not diverge from it.
        val statuses = listOf(status(0), status(15), status(60, asleep = false), status(120), status(135))
        val sleeps = deepSleeps(emptyList(), emptyList(), statuses)
        assertEquals(asleepSpans(statuses), sleeps.map { TimeSpan(it.startMillis, it.endMillis) })
    }

    @Test
    fun `a hole the car slept through is bridged and marked unseen`() {
        // A 45-minute hole, then asleep again: one band, with the unwatched stretch reported.
        val statuses = listOf(status(0), status(15), status(60), status(75), status(90, asleep = false))
        assertEquals(
            listOf(SleepSpan(0, 90 * MINUTE, listOf(TimeSpan(15 * MINUTE, 60 * MINUTE)))),
            bridgedSleepSpans(statuses),
        )
    }

    @Test
    fun `consecutive holes are one unseen stretch`() {
        // Two holes in a row, still asleep between them: the band carries a single striped stretch.
        val statuses = listOf(status(0), status(15), status(60), status(120), status(135, asleep = false))
        assertEquals(
            listOf(SleepSpan(0, 135 * MINUTE, listOf(TimeSpan(15 * MINUTE, 120 * MINUTE)))),
            bridgedSleepSpans(statuses),
        )
    }

    @Test
    fun `a hole followed by a wake is not bridged`() {
        // The car may have woken unseen: the band stops at the last asleep reading.
        val statuses = listOf(status(0), status(15), status(60, asleep = false))
        assertEquals(listOf(SleepSpan(0, 15 * MINUTE)), bridgedSleepSpans(statuses))
    }

    @Test
    fun `there is no cap on a bridged hole`() {
        // Three days without a reading, then asleep again: the same sleep, mostly unseen.
        val statuses = listOf(status(0), status(15), status(15 + 3 * 24 * 60), status(15 + 3 * 24 * 60 + 15, asleep = false))
        assertEquals(
            listOf(SleepSpan(0, (30 + 3 * 24 * 60) * MINUTE, listOf(TimeSpan(15 * MINUTE, (15 + 3 * 24 * 60) * MINUTE)))),
            bridgedSleepSpans(statuses),
        )
    }

    @Test
    fun `an awake reading in the middle ends the sleep`() {
        // A reading in the hole shows the car awake, so the two stretches stay apart.
        val statuses = listOf(status(0), status(15), status(30, asleep = false), status(60), status(75, asleep = false))
        assertEquals(
            listOf(SleepSpan(0, 30 * MINUTE), SleepSpan(60 * MINUTE, 75 * MINUTE)),
            bridgedSleepSpans(statuses),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
