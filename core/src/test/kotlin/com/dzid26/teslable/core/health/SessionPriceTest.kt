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
        energy: Float = 40f,
    ) = ChargeSession(start * MINUTE, end * MINUTE, 20f, 80f, energy, 160f, vehicleId = "car", chargerKind = kind)

    private fun stored(
        start: Long,
        end: Long,
        price: SessionPrice? = SessionPrice.PerKwh(0.3f),
        byOwner: Boolean = true,
        currency: String = "EUR",
    ) = StoredChargePrice("car", start * MINUTE, end * MINUTE, price, currency, byOwner)

    private val home = PriceDefaults(listOf(PriceChange(-10 * MINUTE, 0.2f)), "PLN")

    private fun reconcile(
        sessions: List<ChargeSession>,
        rows: List<StoredChargePrice>,
    ) = reconcilePrices(sessions, rows, home)

    @Test
    fun `every finished session gets the home price of its time, frozen, and the newest stays live`() {
        val defaults = PriceDefaults(listOf(PriceChange(-10 * MINUTE, 0.2f), PriceChange(650 * MINUTE, 0.4f)), "PLN")
        val sessions = listOf(session(0, 60), session(600, 700), session(1200, 1300, kind = ChargerKind.DC), session(2000, 2100))

        assertEquals(
            listOf(
                stored(0, 60, SessionPrice.PerKwh(0.2f), byOwner = false, currency = "PLN").copy(place = ChargePlace.HOME),
                // A price set after a charge started, while it was the newest, covers it.
                stored(600, 700, SessionPrice.PerKwh(0.4f), byOwner = false, currency = "PLN").copy(place = ChargePlace.HOME),
                // A fast charger is never at home: empty until the car's own cost is read.
                stored(1200, 1300, null, byOwner = false, currency = "PLN"),
            ),
            reconcilePrices(sessions, emptyList(), defaults),
        )
    }

    @Test
    fun `an automatic price stays frozen while its session keeps its shape, and is frozen again when it changes`() {
        val kept = stored(0, 60, SessionPrice.PerKwh(0.5f), byOwner = false)
        val moved = stored(599, 700, SessionPrice.PerKwh(0.5f), byOwner = false)
        val sessions = listOf(session(0, 60), session(600, 700), session(900, 950))

        assertEquals(
            listOf(kept, stored(600, 700, SessionPrice.PerKwh(0.2f), byOwner = false, currency = "PLN").copy(place = ChargePlace.HOME)),
            reconcile(sessions, listOf(kept, moved)),
        )
    }

    @Test
    fun `an owner's edit follows its session when a re-cut moves it`() {
        assertEquals(listOf(stored(600, 700)), reconcile(listOf(session(600, 700), session(900, 950)), listOf(stored(599, 700))))
    }

    @Test
    fun `a join keeps the owner's edit over an automatic price, adds up totals, and keeps an edit it cannot merge`() {
        val auto = stored(0, 60, SessionPrice.PerKwh(0.2f), byOwner = false)
        val edit = stored(70, 120, SessionPrice.PerKwh(0.5f))
        assertEquals(listOf(edit.copy(startMillis = 0)), reconcile(listOf(session(0, 120), session(900, 950)), listOf(auto, edit)))

        val totals = listOf(stored(0, 60, SessionPrice.Total(8f)), stored(70, 120, SessionPrice.Total(4f)))
        assertEquals(
            listOf(stored(0, 120, SessionPrice.Total(12f))),
            reconcile(listOf(session(0, 120), session(900, 950)), totals),
        )

        // Two edits that do not add up: the longer one prices the session, the other stays stored, and stays so.
        val edits = listOf(stored(0, 40, SessionPrice.PerKwh(0.4f)), stored(50, 120, SessionPrice.Total(6f)))
        val joined = reconcile(listOf(session(0, 120), session(900, 950)), edits)
        assertEquals(listOf(stored(0, 120, SessionPrice.Total(6f)), edits[0]), joined)
        assertEquals(joined, reconcile(listOf(session(0, 120), session(900, 950)), joined))
    }

    @Test
    fun `a split copies a price per kWh to every part and shares a total by energy`() {
        val parts = listOf(session(0, 60, energy = 30f), session(70, 120, energy = 10f), session(900, 950))

        assertEquals(
            listOf(stored(0, 60), stored(70, 120)),
            reconcile(parts, listOf(stored(0, 120))),
        )
        assertEquals(
            listOf(stored(0, 60, SessionPrice.Total(9f)), stored(70, 120, SessionPrice.Total(3f))),
            reconcile(parts, listOf(stored(0, 120, SessionPrice.Total(12f)))),
        )
    }

    @Test
    fun `a stored price with no session is kept, and the slack never hands it to a neighbour`() {
        val far = stored(200, 260)
        assertEquals(listOf(far), reconcile(listOf(session(0, 60)), listOf(far)))

        // Two charges three minutes apart each keep their own total.
        val rows = listOf(stored(0, 60, SessionPrice.Total(5f)), stored(63, 200, SessionPrice.Total(7f)))
        assertEquals(rows, reconcile(listOf(session(0, 60), session(63, 200), session(900, 950)), rows))
    }

    @Test
    fun `the screens show the stored price, even an empty one, else the live automatic price`() {
        val sessions = listOf(session(0, 60), session(600, 700), session(1200, 1300))
        val rows = listOf(stored(0, 60, SessionPrice.Total(9f)), stored(600, 700, null, byOwner = false, currency = "PLN"))

        assertEquals(
            listOf(
                ChargeSessionPrice(SessionPrice.Total(9f), "EUR", byOwner = true, place = null),
                ChargeSessionPrice(null, "PLN", byOwner = false, place = null),
                ChargeSessionPrice(SessionPrice.PerKwh(0.2f), null, byOwner = false, place = ChargePlace.HOME),
            ),
            sessionPrices(sessions, rows, home.homeChanges),
        )
        assertNull(automaticPrice(session(0, 60, kind = ChargerKind.DC), 0.2f))
        assertEquals(SessionPrice.PerKwh(0.2f), automaticPrice(session(0, 60, kind = null), 0.2f))
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

    private companion object {
        const val MINUTE = 60_000L
    }
}
