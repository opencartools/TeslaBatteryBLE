// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosureAlertsTest {
    private val alerts = ClosureAlerts()

    private fun open() = alerts.onStatus("car", closureOpen = true)

    private fun closed() = alerts.onStatus("car", closureOpen = false)

    private fun drop(
        bleName: String = "car",
        enabled: Boolean = true,
    ) = alerts.onLinkDropped(bleName, enabled)

    @Test
    fun `beeps on the first drop after a status saw an open closure`() {
        open()
        assertTrue(drop())
    }

    @Test
    fun `stays silent when every closure was shut`() {
        closed()
        assertFalse(drop())
    }

    @Test
    fun `stays silent without a status on this connection`() {
        open()
        alerts.onConnectionStarted("car")
        assertFalse(drop())
    }

    @Test
    fun `a drop with no status at all stays silent`() {
        assertFalse(drop())
    }

    @Test
    fun `retries and a second drop in the same stretch stay silent`() {
        open()
        assertTrue(drop())
        assertFalse(drop())
    }

    @Test
    fun `a quick flap with the same closure still open stays silent`() {
        open()
        assertTrue(drop())
        alerts.onConnectionStarted("car")
        open()
        assertFalse(drop())
    }

    @Test
    fun `a link that stays up for a minute re-arms the next drop`() {
        open()
        assertTrue(drop())
        alerts.onConnectionStarted("car")
        open()
        alerts.onLinkUpStable("car")
        assertTrue(drop())
    }

    @Test
    fun `a stable link without a fresh status cannot re-arm`() {
        open()
        assertTrue(drop())
        alerts.onConnectionStarted("car")
        alerts.onLinkUpStable("car")
        assertFalse(drop())
    }

    @Test
    fun `every closure shutting re-arms the next open stretch`() {
        open()
        assertTrue(drop())
        alerts.onConnectionStarted("car")
        closed()
        open()
        assertTrue(drop())
    }

    @Test
    fun `the switch off suppresses without spending the stretch`() {
        open()
        assertFalse(drop(enabled = false))
        open()
        assertTrue(drop())
    }

    @Test
    fun `disarming forgets every episode`() {
        open()
        alerts.disarmAll()
        assertFalse(drop())
    }

    @Test
    fun `cars are tracked apart`() {
        alerts.onStatus("a", closureOpen = true)
        alerts.onStatus("b", closureOpen = true)
        assertTrue(drop("a"))
        assertTrue(drop("b"))
        assertFalse(drop("a"))
    }
}
