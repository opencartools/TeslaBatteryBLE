// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripAlertsTest {
    private val minute = 60_000L

    private fun notice(destination: String? = "Home") = TripNotice(destination, 47, 12, null, 44, 119)

    @Test
    fun `alerts once when a trip starts`() {
        val alerts = TripAlerts()
        assertTrue(alerts.shouldAlert("car", notice(), 0))
        assertFalse(alerts.shouldAlert("car", notice(), minute))
    }

    @Test
    fun `a short gap in the notice does not alert again`() {
        val alerts = TripAlerts()
        alerts.shouldAlert("car", notice(), 0)
        assertFalse(alerts.shouldAlert("car", null, 2 * minute))
        assertFalse(alerts.shouldAlert("car", notice(), 3 * minute))
    }

    @Test
    fun `a new destination alerts`() {
        val alerts = TripAlerts()
        alerts.shouldAlert("car", notice("Home"), 0)
        assertTrue(alerts.shouldAlert("car", notice("Work"), minute))
    }

    @Test
    fun `no calls for a long while does not end the trip`() {
        val alerts = TripAlerts()
        alerts.shouldAlert("car", notice(), 0)
        assertFalse(alerts.shouldAlert("car", notice(), 20 * minute))
    }

    @Test
    fun `the same destination after a long gap is a new trip`() {
        val alerts = TripAlerts()
        alerts.shouldAlert("car", notice(), 0)
        assertFalse(alerts.shouldAlert("car", null, minute))
        assertFalse(alerts.shouldAlert("car", null, 12 * minute))
        assertTrue(alerts.shouldAlert("car", notice(), 13 * minute))
    }

    @Test
    fun `cars are tracked apart`() {
        val alerts = TripAlerts()
        alerts.shouldAlert("a", notice(), 0)
        assertTrue(alerts.shouldAlert("b", notice(), minute))
        assertFalse(alerts.shouldAlert("a", notice(), minute))
    }
}
