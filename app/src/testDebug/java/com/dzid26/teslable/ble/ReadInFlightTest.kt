// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadInFlightTest {
    private val changes = mutableListOf<ReadProgress?>()
    private val read = ReadInFlight { changes += it }

    /** One reply as the controller handles it: the reply, the follow-up if any, then the settle. */
    private fun reply(followUpAt: Long? = null) {
        read.onReply()
        followUpAt?.let(read::onSent)
        read.settle()
    }

    @Test
    fun eachReplyWithAFollowUpFillsOneStepAndTheLastReplyEndsTheRead() {
        assertFalse(read.active)
        read.begin(START, replies = REPLIES, afterHandshake = false)
        assertEquals(ReadProgress(0, REPLIES), changes.last())
        read.onSent(START) // charge
        reply(followUpAt = START + STEP) // drive
        assertEquals(ReadProgress(1, REPLIES), changes.last())
        reply(followUpAt = START + 2 * STEP) // closures
        reply(followUpAt = START + 3 * STEP) // climate
        assertTrue(read.active)
        reply() // climate reply: nothing follows
        assertFalse(read.active)
        assertNull(changes.last())
    }

    @Test
    fun aReadThatWaitsForItsSessionCountsTheHandshakeAsAStep() {
        read.begin(START, replies = REPLIES, afterHandshake = true)
        assertEquals(ReadProgress(0, REPLIES + 1), changes.last())
        assertTrue(read.waitingForSession)
        // The gate asks again while the handshake runs: the read keeps its place.
        read.begin(START + STEP, replies = REPLIES, afterHandshake = true)
        read.sessionReady(START + 2 * STEP)
        assertFalse(read.waitingForSession)
        read.onSent(START + 2 * STEP) // charge
        assertEquals(ReadProgress(1, REPLIES + 1), changes.last())
    }

    @Test
    fun aRefreshMidReadKeepsTheProgress() {
        read.begin(START, replies = REPLIES, afterHandshake = false)
        read.onSent(START)
        reply(followUpAt = START + STEP)
        // A pull-to-refresh or a poll asks again while the read runs: it adds a request, not a restart.
        read.begin(START + 2 * STEP, replies = REPLIES, afterHandshake = false)
        read.onSent(START + 2 * STEP)
        assertEquals(ReadProgress(1, REPLIES), changes.last())
    }

    @Test
    fun aReadThatGrowsOnTheWayGrowsItsTotalInsteadOfOverfilling() {
        read.begin(START, replies = 1, afterHandshake = false)
        read.onSent(START)
        reply(followUpAt = START + STEP) // a follow-up nobody expected
        assertEquals(ReadProgress(1, 2), changes.last())
    }

    @Test
    fun aReplyWithoutAFollowUpASkippedReadOrADroppedLinkEndsIt() {
        // A failure, a refusal, a reply without data or a follow-up that could not be sent.
        read.begin(START, replies = REPLIES, afterHandshake = false)
        read.onSent(START)
        reply()
        assertFalse(read.active)
        assertNull(changes.last())
        read.begin(START, replies = REPLIES, afterHandshake = false)
        read.onSkipped()
        assertFalse(read.active)
        read.begin(START, replies = REPLIES, afterHandshake = true)
        read.onLinkDropped()
        assertFalse(read.active)
        assertFalse(read.waitingForSession)
        assertNull(changes.last())
    }

    @Test
    fun anUnansweredReadEndsAfterTheCommandTimeoutAndNotBefore() {
        read.begin(START, replies = REPLIES, afterHandshake = false)
        read.onSent(START)
        assertFalse(read.expire(START + ReadInFlight.TIMEOUT_MS - 1))
        assertTrue(read.expire(START + ReadInFlight.TIMEOUT_MS))
        assertFalse(read.active)
        assertFalse(read.expire(START + 2 * ReadInFlight.TIMEOUT_MS))
    }

    @Test
    fun eachRequestRestartsTheTimeout() {
        read.begin(START, replies = REPLIES, afterHandshake = false)
        read.onSent(START)
        reply(followUpAt = START + ReadInFlight.TIMEOUT_MS)
        assertFalse(read.expire(START + 2 * ReadInFlight.TIMEOUT_MS - 1))
    }

    @Test
    fun theChangeCallbackFiresOncePerChange() {
        read.begin(START, replies = 2, afterHandshake = false)
        read.onSent(START)
        reply(followUpAt = START + STEP)
        read.onSent(START + 2 * STEP) // the same request again changes nothing
        reply()
        read.settle()
        assertEquals(listOf(ReadProgress(0, 2), ReadProgress(1, 2), null), changes)
    }

    @Test
    fun progressReadsAsAShareOfTheRead() {
        assertEquals(HALF, ReadProgress(1, 2).fraction, 0f)
        assertEquals(1f, ReadProgress(REPLIES + 1, REPLIES).fraction, 0f)
        assertEquals(0f, ReadProgress(0, 0).fraction, 0f)
        assertEquals(CommandRecorder.TIMEOUT_MS, ReadInFlight.TIMEOUT_MS)
    }

    @Test
    fun aRingStaysUpForItsMinimumThenReleasesAtOnce() {
        val shownAt = START
        assertEquals(READ_SPINNER_MIN_VISIBLE_MS, spinnerHoldMs(shownAtMillis = shownAt, nowMillis = shownAt))
        assertEquals(STEP, spinnerHoldMs(shownAtMillis = shownAt, nowMillis = shownAt + READ_SPINNER_MIN_VISIBLE_MS - STEP))
        assertEquals(0L, spinnerHoldMs(shownAtMillis = shownAt, nowMillis = shownAt + READ_SPINNER_MIN_VISIBLE_MS))
    }

    private companion object {
        const val START = 1_000L
        const val STEP = 200L
        const val REPLIES = 4
        const val HALF = 0.5f
    }
}
