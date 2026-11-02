// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockAlertsTest {
    private val wait = UnlockAlerts.DEFAULT_WAIT_MILLIS
    private val alerts = UnlockAlerts(wait)

    private fun eligible(
        bleName: String = "car",
        closureOpen: Boolean = true,
        userPresent: Boolean = false,
    ) = alerts.onStatus(bleName, unlocked = true, closureOpen = closureOpen, userPresent = userPresent)

    private fun due(
        bleName: String = "car",
        nowMillis: Long = wait,
        enabled: Boolean = true,
    ) = alerts.due(bleName, nowMillis, enabled)

    @Test
    fun `an unlocked car with a door open alerts only after the wait`() {
        eligible(closureOpen = true, userPresent = true)
        alerts.onLinkDropped("car", nowMillis = 0)
        assertFalse(due(nowMillis = wait - 1))
        assertTrue(due(nowMillis = wait))
    }

    @Test
    fun `nobody inside with everything shut is enough`() {
        eligible(closureOpen = false, userPresent = false)
        alerts.onLinkDropped("car", nowMillis = 0)
        assertTrue(due(nowMillis = wait))
    }

    @Test
    fun `a shut car with someone inside never alerts`() {
        eligible(closureOpen = false, userPresent = true)
        alerts.onLinkDropped("car", nowMillis = 0)
        assertFalse(due(nowMillis = 2 * wait))
    }

    @Test
    fun `a locked car never alerts even with a door open`() {
        alerts.onStatus("car", unlocked = false, closureOpen = true, userPresent = false)
        alerts.onLinkDropped("car", nowMillis = 0)
        assertFalse(due(nowMillis = 2 * wait))
    }

    @Test
    fun `a drop before any status stays silent`() {
        alerts.onLinkDropped("car", nowMillis = 0)
        assertFalse(due(nowMillis = 2 * wait))
    }

    @Test
    fun `a quick reconnect cancels the wait`() {
        eligible()
        alerts.onLinkDropped("car", nowMillis = 0)
        alerts.onLinkReady("car")
        assertFalse(due(nowMillis = wait))
    }

    @Test
    fun `a second drop after a reconnect gets its own full wait`() {
        eligible()
        alerts.onLinkDropped("car", nowMillis = 0)
        alerts.onLinkReady("car")
        alerts.onLinkDropped("car", nowMillis = 2_000)
        assertFalse(due(nowMillis = wait))
        assertTrue(due(nowMillis = wait + 2_000))
    }

    @Test
    fun `the alert fires once per episode`() {
        eligible()
        alerts.onLinkDropped("car", nowMillis = 0)
        assertTrue(due(nowMillis = wait))
        assertFalse(due(nowMillis = 2 * wait))
    }

    @Test
    fun `the toggle off suppresses without spending the episode`() {
        eligible()
        alerts.onLinkDropped("car", nowMillis = 0)
        assertFalse(due(enabled = false))
        assertTrue(due(enabled = true))
    }

    @Test
    fun `a later locked status cancels the wait and re-arms`() {
        eligible()
        alerts.onLinkDropped("car", nowMillis = 0)
        alerts.onStatus("car", unlocked = false, closureOpen = false, userPresent = false)
        assertFalse(due(nowMillis = wait))
        eligible()
        alerts.onLinkDropped("car", nowMillis = 2 * wait)
        assertTrue(due(nowMillis = 3 * wait))
    }

    @Test
    fun `disarming forgets every episode`() {
        eligible()
        alerts.onLinkDropped("car", nowMillis = 0)
        alerts.disarmAll()
        assertFalse(due(nowMillis = 2 * wait))
    }

    @Test
    fun `cars are tracked apart`() {
        eligible("a")
        eligible("b")
        alerts.onLinkDropped("a", nowMillis = 0)
        alerts.onLinkReady("b")
        assertTrue(due("a", nowMillis = wait))
        assertFalse(due("b", nowMillis = wait))
    }
}
