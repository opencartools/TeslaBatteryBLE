// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockAlertsTest {
    private val alerts = UnlockAlerts()

    private fun away() = alerts.onStatus("car", locked = false, userPresent = false)

    private fun locked() = alerts.onStatus("car", locked = true, userPresent = false)

    private fun present() = alerts.onStatus("car", locked = false, userPresent = true)

    private fun drop(
        bleName: String = "car",
        enabled: Boolean = true,
        trackingEnabled: Boolean = true,
        bluetoothOff: Boolean = false,
    ) = alerts.onLinkDropped(bleName, enabled, trackingEnabled, bluetoothOff)

    @Test
    fun `alerts once when an unlocked empty car drops off`() {
        away()
        assertTrue(drop())
        assertFalse(drop())
    }

    @Test
    fun `a drop before any status stays silent`() {
        assertFalse(drop())
    }

    @Test
    fun `a locked car stays silent`() {
        locked()
        assertFalse(drop())
    }

    @Test
    fun `a car with someone inside stays silent`() {
        present()
        assertFalse(drop())
    }

    @Test
    fun `the toggle suppresses the alert`() {
        away()
        assertFalse(drop(enabled = false))
    }

    @Test
    fun `turning the toggle back on can still alert the same episode`() {
        away()
        assertFalse(drop(enabled = false))
        assertTrue(drop())
    }

    @Test
    fun `tracking switched off suppresses the alert and leaves the episode armed`() {
        away()
        assertFalse(drop(trackingEnabled = false))
        assertTrue(drop())
    }

    @Test
    fun `Bluetooth switched off suppresses the alert and leaves the episode armed`() {
        away()
        assertFalse(drop(bluetoothOff = true))
        assertTrue(drop())
    }

    @Test
    fun `locking and unlocking again alerts again`() {
        away()
        assertTrue(drop())
        locked()
        away()
        assertTrue(drop())
    }

    @Test
    fun `the driver leaving a still unlocked car is a new episode`() {
        present()
        away()
        assertTrue(drop())
        present()
        away()
        assertTrue(drop())
    }

    @Test
    fun `a fresh status while already away keeps the episode spent`() {
        away()
        assertTrue(drop())
        // Reconnecting polls the status again; it is still unlocked and empty, so this is the same episode.
        away()
        assertFalse(drop())
    }

    @Test
    fun `disarming forgets the episode`() {
        away()
        alerts.disarmAll()
        assertFalse(drop())
    }

    @Test
    fun `cars are tracked apart`() {
        alerts.onStatus("a", locked = false, userPresent = false)
        alerts.onStatus("b", locked = false, userPresent = false)
        assertTrue(drop("a"))
        assertTrue(drop("b"))
        assertFalse(drop("a"))
    }
}
