// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DischargeProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ParkedRatesTest {
    private fun projection(perDay: Double) =
        DischargeProjection(
            from = BatterySample(timestampMillis = 0, batteryLevel = 60, chargingState = null, chargeLimit = null),
            projectedPercent = 58,
            projectedAtMillis = 0,
            horizonMillis = 0,
            percentPerHour = perDay / 24,
        )

    @Test
    fun `the power-down rate shows`() {
        val rates = parkedRates(projection(0.4), Locale.US)
        assertEquals("0.4", rates.main)
    }

    @Test
    fun `a tiny drain has no figure, not a zero`() {
        assertNull(parkedRates(projection(0.03), Locale.US).main)
    }

    @Test
    fun `the horizon reads in whole hours under a day and whole days from a day`() {
        val hours = parkedHorizon(12 * 60 * 60_000L)
        assertEquals(12, hours.amount)
        assertFalse(hours.days)
        val days = parkedHorizon(3 * 24 * 60 * 60_000L)
        assertEquals(3, days.amount)
        assertTrue(days.days)
    }
}
