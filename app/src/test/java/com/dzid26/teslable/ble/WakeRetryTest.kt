// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the bounded wake retry: first send, retries while unconfirmed, stop and give up. */
class WakeRetryTest {
    private val retry = WakeRetry()

    @Test
    fun `the first tick with a session sends the wake`() {
        assertEquals(WakeRetry.Action.SEND, retry.onTick(awake = false, hasVcsecSession = true))
        assertEquals(1, retry.sent)
    }

    @Test
    fun `an unconfirmed car gets the wake again on every tick`() {
        repeat(WakeRetry.MAX_ATTEMPTS) {
            assertEquals(WakeRetry.Action.SEND, retry.onTick(awake = false, hasVcsecSession = true))
        }

        assertEquals(WakeRetry.MAX_ATTEMPTS, retry.sent)
    }

    @Test
    fun `a status that says awake stops the retries`() {
        retry.onTick(awake = false, hasVcsecSession = true)
        val sentBefore = retry.sent

        assertEquals(WakeRetry.Action.CONFIRMED, retry.onTick(awake = true, hasVcsecSession = true))
        assertEquals(sentBefore, retry.sent)
    }

    @Test
    fun `confirmation wins even after the bound is spent`() {
        repeat(WakeRetry.MAX_ATTEMPTS) { retry.onTick(awake = false, hasVcsecSession = true) }

        assertEquals(WakeRetry.Action.CONFIRMED, retry.onTick(awake = true, hasVcsecSession = true))
    }

    @Test
    fun `it gives up once the bound is spent`() {
        repeat(WakeRetry.MAX_ATTEMPTS) { retry.onTick(awake = false, hasVcsecSession = true) }

        assertEquals(WakeRetry.Action.GIVE_UP, retry.onTick(awake = false, hasVcsecSession = true))
        assertEquals(WakeRetry.MAX_ATTEMPTS, retry.sent)
    }

    @Test
    fun `a tick without a session waits and does not spend an attempt`() {
        assertEquals(WakeRetry.Action.WAIT, retry.onTick(awake = false, hasVcsecSession = false))
        assertEquals(0, retry.sent)
        assertEquals(WakeRetry.Action.SEND, retry.onTick(awake = false, hasVcsecSession = true))
    }

    @Test
    fun `a new pull starts the count over`() {
        repeat(WakeRetry.MAX_ATTEMPTS) { retry.onTick(awake = false, hasVcsecSession = true) }

        retry.reset()

        assertEquals(0, retry.sent)
        assertEquals(WakeRetry.Action.SEND, retry.onTick(awake = false, hasVcsecSession = true))
    }
}
