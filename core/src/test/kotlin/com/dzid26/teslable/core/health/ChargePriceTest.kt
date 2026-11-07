// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.protocol.ChargingStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChargePriceTest {
    private fun sample(
        minutes: Long,
        state: ChargingStateKind,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = 50,
        chargingState = state,
        chargeLimit = null,
    )

    private fun at(minutes: Long) = minutes * 60_000L

    // Charge A runs 0..60 minutes, the car then sits, charge B runs 600..660 and is still going.
    private val samples =
        listOf(
            sample(0, ChargingStateKind.Charging),
            sample(60, ChargingStateKind.Charging),
            sample(90, ChargingStateKind.Disconnected),
            sample(600, ChargingStateKind.Charging),
            sample(660, ChargingStateKind.Charging),
        )

    @Test
    fun `a price set while parked also prices the charge just finished`() {
        val changes = listOf(PriceChange(at(120), 0.30f))
        assertEquals(0.30f, priceForCharge(changes, samples, chargeEndMillis = at(60)))
    }

    @Test
    fun `a price set mid-charge covers that whole charge but not the one before`() {
        val changes = listOf(PriceChange(at(0), 0.20f), PriceChange(at(630), 0.40f))
        assertEquals(0.20f, priceForCharge(changes, samples, chargeEndMillis = at(60)))
        assertEquals(0.40f, priceForCharge(changes, samples, chargeEndMillis = at(660)))
    }

    @Test
    fun `a price set after a later charge began leaves the earlier charge alone`() {
        val changes = listOf(PriceChange(at(0), 0.20f), PriceChange(at(800), 0.50f))
        assertEquals(0.20f, priceForCharge(changes, samples, chargeEndMillis = at(60)))
        assertEquals(0.50f, priceForCharge(changes, samples, chargeEndMillis = at(660)))
    }

    @Test
    fun `no change in effect or a cleared price means no price`() {
        assertNull(priceForCharge(emptyList(), samples, at(60)))
        assertNull(priceForCharge(listOf(PriceChange(at(120), null)), samples, at(60)))
    }

    @Test
    fun `typing a price replaces the earlier keystrokes of the same edit`() {
        var changes = emptyList<PriceChange>()
        listOf(0L to 0.3f, 1L to 0.35f, 2L to 0.355f).forEach { (minutes, price) ->
            changes = recordPriceChange(changes, PriceChange(at(minutes), price))
        }
        assertEquals(listOf(PriceChange(at(2), 0.355f)), changes)
        changes = recordPriceChange(changes, PriceChange(at(600), 0.4f))
        assertEquals(2, changes.size)
    }

    @Test
    fun `a session is priced by the change in effect at the start of the latest charge begun by then`() {
        val sessions = chargeSessions(samples)
        val changes = listOf(PriceChange(at(-10), 0.20f), PriceChange(at(120), 0.30f), PriceChange(at(630), 0.40f))
        assertEquals(listOf(0.30f, 0.40f), pricesForSessions(changes, sessions))
    }

    @Test
    fun `no price for a time where no charge ends`() {
        assertNull(priceForCharge(listOf(PriceChange(at(-10), 0.20f)), samples, at(30)))
    }

    @Test
    fun `sessions before every price change and a cleared price have none`() {
        val sessions = chargeSessions(samples)
        assertEquals(listOf(null, null), pricesForSessions(emptyList(), sessions))
        assertEquals(listOf(null, 0.30f), pricesForSessions(listOf(PriceChange(at(-10), null), PriceChange(at(600), 0.30f)), sessions))
    }
}
