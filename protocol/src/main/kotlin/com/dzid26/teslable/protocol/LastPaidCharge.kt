// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.tesla.generated.carserver.vehicle.ChargeState
import okio.Buffer

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
 * The last paid charge, from `ChargeState` fields that the pinned protos do not define yet (183 cost, 184 currency,
 * 185 price per kWh, 190 timestamp), with the names and types of opencartools/vehicle-command#1. They are read from the
 * reply's unknown fields so the vendored protos stay verbatim; once upstream defines them, a `TESLA_COMMIT` bump
 * turns this into plain field reads. Null unless the cost, currency and time are all there.
 */
val ChargeState.lastPaidCharge: LastPaidCharge?
    get() {
        if (unknownFields.size == 0) return null
        var cost: Float? = null
        var currency: String? = null
        var price: Float? = null
        var atMillis: Long? = null
        runCatching {
            val reader = ProtoReader(Buffer().write(unknownFields))
            val token = reader.beginMessage()
            while (true) {
                when (reader.nextTag()) {
                    -1 -> break
                    LAST_CHARGE_COST -> cost = ProtoAdapter.FLOAT.decode(reader)
                    LAST_CHARGE_CURRENCY -> currency = ProtoAdapter.STRING.decode(reader)
                    LAST_CHARGE_PRICE_PER_KWH -> price = ProtoAdapter.FLOAT.decode(reader)
                    LAST_CHARGE_TIMESTAMP -> atMillis = ProtoAdapter.INSTANT.decode(reader).toEpochMilli()
                    else -> reader.skip()
                }
            }
            reader.endMessageAndGetUnknownFields(token)
        }
        val at = atMillis ?: return null
        return cost?.let { c -> currency?.let { LastPaidCharge(at, c, it, price) } }
    }

private const val LAST_CHARGE_COST = 183
private const val LAST_CHARGE_CURRENCY = 184
private const val LAST_CHARGE_PRICE_PER_KWH = 185
private const val LAST_CHARGE_TIMESTAMP = 190
