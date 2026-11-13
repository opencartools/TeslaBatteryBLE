// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable

import android.content.Context
import com.dzid26.teslable.core.health.PriceChange
import com.dzid26.teslable.core.health.recordPriceChange

/** The owner's home and work electricity prices per kWh and the currency they are in; used to price charge sessions. */
class EnergyPriceStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every home price the owner set, oldest first: a charge is priced by the change in effect when it ended. */
    fun priceChanges(): List<PriceChange> = changes(KEY_CHANGES)

    /** The current home price per kWh, or null while none is set. */
    fun pricePerKwh(): Float? = priceChanges().lastOrNull()?.pricePerKwh

    /** Saves [price] as a new home price change made now; null or a non-positive value clears it. */
    fun setPricePerKwh(price: Float?) = record(KEY_CHANGES, price)

    /**
     * Every work price the owner set, oldest first, kept like the home price. Nothing prices a charge by it until
     * the app can tell a charge happened at work.
     */
    fun workPriceChanges(): List<PriceChange> = changes(KEY_WORK_CHANGES)

    /** The current work price per kWh, or null while none is set. */
    fun workPricePerKwh(): Float? = workPriceChanges().lastOrNull()?.pricePerKwh

    /** Saves [price] as a new work price change made now; null or a non-positive value clears it. */
    fun setWorkPricePerKwh(price: Float?) = record(KEY_WORK_CHANGES, price)

    private fun changes(key: String): List<PriceChange> =
        prefs
            .getString(key, null)
            .orEmpty()
            .split(SEPARATOR)
            .mapNotNull { entry ->
                val (at, price) = entry.split(PAIR, limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                at.toLongOrNull()?.let { PriceChange(it, price.toFloatOrNull()) }
            }

    private fun record(
        key: String,
        price: Float?,
    ) {
        val valid = price?.takeIf { it.isFinite() && it > 0f }
        val changes = recordPriceChange(changes(key), PriceChange(System.currentTimeMillis(), valid))
        prefs
            .edit()
            .putString(key, changes.joinToString(SEPARATOR) { "${it.atMillis}$PAIR${it.pricePerKwh ?: ""}" })
            .apply()
    }

    /** The ISO 4217 code the owner chose, or null to follow the phone's locale. */
    fun currencyCode(): String? = prefs.getString(KEY_CURRENCY, null)

    /** Saves [code]; null clears it back to the locale's currency. */
    fun setCurrencyCode(code: String?) {
        prefs.edit().apply { if (code != null) putString(KEY_CURRENCY, code) else remove(KEY_CURRENCY) }.apply()
    }

    private companion object {
        const val PREFS = "teslable_energy_price"
        const val KEY_CHANGES = "price_changes"
        const val KEY_WORK_CHANGES = "work_price_changes"
        const val SEPARATOR = ","
        const val PAIR = ":"
        const val KEY_CURRENCY = "currency_code"
    }
}
