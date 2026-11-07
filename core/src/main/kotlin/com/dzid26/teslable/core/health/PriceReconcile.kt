// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

/**
 * The stored prices of one car after a build re-cut its [sessions] (oldest first), from what was [stored]:
 * - every session but the newest gets a stored price; the newest is priced live until the next charge starts, so a
 *   price set while parked still covers the charge just finished;
 * - a stored price follows its session to the new start and end;
 * - when a re-cut joins sessions, the owner's edits win over automatic prices, and totals add up;
 * - when it splits one, a price per kWh goes to every part and a total is shared by energy;
 * - a session with no stored price gets the automatic one, frozen;
 * - a stored price that overlaps no session is kept, never dropped.
 */
fun reconcilePrices(
    sessions: List<ChargeSession>,
    stored: List<StoredChargePrice>,
    defaults: PriceDefaults,
): List<StoredChargePrice> {
    val targets = stored.map { row -> targetsOf(sessions, row) }
    val home = pricesForSessions(defaults.homeChanges, sessions)
    val priced =
        sessions.indices.mapNotNull { i ->
            val parts =
                stored.indices
                    .filter {
                        i in targets[it]
                    }.map { Part(stored[it], share(sessions, targets[it], i), overlap(sessions[i], stored[it])) }
            val session = sessions[i]
            when {
                parts.isNotEmpty() -> joined(parts).copy(startMillis = session.startMillis, endMillis = session.endMillis)
                i == sessions.lastIndex -> null
                else -> frozen(session, automaticPrice(session, home[i]), defaults.currencyCode)
            }
        }
    return priced + stored.filterIndexed { index, _ -> targets[index].isEmpty() }
}

/**
 * The sessions a stored price falls into: those it overlaps (several after a re-cut split one), else the one it
 * nearly touches within [MATCH_SLACK_MILLIS], a session that moved by a reading. The slack never adds a neighbour.
 */
private fun targetsOf(
    sessions: List<ChargeSession>,
    row: StoredChargePrice,
): List<Int> =
    sessions.indices.filter { overlap(sessions[it], row) >= 0 }.ifEmpty {
        listOfNotNull(sessions.indices.filter { overlaps(sessions[it], row) }.maxByOrNull { overlap(sessions[it], row) })
    }

/** [session]'s automatic [price], frozen. */
private fun frozen(
    session: ChargeSession,
    price: SessionPrice?,
    currencyCode: String,
) = StoredChargePrice(session.vehicleId, session.startMillis, session.endMillis, price, currencyCode, false, automaticPlace(session))

/** Where [session] charged, as far as the app can tell without the car's location: home, unless it was DC. */
fun automaticPlace(session: ChargeSession): String? = ChargePlace.HOME.takeIf { session.chargerKind != ChargerKind.DC }

/** A stored price's part in one session: its [share] of a total, and how long it overlaps the session. */
private class Part(
    val row: StoredChargePrice,
    val share: Float,
    val overlap: Long,
)

/** The stored prices that fell into one session as one: the owner's edits first, totals added, else the longest. */
private fun joined(parts: List<Part>): StoredChargePrice {
    val chosen = parts.filter { it.row.byOwner }.ifEmpty { parts }
    val main = chosen.maxBy { it.overlap }.row
    val totals = chosen.map { it.row.price }
    val sum =
        if (totals.all { it is SessionPrice.Total } && chosen.all { it.row.currencyCode == main.currencyCode }) {
            SessionPrice.Total(chosen.sumOf { ((it.row.price as SessionPrice.Total).amount * it.share).toDouble() }.toFloat())
        } else {
            (main.price as? SessionPrice.Total)?.let { SessionPrice.Total(it.amount * chosen.first { p -> p.row == main }.share) }
                ?: main.price
        }
    return main.copy(price = sum)
}

/** Session [i]'s share of a stored price that spans [targets]: its part of their energy, else of their time. */
private fun share(
    sessions: List<ChargeSession>,
    targets: List<Int>,
    i: Int,
): Float {
    val energy = { at: Int -> sessions[at].energyAddedKwh ?: 0f }
    val duration = { at: Int -> (sessions[at].endMillis - sessions[at].startMillis).coerceAtLeast(1).toFloat() }
    val weight = if (targets.sumOf { energy(it).toDouble() } > 0.0) energy else duration
    return weight(i) / targets.sumOf { weight(it).toDouble() }.toFloat()
}
