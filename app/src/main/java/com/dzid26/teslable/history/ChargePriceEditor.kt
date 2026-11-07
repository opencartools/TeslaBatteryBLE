// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.history

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.StoredChargePrice
import kotlinx.coroutines.flow.StateFlow

/** What the screens may do with the stored charge prices: read them and set one session's price. */
interface ChargePriceEditor {
    /** Every stored session price, as `owner.db` keeps it. */
    val chargePrices: StateFlow<List<StoredChargePrice>>

    /** Sets the owner's [price] for [session] in [currencyCode] (ISO 4217) at [place]; a null price is an empty one. */
    fun setChargePrice(
        session: ChargeSession,
        price: SessionPrice?,
        currencyCode: String,
        place: String?,
    )
}

/** This store's prices, as the screens may use them. */
fun HistoryStore.chargePriceEditor(): ChargePriceEditor {
    val store = this
    return object : ChargePriceEditor {
        override val chargePrices = store.chargePrices

        override fun setChargePrice(
            session: ChargeSession,
            price: SessionPrice?,
            currencyCode: String,
            place: String?,
        ) = store.setChargePrice(session, price, currencyCode, place)
    }
}
