// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample

private const val EDIT_WINDOW_MILLIS = 5 * 60_000L

/**
 * [changes] plus [change]. Typing a price changes it with every key, so the earlier changes of that edit,
 * those within five minutes before [change], are replaced rather than kept. Every other change stays: a re-cut
 * session is priced again by the change of its own time, however old.
 */
fun recordPriceChange(
    changes: List<PriceChange>,
    change: PriceChange,
): List<PriceChange> =
    (changes.filter { it.atMillis < change.atMillis - EDIT_WINDOW_MILLIS } + change)
        .sortedBy { it.atMillis }

/**
 * The price a charge that ended at [chargeEndMillis] (the time of its last charging sample) was billed at: its
 * entry from [pricesForSessions] over the sessions cut from [samples]. Null when no charge ends there, or no
 * price is in effect for it.
 */
fun priceForCharge(
    changes: List<PriceChange>,
    samples: List<BatterySample>,
    chargeEndMillis: Long,
): Float? {
    val sessions = chargeSessions(samples)
    val index = sessions.indexOfFirst { it.endMillis == chargeEndMillis }
    return if (index >= 0) pricesForSessions(changes, sessions)[index] else null
}

/**
 * The price each of [sessions] (oldest first) was billed at. A price change takes effect from the start of the
 * latest charge when it was made: the current one while charging, else the last finished one. So a price set
 * mid-charge covers that whole charge, and one set while parked also re-prices the charge just finished. The
 * newest change in effect by a session's start wins; null when none is, or it cleared the price. Reads only
 * the session starts, so it also prices the stored sessions older than the readings in memory.
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
