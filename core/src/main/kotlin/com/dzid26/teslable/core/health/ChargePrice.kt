// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample

private const val EDIT_WINDOW_MILLIS = 5 * 60_000L
private const val MAX_CHANGES = 50

/**
 * [changes] plus [change]. Typing a price changes it with every key, so the earlier changes of that edit,
 * those within five minutes before [change], are replaced rather than kept. Only the newest fifty stay.
 */
fun recordPriceChange(
    changes: List<PriceChange>,
    change: PriceChange,
): List<PriceChange> =
    (changes.filter { it.atMillis < change.atMillis - EDIT_WINDOW_MILLIS } + change)
        .sortedBy { it.atMillis }
        .takeLast(MAX_CHANGES)

/**
 * The price a charge that ended at [chargeEndMillis] was billed at. A price change takes effect from the
 * start of the latest charge when it was made: the current one while charging, else the last finished one.
 * So a price set mid-charge covers that whole charge, and one set while parked also re-prices the charge
 * just finished. The newest change in effect by the charge's start wins; null when none is, or it cleared
 * the price.
 */
fun priceForCharge(
    changes: List<PriceChange>,
    samples: List<BatterySample>,
    chargeEndMillis: Long,
): Float? {
    val chargeStart = chargeStartOf(samples, chargeEndMillis)
    return changes
        .filter { change -> (chargeStartsBy(samples, change.atMillis).lastOrNull() ?: Long.MIN_VALUE) <= chargeStart }
        .maxByOrNull { it.atMillis }
        ?.pricePerKwh
}

/**
 * The price each of [sessions] (oldest first) was billed at, by the rule of [priceForCharge]: a change takes
 * effect from the start of the latest session that had begun when it was made. Reads the session starts
 * instead of re-cutting the samples, so it also prices the stored sessions older than the readings in memory.
 */
fun pricesForSessions(
    changes: List<PriceChange>,
    sessions: List<ChargeSession>,
): List<Float?> {
    val starts = sessions.map { it.startMillis }
    val effectiveFrom = changes.map { change -> starts.lastOrNull { it <= change.atMillis } ?: Long.MIN_VALUE }
    return sessions.map { session ->
        changes.indices
            .filter { effectiveFrom[it] <= session.startMillis }
            .maxByOrNull { changes[it].atMillis }
            ?.let { changes[it].pricePerKwh }
    }
}
