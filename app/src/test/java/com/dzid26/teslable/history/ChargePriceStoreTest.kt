// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dzid26.teslable.core.health.ChargePlace
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.PriceChange
import com.dzid26.teslable.core.health.PriceDefaults
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.StoredChargePrice
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.LastPaidCharge
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
    private val prices = MutableStateFlow<List<StoredChargePrice>>(emptyList())
    private val defaults = PriceDefaults(listOf(PriceChange(0, 0.2f)), "PLN")

    @After
    fun close() = db.close()

    private fun session(
        start: Long,
        end: Long,
        car: String = "carA",
    ) = ChargeSession(start, end, 40f, 80f, 20f, null, vehicleId = car)

    @Test
    fun `a build freezes every finished session, and an edit replaces its price and survives a reopen`() {
        val store = ChargePriceStore(db, prices)
        val first = session(HOUR, 3 * HOUR)
        store.follow(listOf(first, session(10 * HOUR, 11 * HOUR)), defaults)
        store.set(first, SessionPrice.Total(7f), "EUR", ChargePlace.SUPERCHARGER)

        ChargePriceStore(db, prices)

        assertEquals(
            listOf(
                StoredChargePrice("carA", HOUR, 3 * HOUR, SessionPrice.Total(7f), "EUR", byOwner = true, ChargePlace.SUPERCHARGER),
            ),
            prices.value,
        )
    }

    @Test
    fun `clearing an edit freezes the automatic price again at the next build`() {
        val store = ChargePriceStore(db, prices)
        val first = session(HOUR, 3 * HOUR)
        val sessions = listOf(first, session(10 * HOUR, 11 * HOUR))
        store.set(first, null, "EUR", null)

        store.clear(first)
        store.follow(sessions, defaults)

        assertEquals(
            listOf(StoredChargePrice("carA", HOUR, 3 * HOUR, SessionPrice.PerKwh(0.2f), "PLN", byOwner = false, ChargePlace.HOME)),
            prices.value,
        )
    }

    @Test
    fun `a re-cut moves stored prices, keeps one with no session and leaves other cars alone`() {
        val store = ChargePriceStore(db, prices)
        store.set(session(HOUR, 3 * HOUR), SessionPrice.PerKwh(0.30f), "EUR", ChargePlace.WORK)
        store.set(session(20 * HOUR, 21 * HOUR), SessionPrice.Total(5f), "EUR", "Gym")
        store.set(session(HOUR, 2 * HOUR, car = "carB"), SessionPrice.Total(9f), "PLN", null)

        store.follow(listOf(session(HOUR + MINUTE, 4 * HOUR), session(10 * HOUR, 11 * HOUR)), defaults)

        assertEquals(
            setOf(
                StoredChargePrice("carA", HOUR + MINUTE, 4 * HOUR, SessionPrice.PerKwh(0.30f), "EUR", byOwner = true, ChargePlace.WORK),
                StoredChargePrice("carA", 20 * HOUR, 21 * HOUR, SessionPrice.Total(5f), "EUR", byOwner = true, "Gym"),
                StoredChargePrice("carB", HOUR, 2 * HOUR, SessionPrice.Total(9f), "PLN", byOwner = true),
            ),
            prices.value.toSet(),
        )
    }

    @Test
    fun `a battery sample keeps the car's paid charge through derived db`() {
        val paid = LastPaidCharge(1_780_411_979_000, 4.22f, "EUR", 0.28f)
        val sample = BatterySample(1_000, 50, ChargingStateKind.Disconnected, 80, "carA", lastPaidCharge = paid)

        assertEquals(sample, sample.toRow().toSample())
        assertEquals(sample.copy(lastPaidCharge = null), sample.copy(lastPaidCharge = null).toRow().toSample())
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
