// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosureAlertEpisodesTest {
    private val stable = ClosureAlertEpisodes.STABLE_MILLIS
    private val alerts = ClosureAlertEpisodes()

    private fun connected(
        at: Long = 0,
        bleName: String = "car",
    ) = alerts.onConnectionStarted(bleName, at)

    private fun open(bleName: String = "car") = alerts.onStatus(bleName, closureOpen = true)

    private fun closed(bleName: String = "car") = alerts.onStatus(bleName, closureOpen = false)

    private fun drop(
        at: Long,
        bleName: String = "car",
        enabled: Boolean = true,
    ) = alerts.onLinkDropped(bleName, at, enabled)

    @Test
    fun `beeps on the first drop after a status saw an open closure`() {
        connected(0)
        open()
        assertTrue(drop(at = 10_000))
    }

    @Test
    fun `stays silent when every closure was shut`() {
        connected(0)
        closed()
        assertFalse(drop(at = 10_000))
    }

    @Test
    fun `stays silent without a status on this connection`() {
        connected(0)
        open()
        connected(at = 20_000)
        assertFalse(drop(at = 30_000))
    }

    @Test
    fun `a drop with no status at all stays silent`() {
        assertFalse(drop(at = 10_000))
    }

    @Test
    fun `retries and a second drop in the same stretch stay silent`() {
        connected(0)
        open()
        assertTrue(drop(at = 10_000))
        assertFalse(drop(at = 11_000))
    }

    @Test
    fun `a quick flap with the same closure still open stays silent`() {
        connected(0)
        open()
        assertTrue(drop(at = 10_000))
        connected(at = 20_000)
        open()
        assertFalse(drop(at = 30_000))
    }

    @Test
    fun `a link up for the full stable time re-arms the next drop`() {
        connected(0)
        open()
        assertTrue(drop(at = 10_000))
        connected(at = 20_000)
        open()
        assertTrue(drop(at = 20_000 + stable))
    }

    @Test
    fun `one millisecond short of stable does not re-arm`() {
        connected(0)
        open()
        assertTrue(drop(at = 10_000))
        connected(at = 20_000)
        open()
        assertFalse(drop(at = 20_000 + stable - 1))
    }

    @Test
    fun `a stable link without a fresh status cannot re-arm`() {
        connected(0)
        open()
        assertTrue(drop(at = 10_000))
        connected(at = 20_000)
        assertFalse(drop(at = 20_000 + stable))
    }

    @Test
    fun `every closure shutting re-arms the next open stretch`() {
        connected(0)
        open()
        assertTrue(drop(at = 10_000))
        connected(at = 20_000)
        closed()
        open()
        assertTrue(drop(at = 30_000))
    }

    @Test
    fun `the switch off suppresses without spending the stretch`() {
        connected(0)
        open()
        assertFalse(drop(at = 10_000, enabled = false))
        connected(at = 20_000)
        open()
        assertTrue(drop(at = 30_000))
    }

    @Test
    fun `disarming forgets every episode`() {
        connected(0)
        open()
        alerts.disarmAll()
        assertFalse(drop(at = 10_000))
    }

    @Test
    fun `cars are tracked apart`() {
        connected(bleName = "a")
        connected(bleName = "b")
        open("a")
        open("b")
        assertTrue(drop(at = 10_000, bleName = "a"))
        assertTrue(drop(at = 10_000, bleName = "b"))
        assertFalse(drop(at = 11_000, bleName = "a"))
    }
}
