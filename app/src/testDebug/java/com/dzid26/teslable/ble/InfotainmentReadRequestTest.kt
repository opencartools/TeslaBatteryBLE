// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.infotainment.InfotainmentPollPolicy.ReadKind
import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The read request itself: why the link reads, and whether it is the start/stop confirmation. */
class InfotainmentReadRequestTest {
    private val read = InfotainmentRead()
    private val full = InfotainmentRead.Request(Command.Reason.POLICY_HOLD, ReadKind.AUTOMATIC)
    private val confirmation = InfotainmentRead.Request(Command.Reason.ACTION_READBACK, ReadKind.CONFIRMATION)

    @Test
    fun aConfirmationKeepsItsKindWhileItWaitsForTheSession() {
        read.begin(confirmation, nowMs = START, replies = 1, afterHandshake = true)
        assertTrue(read.confirming)
        assertTrue(read.waitingForSession)
        read.onSessionReady(confirmation, START + STEP)
        assertTrue(read.confirming)
        assertTrue(read.running)
        read.onSent(START + STEP)
        read.onReply()
        read.settle()
        assertFalse(read.active)
        assertFalse(read.confirming)
    }

    @Test
    fun aResumeReplacesTheReasonAndKindOfTheWaitingRead() {
        read.begin(full, nowMs = START, replies = 4, afterHandshake = true)
        assertFalse(read.confirming)
        read.onSessionReady(confirmation, START + STEP)
        assertTrue(read.confirming)
        assertEquals(ReadKind.CONFIRMATION, read.request?.kind)
        assertEquals(Command.Reason.ACTION_READBACK, read.request?.reason)
    }

    @Test
    fun aRequestWhileTheReadRunsKeepsTheProgressButTakesTheNewRequest() {
        val changes = mutableListOf<ReadProgress?>()
        val running = InfotainmentRead { changes += it }
        running.begin(full, nowMs = START, replies = 4, afterHandshake = false)
        running.onSent(START)
        val progress = changes.last()
        running.begin(confirmation, nowMs = START + STEP, replies = 1, afterHandshake = false)
        assertEquals("the running read keeps its progress", progress, changes.last())
        assertEquals(ReadKind.CONFIRMATION, running.request?.kind)
        assertTrue(running.active)
        assertTrue(running.running)
    }

    @Test
    fun aSkippedReadForgetsItsRequest() {
        read.begin(confirmation, nowMs = START, replies = 1, afterHandshake = true)
        read.onSkipped()
        assertNull(read.request)
        assertFalse(read.active)
    }

    /**
     * Regression: a read whose ring timed out while its handshake was still out must still go out
     * when the session arrives. The ring forgets the request; the session wait must keep it.
     */
    @Test
    fun aReadThatOutlivedItsRingStillGoesOutWhenTheSessionArrives() {
        val wait = InfotainmentSessionWait<InfotainmentRead.Request>()
        wait.onReadDue(awake = true, hasSession = false, request = full)
        read.begin(full, nowMs = START, replies = 4, afterHandshake = true)
        assertTrue(read.waitingForSession)

        assertTrue(read.expire(START + InfotainmentRead.TIMEOUT_MS))
        assertNull("the ring forgets the request", read.request)

        // The session arrives: the wait still holds the read and hands it back for the resume.
        assertEquals(setOf(InfotainmentSessionWait.Demand.READ), wait.onSessionEstablished())
        assertEquals(full, wait.takeReadRequest())
        assertFalse("the resume consumes the request", wait.hasWaitingRead)
        assertNull(wait.takeReadRequest())
    }

    private companion object {
        const val START = 1_000L
        const val STEP = 200L
    }
}
