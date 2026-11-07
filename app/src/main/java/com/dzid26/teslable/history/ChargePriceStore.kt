// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.health.ChargePriceOverride
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.matchOverrides
import com.dzid26.teslable.core.health.movedOverrides
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The owner's per-session charge prices in `owner.db`. A session without one takes the automatic price when it is
 * read (`sessionPrice`). Blocking; call it off the main thread.
 */
internal class ChargePriceStore(
    db: OwnerDatabase,
    /** Where every car's overrides are published, by car and then oldest first. */
    private val published: MutableStateFlow<List<ChargePriceOverride>>,
) {
    private val dao = db.chargePrices()

    init {
        load()
    }

    /** Publishes what is stored. */
    fun load() {
        published.value = dao.all().mapNotNull { it.toOverride() }
    }

    /** Sets [price] in [currencyCode] for [session], replacing the one it had. */
    fun set(
        session: ChargeSession,
        price: SessionPrice,
        currencyCode: String?,
    ) {
        overrideOf(session)?.let { dao.delete(it.vehicleId, it.startMillis) }
        dao.put(ChargePriceOverride(session.vehicleId, session.startMillis, session.endMillis, price, currencyCode).toRow())
        load()
    }

    /** Takes [session] back to the automatic price. */
    fun clear(session: ChargeSession) {
        overrideOf(session)?.let { dao.delete(it.vehicleId, it.startMillis) }
        load()
    }

    /**
     * Keeps every override on its session after a re-cut: one whose session moved follows it to the new start and
     * end. [sessions] is every car's stored sessions, by car and oldest first; an override matching none stays.
     */
    fun follow(sessions: List<ChargeSession>) {
        val byCar = published.value.groupBy { it.vehicleId }
        val moves =
            sessions.groupBy { it.vehicleId }.flatMap { (vehicleId, carSessions) ->
                movedOverrides(carSessions, byCar[vehicleId].orEmpty())
            }
        if (moves.isEmpty()) return
        dao.move(moves.map { (old, moved) -> old.toRow() to moved.toRow() })
        load()
    }

    private fun overrideOf(session: ChargeSession): ChargePriceOverride? =
        matchOverrides(listOf(session), published.value.filter { it.vehicleId == session.vehicleId }).single()
}
