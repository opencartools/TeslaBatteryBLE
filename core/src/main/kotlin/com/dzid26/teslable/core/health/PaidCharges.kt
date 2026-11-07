// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.protocol.LastPaidCharge
import kotlin.math.max

/**
 * The paid charge each of [sessions] (one car, oldest first) was: each of [paid] goes to the session that is not AC
 * and lies nearest its time, within [PAID_MATCH_MILLIS] (the car's stamp may be the session's start or its end).
 */
fun matchPaidCharges(
    sessions: List<ChargeSession>,
    paid: List<LastPaidCharge>,
): List<LastPaidCharge?> {
    val matched = arrayOfNulls<LastPaidCharge>(sessions.size)
    val gaps = LongArray(sessions.size) { Long.MAX_VALUE }
    paid.forEach { charge ->
        sessions.indices
            .filter { sessions[it].chargerKind != ChargerKind.AC }
            .map { it to gapTo(sessions[it], charge.atMillis) }
            .filter { (_, gap) -> gap <= PAID_MATCH_MILLIS }
            .minByOrNull { (_, gap) -> gap }
            ?.let { (i, gap) ->
                if (gap < gaps[i]) {
                    matched[i] = charge
                    gaps[i] = gap
                }
            }
    }
    return matched.toList()
}

/** The price a paid charge stores: the car's total, which has more resolution than its price per kWh. */
fun LastPaidCharge.price(): SessionPrice = SessionPrice.Total(cost)

private fun gapTo(
    session: ChargeSession,
    atMillis: Long,
): Long = max(0L, max(session.startMillis - atMillis, atMillis - session.endMillis))

/** How far the car's stamp of a paid charge may lie outside the session it belongs to. */
private const val PAID_MATCH_MILLIS = 30 * 60_000L
