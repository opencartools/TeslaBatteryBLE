// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.protocol.ChargingStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionPriceTest {
    private fun session(
        start: Long,
        end: Long,
        kind: ChargerKind? = ChargerKind.AC,
    ) = ChargeSession(start * MINUTE, end * MINUTE, 20f, 80f, 40f, 160f, vehicleId = "car", chargerKind = kind)

    private fun override(
        start: Long,
        end: Long,
        price: Float = 0.3f,
    ) = ChargePriceOverride("car", start * MINUTE, end * MINUTE, SessionPrice.PerKwh(price), "EUR")

    @Test
    fun `an override stays with its session when a re-cut moves it by a reading`() {
        val sessions = listOf(session(0, 60), session(600, 700))
        val overrides = listOf(override(1, 60, 0.3f), override(599, 700, 0.5f))

        assertEquals(overrides, matchOverrides(sessions, overrides))
        assertEquals(
            listOf(overrides[0] to overrides[0].copy(startMillis = 0), overrides[1] to overrides[1].copy(startMillis = 600 * MINUTE)),
            movedOverrides(sessions, overrides),
        )
    }

    @Test
    fun `an override far from every session matches none`() {
        assertEquals(listOf(null), matchOverrides(listOf(session(0, 60)), listOf(override(200, 260))))
    }

    @Test
    fun `when a re-cut joins two sessions the longer override wins and the other is left for the owner`() {
        val joined = listOf(session(0, 300))
        val overrides = listOf(override(0, 60, 0.3f), override(100, 300, 0.5f))

        assertEquals(listOf(overrides[1]), matchOverrides(joined, overrides))
    }

    @Test
    fun `the owner's price wins, else the home price for any charge but DC`() {
        val owner = override(0, 60, 0.5f)
        assertEquals(SessionPrice.PerKwh(0.5f), sessionPrice(session(0, 60), owner, homePricePerKwh = 0.2f))
        assertEquals(SessionPrice.PerKwh(0.2f), sessionPrice(session(0, 60), null, homePricePerKwh = 0.2f))
        assertEquals(SessionPrice.PerKwh(0.2f), sessionPrice(session(0, 60, kind = null), null, homePricePerKwh = 0.2f))
        assertNull(sessionPrice(session(0, 60, kind = ChargerKind.DC), null, homePricePerKwh = 0.2f))
        assertNull(sessionPrice(session(0, 60), null, homePricePerKwh = null))
    }

    @Test
    fun `a total is the cost whatever the energy, a per kWh price needs it`() {
        assertEquals(12f, SessionPrice.Total(12f).cost(null))
        assertEquals(8f, SessionPrice.PerKwh(0.2f).cost(40f))
        assertNull(SessionPrice.PerKwh(0.2f).cost(null))
    }

    @Test
    fun `the charger kind comes from the supply voltage, or from a power no AC supply reaches`() {
        fun charging(
            minutes: Long,
            volts: Int?,
            energy: Float,
        ) = BatterySample(minutes * MINUTE, 50, ChargingStateKind.Charging, 80, chargeEnergyAdded = energy, chargerVoltage = volts)

        assertEquals(ChargerKind.AC, chargeSessions(listOf(charging(0, 230, 0f), charging(60, 231, 11f))).single().chargerKind)
        assertEquals(ChargerKind.DC, chargeSessions(listOf(charging(0, 2, 0f), charging(30, 3, 40f))).single().chargerKind)
        assertEquals(ChargerKind.DC, chargeSessions(listOf(charging(0, null, 0f), charging(30, null, 40f))).single().chargerKind)
        assertNull(chargeSessions(listOf(charging(0, null, 0f), charging(60, null, 7f))).single().chargerKind)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
