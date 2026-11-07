// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.protocol.LastPaidCharge
import kotlin.test.Test
import kotlin.test.assertEquals

class PaidChargesTest {
    private fun session(
        start: Long,
        end: Long,
        kind: ChargerKind? = ChargerKind.DC,
    ) = ChargeSession(start * MINUTE, end * MINUTE, 20f, 80f, 15f, null, vehicleId = "car", chargerKind = kind)

    private val paid = LastPaidCharge(70 * MINUTE, 4.22f, "EUR", 0.28f)
    private val defaults = PriceDefaults(listOf(PriceChange(0, 0.2f)), "PLN")
    private val supercharged =
        StoredChargePrice("car", 60 * MINUTE, 66 * MINUTE, SessionPrice.Total(4.22f), "EUR", byOwner = false)

    @Test
    fun `a paid charge goes to the nearest session that was not AC, within half an hour`() {
        val sessions = listOf(session(0, 30, ChargerKind.AC), session(60, 66), session(300, 330))

        assertEquals(listOf(null, paid, null), matchPaidCharges(sessions, listOf(paid)))
        assertEquals(listOf(null, null, null), matchPaidCharges(sessions, listOf(paid.copy(atMillis = 200 * MINUTE))))
    }

    @Test
    fun `a paid session is frozen at the car's cost, which also fills an empty one, but never the owner's edit`() {
        val sessions = listOf(session(60, 66), session(300, 330))
        assertEquals(listOf(supercharged), reconcilePrices(sessions, emptyList(), defaults, listOf(paid)))

        val empty = StoredChargePrice("car", 60 * MINUTE, 66 * MINUTE, null, "PLN", false)
        assertEquals(listOf(supercharged), reconcilePrices(sessions, listOf(empty), defaults, listOf(paid)))

        val edit = empty.copy(price = SessionPrice.Total(9f), byOwner = true)
        assertEquals(listOf(edit), reconcilePrices(sessions, listOf(edit), defaults, listOf(paid)))
        val emptyEdit = empty.copy(byOwner = true)
        assertEquals(listOf(emptyEdit), reconcilePrices(sessions, listOf(emptyEdit), defaults, listOf(paid)))
    }

    @Test
    fun `the car's cost replaces the home price a session without a DC reading was frozen at`() {
        val sessions = listOf(session(60, 66, kind = null), session(300, 330))
        val atHome = reconcilePrices(sessions, emptyList(), defaults)
        assertEquals(listOf(SessionPrice.PerKwh(0.2f)), atHome.map { it.price })

        assertEquals(listOf(supercharged), reconcilePrices(sessions, atHome, defaults, listOf(paid)))
        val shown = sessionPrices(sessions.take(1), atHome, emptyList(), listOf(paid)).single()
        assertEquals(SessionPrice.Total(4.22f), shown.price)
    }

    @Test
    fun `the screens show the car's cost before a build stores it`() {
        val shown = sessionPrices(listOf(session(60, 66)), emptyList(), emptyList(), listOf(paid)).single()

        assertEquals(ChargeSessionPrice(SessionPrice.Total(4.22f), "EUR", byOwner = false, place = null), shown)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
