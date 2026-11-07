// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The step a queued quick action takes when the car's sleep status arrives. The bug this guards: an
 * Infotainment session that outlived the car's sleep read as "a handshake is in flight", so the action
 * waited for a session that would never be requested and was silently dropped.
 */
class QuickActionStepTest {
    @Test
    fun `an existing session sends the queued action at once`() {
        assertEquals(
            QuickActionStep.SEND,
            nextQuickActionStep(asleep = false, hasPending = true, hasSession = true, readWaiting = false),
        )
    }

    @Test
    fun `a sleeping car wakes first, whatever session it still has`() {
        assertEquals(
            QuickActionStep.NOTHING,
            nextQuickActionStep(asleep = true, hasPending = true, hasSession = true, readWaiting = false),
        )
    }

    @Test
    fun `a read's handshake carries the queued action`() {
        assertEquals(
            QuickActionStep.WAIT_FOR_SESSION,
            nextQuickActionStep(asleep = false, hasPending = true, hasSession = false, readWaiting = true),
        )
    }

    @Test
    fun `an awake car with no session asks for one`() {
        assertEquals(
            QuickActionStep.START_SESSION,
            nextQuickActionStep(asleep = false, hasPending = true, hasSession = false, readWaiting = false),
        )
    }

    @Test
    fun `nothing is queued, so nothing happens`() {
        assertEquals(
            QuickActionStep.NOTHING,
            nextQuickActionStep(asleep = false, hasPending = false, hasSession = false, readWaiting = false),
        )
    }
}
