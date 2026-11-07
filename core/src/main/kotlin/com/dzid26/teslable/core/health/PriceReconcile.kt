// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

/**
 * The stored prices of one car after a build re-cut its [sessions] (oldest first, as cut), from what was [stored].
 * Every row comes out at its session's exact start and end, the key the screens read it by (`sessionPrices`); an
 * owner's edit left over from a join keeps its own, which no session has:
 * - every session but the newest gets a stored price; the newest is priced live until the next charge starts, so a
 *   price set while parked still covers the charge just finished;
 * - an automatic price stays frozen when its session only moved; one whose session was joined with or split from
 *   others is frozen again by the price rule, at the session's own time, since it holds no owner input;
 * - the owner's edit follows its session: when a re-cut joins sessions, their totals in one currency add up, else
 *   the longest overlap wins and the other edits stay stored, unmatched; when it splits one, a price per kWh goes to
 *   every part and a total is shared by energy;
 * - a stored price that overlaps no session is kept (on a new phone the sessions come only once the logs are back).
 */
fun reconcilePrices(
    sessions: List<ChargeSession>,
    stored: List<StoredChargePrice>,
    defaults: PriceDefaults,
): List<StoredChargePrice> {
    val (owner, automatic) = stored.partition { it.byOwner }
    val ownerTargets = owner.map { price -> targetsOf(sessions, price) }
    val ownerParts = Array(sessions.size) { mutableListOf<Int>() }
    ownerTargets.forEachIndexed { row, targets -> targets.forEach { ownerParts[it] += row } }
    val used = BooleanArray(owner.size)
    val automaticTargets = automatic.map { price -> targetsOf(sessions, price) }
    val automaticParts = Array(sessions.size) { mutableListOf<Int>() }
    automaticTargets.forEachIndexed { row, targets -> targets.forEach { automaticParts[it] += row } }
    val home = pricesForSessions(defaults.homeChanges, sessions)
    val priced =
        sessions.indices.mapNotNull { i ->
            val session = sessions[i]
            val parts = ownerParts[i].map { Part(it, owner[it], share(sessions, ownerTargets[it], i), overlap(session, owner[it])) }
            // A session that only moved keeps its frozen price; one joined or split from others is frozen again.
            val moved = automaticParts[i].singleOrNull()?.takeIf { automaticTargets[it].size == 1 }?.let { automatic[it] }
            when {
                parts.isNotEmpty() -> joined(parts, used)
                moved != null -> moved
                else -> frozen(session, home[i], defaults).takeIf { i != sessions.lastIndex }
            }?.copy(startMillis = session.startMillis, endMillis = session.endMillis)
        }
    val keptAutomatic = automatic.filterIndexed { row, _ -> automaticTargets[row].isEmpty() }
    return priced + owner.filterIndexed { row, _ -> !used[row] } + keptAutomatic
}

/**
 * The sessions (by index) a stored price falls into: those it overlaps, several after a re-cut split one, else the
 * one it nearly touches within [MATCH_SLACK_MILLIS], a session that moved by a reading. The slack never adds a
 * neighbour. Sessions do not overlap, so their ends are as sorted as their starts: a binary search finds the first.
 */
private fun targetsOf(
    sessions: List<ChargeSession>,
    row: StoredChargePrice,
): List<Int> {
    val first = sessions.binarySearch { if (it.endMillis < row.startMillis) -1 else 1 }.let { -(it + 1) }
    val overlapping = (first until sessions.size).takeWhile { sessions[it].startMillis <= row.endMillis }
    return overlapping.ifEmpty {
        listOf(first - 1, first)
            .filter { it in sessions.indices && overlaps(sessions[it], row) }
            .maxByOrNull { overlap(sessions[it], row) }
            .let(::listOfNotNull)
    }
}

/** [session]'s automatic price, frozen: an empty one means unpriced, which a later source (the car's cost) may fill. */
private fun frozen(
    session: ChargeSession,
    homePricePerKwh: Float?,
    defaults: PriceDefaults,
) = StoredChargePrice(
    session.vehicleId,
    session.startMillis,
    session.endMillis,
    automaticPrice(session, homePricePerKwh),
    defaults.currencyCode,
    byOwner = false,
    place = automaticPlace(session),
)

/** Where [session] charged, as far as the app can tell without the car's location: home, unless it was DC. */
fun automaticPlace(session: ChargeSession): String? = ChargePlace.HOME.takeIf { session.chargerKind != ChargerKind.DC }

/** The owner's edit [index] as part of one session: its [share] of a total, and how long it overlaps the session. */
private class Part(
    val index: Int,
    val row: StoredChargePrice,
    val share: Float,
    val overlap: Long,
)

/**
 * The owner's edits that fell into one session as one, marking each it takes in [used]: totals in one currency add
 * up; otherwise the longest overlap wins (a total of it shared, if it spans more sessions) and the rest stay unused.
 */
private fun joined(
    parts: List<Part>,
    used: BooleanArray,
): StoredChargePrice {
    val main = parts.maxBy { it.overlap }
    val summable = parts.all { it.row.price is SessionPrice.Total && it.row.currencyCode == main.row.currencyCode }
    val taken = if (summable) parts else listOf(main)
    taken.forEach { used[it.index] = true }
    val price =
        if (summable) {
            SessionPrice.Total(parts.sumOf { ((it.row.price as SessionPrice.Total).amount * it.share).toDouble() }.toFloat())
        } else {
            (main.row.price as? SessionPrice.Total)?.let { SessionPrice.Total(it.amount * main.share) } ?: main.row.price
        }
    return main.row.copy(price = price)
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
