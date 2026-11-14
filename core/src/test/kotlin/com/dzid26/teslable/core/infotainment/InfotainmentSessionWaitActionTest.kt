// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.infotainment

import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait.Demand
import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The charge action's side of the shared Infotainment session wait. */
class InfotainmentSessionWaitActionTest {
    private val wait = InfotainmentSessionWait<String>()

    @Test
    fun `an action with a session goes out without a handshake`() {
        assertFalse(wait.requestForAction(awake = true, hasSession = true))
        assertTrue(wait.onSessionEstablished().isEmpty())
    }

    @Test
    fun `a queued action starts the handshake the next read then waits for`() {
        assertTrue(wait.requestForAction(awake = true, hasSession = false))
        assertEquals(Step.WAIT_FOR_HANDSHAKE, wait.onReadDue(awake = true, hasSession = false, request = "read"))
        assertTrue(wait.hasWaitingRead)
        assertEquals(setOf(Demand.ACTION, Demand.READ), wait.onSessionEstablished())
    }

    @Test
    fun `a read and an action share the one request`() {
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        assertFalse(wait.requestForAction(awake = true, hasSession = false))
        assertEquals(setOf(Demand.READ, Demand.ACTION), wait.onSessionEstablished())
    }

    @Test
    fun `a released action leaves the waiting read alone`() {
        assertTrue(wait.requestForAction(awake = true, hasSession = false))
        wait.onReadDue(awake = true, hasSession = false, request = "read")
        wait.onActionReleased()
        assertEquals(setOf(Demand.READ), wait.onSessionEstablished())
        assertTrue(wait.hasWaitingRead)
    }

    @Test
    fun `a sleeping queued action is not left waiting`() {
        assertFalse(wait.requestForAction(awake = false, hasSession = false))
        assertFalse(wait.mayRequest(awake = true))
        assertTrue(wait.onSessionEstablished().isEmpty())
    }
}
