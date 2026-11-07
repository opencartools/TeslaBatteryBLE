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
    fun `each session takes its own edit, else the home price in effect for it`() {
        val sessions = listOf(session(0, 60), session(600, 700), session(1200, 1300, kind = ChargerKind.DC))
        val homeChanges = listOf(PriceChange(-10 * MINUTE, 0.2f), PriceChange(650 * MINUTE, 0.4f))

        assertEquals(
            listOf(SessionPrice.PerKwh(0.2f), SessionPrice.Total(9f), null),
            sessionPrices(
                sessions,
                listOf(ChargePriceOverride("car", 600 * MINUTE, 700 * MINUTE, SessionPrice.Total(9f), "EUR")),
                homeChanges,
            ),
        )
        assertEquals(listOf(SessionPrice.PerKwh(0.2f), SessionPrice.PerKwh(0.4f), null), sessionPrices(sessions, emptyList(), homeChanges))
    }

    @Test
    fun `a total is the cost whatever the energy, a per kWh price needs it`() {
        assertEquals(12f, SessionPrice.Total(12f).cost(null))
        assertEquals(8f, SessionPrice.PerKwh(0.2f).cost(40f))
        assertNull(SessionPrice.PerKwh(0.2f).cost(null))
    }

    @Test
    fun `the charger kind comes from the car's fast charger flag, else from a power no AC supply reaches`() {
        fun charging(
            minutes: Long,
            volts: Int?,
            energy: Float,
            fast: Boolean? = null,
        ) = BatterySample(
            minutes * MINUTE,
            50,
            ChargingStateKind.Charging,
            80,
            chargeEnergyAdded = energy,
            chargerVoltage = volts,
            fastChargerPresent = fast,
        )

        assertEquals(
            ChargerKind.AC,
            chargeSessions(listOf(charging(0, 230, 0f, false), charging(60, 231, 11f, false))).single().chargerKind,
        )
        // Some firmwares report the DC bus voltage while fast charging: the flag wins over it.
        assertEquals(ChargerKind.DC, chargeSessions(listOf(charging(0, 400, 0f, true), charging(30, 390, 40f, true))).single().chargerKind)
        assertEquals(ChargerKind.DC, chargeSessions(listOf(charging(0, 400, 0f), charging(30, 390, 40f))).single().chargerKind)
        assertEquals(ChargerKind.AC, chargeSessions(listOf(charging(0, 230, 0f), charging(60, 231, 11f))).single().chargerKind)
        assertNull(chargeSessions(listOf(charging(0, 2, 0f), charging(60, 3, 7f))).single().chargerKind)
        assertNull(chargeSessions(listOf(charging(0, null, 0f), charging(60, null, 7f))).single().chargerKind)
    }

    @Test
    fun `two charges minutes apart each keep their own override`() {
        val sessions = listOf(session(0, 60), session(63, 200))
        val overrides = listOf(override(0, 60, 0.3f), override(63, 200, 0.5f))

        assertEquals(overrides, matchOverrides(sessions, overrides))
        // The first session overlaps the second override too, through the slack; the better pair still wins.
        assertEquals(listOf(null, overrides[1]), matchOverrides(sessions, listOf(overrides[1])))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
