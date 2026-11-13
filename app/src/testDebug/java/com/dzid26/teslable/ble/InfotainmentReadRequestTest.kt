// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.infotainment.InfotainmentPollPolicy.ReadKind
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
        read.begin(full, nowMs = START, replies = 4, afterHandshake = false)
        read.onSent(START)
        read.begin(confirmation, nowMs = START + STEP, replies = 1, afterHandshake = false)
        assertEquals(ReadKind.CONFIRMATION, read.request?.kind)
        assertTrue(read.active)
        assertTrue(read.running)
    }

    @Test
    fun aSkippedReadForgetsItsRequest() {
        read.begin(confirmation, nowMs = START, replies = 1, afterHandshake = true)
        read.onSkipped()
        assertNull(read.request)
        assertFalse(read.active)
    }

    private companion object {
        const val START = 1_000L
        const val STEP = 200L
    }
}
