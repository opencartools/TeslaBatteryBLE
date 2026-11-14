// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.infotainment

import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait.Demand
import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfotainmentSessionWaitTest {
    private val wait = InfotainmentSessionWait<String>()

    @Test
    fun `read with a session goes out without a handshake`() {
        assertEquals(Step.SEND, wait.onReadDue(awake = true, hasSession = true, request = "read"))
        assertFalse(wait.hasWaitingRead)
    }

    @Test
    fun `first due read starts the handshake and later ones wait for it`() {
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false, request = "read"))
        assertTrue(wait.hasWaitingRead)
        assertEquals(Step.WAIT_FOR_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false, request = "read again"))
        assertEquals(Step.WAIT_FOR_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false, request = "read again"))
        assertEquals("read again", wait.takeReadRequest(), "the newest request replaces the one waiting")
    }

    @Test
    fun `established session serves the waiting read once`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        assertEquals(setOf(Demand.READ), wait.onSessionEstablished())
        assertTrue(wait.onSessionEstablished().isEmpty())
        // The request is handed back once; a later session does not replay it.
        assertEquals("read", wait.takeReadRequest())
        assertNull(wait.takeReadRequest())
        assertFalse(wait.hasWaitingRead)
    }

    @Test
    fun `a read whose ring timed out still goes out when the session arrives`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        // The read's progress model expired meanwhile; the wait still holds its request.
        assertEquals(setOf(Demand.READ), wait.onSessionEstablished())
        assertTrue(wait.hasWaitingRead)
        assertEquals("read", wait.takeReadRequest())
        assertFalse(wait.hasWaitingRead)
    }

    @Test
    fun `session is requested only while awake and someone waits`() {
        assertFalse(wait.mayRequest(awake = true))
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        assertTrue(wait.mayRequest(awake = true))
        assertFalse(wait.mayRequest(awake = false))
    }

    @Test
    fun `a sleeping car never starts a handshake`() {
        assertEquals(Step.SKIP, wait.onReadDue(awake = false, hasSession = false, request = "read"))
        assertFalse(wait.hasWaitingRead)
        assertFalse(wait.mayRequest(awake = false))
    }

    @Test
    fun `give-up unblocks the next due read`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        wait.onGaveUp()
        assertFalse(wait.hasWaitingRead)
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false, request = "read"))
    }

    @Test
    fun `car falling asleep unblocks the next due read after the wake`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        wait.onAsleep()
        assertFalse(wait.hasWaitingRead)
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false, request = "read"))
    }

    @Test
    fun `dropped link unblocks the next due read after the reconnect`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        wait.onLinkDropped()
        assertFalse(wait.hasWaitingRead)
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false, request = "read"))
    }

    @Test
    fun `unstartable handshake does not leave a read waiting`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        wait.onNotStartable()
        assertFalse(wait.hasWaitingRead)
    }

    @Test
    fun `waiting read is dropped when the car is found asleep at the next due read`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        assertEquals(Step.SKIP, wait.onReadDue(awake = false, hasSession = false, request = "read"))
        assertFalse(wait.hasWaitingRead)
    }
}
