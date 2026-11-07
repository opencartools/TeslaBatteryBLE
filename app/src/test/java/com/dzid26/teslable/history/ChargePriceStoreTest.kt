// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dzid26.teslable.core.health.ChargePriceOverride
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.SessionPrice
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChargePriceStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val db by lazy { OwnerDatabase.inMemory(TestContext(folder.root), onMainThread = true, driver = BundledSQLiteDriver()) }
    private val overrides = MutableStateFlow<List<ChargePriceOverride>>(emptyList())

    @After
    fun close() = db.close()

    private fun session(
        start: Long,
        end: Long,
        car: String = "carA",
    ) = ChargeSession(start, end, 40f, 80f, 20f, null, vehicleId = car)

    @Test
    fun `an edited price replaces the session's earlier one and survives a reopen`() {
        val store = ChargePriceStore(db, overrides)
        val charge = session(HOUR, 3 * HOUR)
        store.set(charge, SessionPrice.PerKwh(0.30f), "EUR")
        store.set(charge.copy(startMillis = HOUR + MINUTE), SessionPrice.Total(7f), "PLN")

        ChargePriceStore(db, overrides)

        assertEquals(
            listOf(ChargePriceOverride("carA", HOUR + MINUTE, 3 * HOUR, SessionPrice.Total(7f), "PLN")),
            overrides.value,
        )
    }

    @Test
    fun `clearing takes the session back to the automatic price`() {
        val store = ChargePriceStore(db, overrides)
        val charge = session(HOUR, 3 * HOUR)
        store.set(charge, SessionPrice.PerKwh(0.30f), null)

        store.clear(charge)

        assertEquals(emptyList<ChargePriceOverride>(), overrides.value)
    }

    @Test
    fun `a re-cut session keeps its price and an override with no session stays`() {
        val store = ChargePriceStore(db, overrides)
        store.set(session(HOUR, 3 * HOUR), SessionPrice.PerKwh(0.30f), "EUR")
        store.set(session(10 * HOUR, 11 * HOUR), SessionPrice.Total(5f), "EUR")
        store.set(session(HOUR, 2 * HOUR, car = "carB"), SessionPrice.Total(9f), null)

        store.follow(listOf(session(HOUR + MINUTE, 4 * HOUR), session(HOUR, 2 * HOUR, car = "carB")))

        assertEquals(
            setOf(
                ChargePriceOverride("carA", HOUR + MINUTE, 4 * HOUR, SessionPrice.PerKwh(0.30f), "EUR"),
                ChargePriceOverride("carA", 10 * HOUR, 11 * HOUR, SessionPrice.Total(5f), "EUR"),
                ChargePriceOverride("carB", HOUR, 2 * HOUR, SessionPrice.Total(9f), null),
            ),
            overrides.value.toSet(),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
