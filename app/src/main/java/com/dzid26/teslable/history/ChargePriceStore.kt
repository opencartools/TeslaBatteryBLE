// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.PriceDefaults
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.StoredChargePrice
import com.dzid26.teslable.core.health.reconcilePrices
import kotlinx.coroutines.flow.MutableStateFlow

/** `owner.db`, and what a session nobody priced is frozen at: the home price changes and the currency, read at each build. */
internal class OwnerPricing(
    val db: OwnerDatabase,
    val defaults: () -> PriceDefaults,
)

/**
 * Every finished charge session's price in `owner.db`: the automatic one, frozen, or the owner's edit. Blocking;
 * call it off the main thread.
 */
internal class ChargePriceStore(
    db: OwnerDatabase,
    /** Where every car's stored prices are published, by car and then oldest first. */
    private val published: MutableStateFlow<List<StoredChargePrice>>,
) {
    private val dao = db.chargePrices()

    /** What the last [follow] reconciled, so a build that changed neither the sessions nor the defaults skips it. */
    private var followed: Pair<List<ChargeSession>, PriceDefaults>? = null

    init {
        load()
    }

    /** Publishes what is stored. */
    fun load() {
        published.value = dao.all().mapNotNull { it.toStored() }
    }

    /** Sets the owner's [price] for [session] in [currencyCode] at [place]; a null price is an empty one the owner chose. */
    fun set(
        session: ChargeSession,
        price: SessionPrice?,
        currencyCode: String,
        place: String?,
    ) {
        val row =
            StoredChargePrice(session.vehicleId, session.startMillis, session.endMillis, price, currencyCode, byOwner = true, place = place)
        dao.replace(listOfNotNull(storedFor(session)?.toRow()), listOf(row.toRow()))
        followed = null
        load()
    }

    /** Takes [session] back to the automatic price: the next build freezes it again, at the price of its time. */
    fun clear(session: ChargeSession) {
        storedFor(session)?.let { dao.replace(listOf(it.toRow()), emptyList()) }
        followed = null
        load()
    }

    /**
     * Brings the stored prices in step with a build's [sessions] (every car's, by car and oldest first): see
     * [reconcilePrices]. Only what changed is written.
     */
    fun follow(
        sessions: List<ChargeSession>,
        defaults: PriceDefaults,
    ) {
        if (followed == (sessions to defaults)) return
        val byCar = published.value.groupBy { it.vehicleId }
        val before = published.value.toSet()
        val after =
            sessions.groupBy { it.vehicleId }.flatMap { (vehicleId, carSessions) ->
                reconcilePrices(carSessions, byCar[vehicleId].orEmpty(), defaults)
            } + published.value.filter { it.vehicleId !in sessions.mapTo(HashSet()) { s -> s.vehicleId } }
        val afterSet = after.toSet()
        val removed = before.filter { it !in afterSet }
        val written = after.filter { it !in before }
        if (removed.isNotEmpty() || written.isNotEmpty()) {
            dao.replace(removed.map { it.toRow() }, written.map { it.toRow() })
            load()
        }
        followed = sessions to defaults
    }

    /** The price stored at [session]'s exact start, where each build writes it. */
    private fun storedFor(session: ChargeSession): StoredChargePrice? =
        published.value.firstOrNull { it.vehicleId == session.vehicleId && it.startMillis == session.startMillis }
}
