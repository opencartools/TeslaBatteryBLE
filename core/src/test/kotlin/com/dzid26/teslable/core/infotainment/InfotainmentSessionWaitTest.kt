// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.infotainment

import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait.Demand
import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfotainmentSessionWaitTest {
    private val wait = InfotainmentSessionWait()

    @Test
    fun `read with a session goes out without a handshake`() {
        assertEquals(Step.SEND, wait.onReadDue(awake = true, hasSession = true))
        assertFalse(wait.readWaiting)
    }

    @Test
    fun `first due read starts the handshake and later ones wait for it`() {
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false))
        assertTrue(wait.readWaiting)
        assertEquals(Step.WAIT_FOR_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false))
        assertEquals(Step.WAIT_FOR_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `established session releases the waiting read once`() {
        wait.onReadDue(awake = true, hasSession = false)
        assertEquals(setOf(Demand.READ), wait.onSessionEstablished())
        assertFalse(wait.readWaiting)
        assertTrue(wait.onSessionEstablished().isEmpty())
    }

    @Test
    fun `session is requested only while awake and someone waits`() {
        assertFalse(wait.mayRequest(awake = true))
        wait.onReadDue(awake = true, hasSession = false)
        assertTrue(wait.mayRequest(awake = true))
        assertFalse(wait.mayRequest(awake = false))
    }

    @Test
    fun `a sleeping car never starts a handshake`() {
        assertEquals(Step.SKIP, wait.onReadDue(awake = false, hasSession = false))
        assertFalse(wait.readWaiting)
        assertFalse(wait.mayRequest(awake = false))
    }

    @Test
    fun `an action with a session goes out without a handshake`() {
        assertEquals(Step.SEND, wait.onActionDue(awake = true, hasSession = true))
        assertFalse(wait.actionWaiting)
    }

    @Test
    fun `a queued action starts the handshake the next read then waits for`() {
        assertEquals(Step.START_HANDSHAKE, wait.onActionDue(awake = true, hasSession = false))
        assertTrue(wait.actionWaiting)
        assertEquals(Step.WAIT_FOR_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false))
        assertTrue(wait.readWaiting)
        assertEquals(setOf(Demand.ACTION, Demand.READ), wait.onSessionEstablished())
        assertFalse(wait.hasWaiting)
    }

    @Test
    fun `a read and an action share the one request`() {
        wait.onReadDue(awake = true, hasSession = false)
        assertEquals(Step.WAIT_FOR_HANDSHAKE, wait.onActionDue(awake = true, hasSession = false))
        assertTrue(wait.readWaiting)
        assertTrue(wait.actionWaiting)
        assertEquals(setOf(Demand.READ, Demand.ACTION), wait.onSessionEstablished())
    }

    @Test
    fun `a released action leaves the waiting read alone`() {
        wait.onActionDue(awake = true, hasSession = false)
        wait.onReadDue(awake = true, hasSession = false)
        wait.onActionReleased()
        assertFalse(wait.actionWaiting)
        assertTrue(wait.readWaiting)
        assertTrue(wait.mayRequest(awake = true))
    }

    @Test
    fun `a sleeping queued action is not left waiting`() {
        assertEquals(Step.SKIP, wait.onActionDue(awake = false, hasSession = false))
        assertFalse(wait.actionWaiting)
        assertFalse(wait.mayRequest(awake = true))
    }

    @Test
    fun `give-up unblocks the next due read`() {
        wait.onReadDue(awake = true, hasSession = false)
        wait.onGaveUp()
        assertFalse(wait.readWaiting)
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `car falling asleep unblocks the next due read after the wake`() {
        wait.onReadDue(awake = true, hasSession = false)
        wait.onAsleep()
        assertFalse(wait.readWaiting)
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `dropped link unblocks the next due read after the reconnect`() {
        wait.onReadDue(awake = true, hasSession = false)
        wait.onLinkDropped()
        assertFalse(wait.readWaiting)
        assertEquals(Step.START_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `unstartable handshake does not leave a read waiting`() {
        wait.onReadDue(awake = true, hasSession = false)
        wait.onNotStartable()
        assertFalse(wait.readWaiting)
    }

    @Test
    fun `waiting read is dropped when the car is found asleep at the next due read`() {
        wait.onReadDue(awake = true, hasSession = false)
        assertEquals(Step.SKIP, wait.onReadDue(awake = false, hasSession = false))
        assertFalse(wait.readWaiting)
    }
}
