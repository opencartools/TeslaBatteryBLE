// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.vehicle.ChargeState

/**
 * The car's summary of its last paid charge (a Supercharger session), as the car shows it with its invoice. It stays
 * the same in every reply until the next paid charge. The car sends no energy or duration for it.
 */
data class LastPaidCharge(
    /** When the car stamped the session, millis. */
    val atMillis: Long,
    /** What the session cost, in [currencyCode]. */
    val cost: Float,
    /** The ISO 4217 code, such as "EUR". */
    val currencyCode: String,
    /** The price per kWh the session was billed at; null when the car sent none. */
    val pricePerKwh: Float?,
)

/**
 * The last paid charge, from `ChargeState` fields 183 (cost), 184 (currency), 185 (price per kWh) and 190 (time).
 * Null unless the cost, currency and time are all there.
 */
val ChargeState.lastPaidCharge: LastPaidCharge?
    get() {
        val at = last_charge_timestamp ?: return null
        val cost = last_charge_cost ?: return null
        val currency = last_charge_currency ?: return null
        return LastPaidCharge(at.toEpochMilli(), cost, currency, last_charge_price_per_kwh)
    }
