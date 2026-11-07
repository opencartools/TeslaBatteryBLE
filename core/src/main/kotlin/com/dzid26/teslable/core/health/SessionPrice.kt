// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import kotlin.math.abs

/** How the car charged: through its own charger from AC, or from a DC fast charger. */
enum class ChargerKind { AC, DC }

/** What the owner pays for one charge session. */
sealed interface SessionPrice {
    /** A price per kWh, times the energy the car added. */
    data class PerKwh(
        val pricePerKwh: Float,
    ) : SessionPrice

    /** A total for the whole session, as a public charger bills it. */
    data class Total(
        val amount: Float,
    ) : SessionPrice
}

/** What a session priced at [this] cost; null when that needs the energy and the car reported none. */
fun SessionPrice.cost(energyAddedKwh: Float?): Float? =
    when (this) {
        is SessionPrice.PerKwh -> chargeSessionCost(energyAddedKwh, pricePerKwh)
        is SessionPrice.Total -> amount.takeIf { it.isFinite() && it >= 0f }
    }

/**
 * One charge session's price as `owner.db` keeps it, so a finished session's cost never changes after the fact:
 * the automatic price frozen when the session was finished, or the owner's edit ([byOwner]). [price] null is an
 * empty price, kept as such (a charge away from home or work that the owner did not price). [startMillis] and
 * [endMillis] are the session's as last cut: a stored price belongs to the session it overlaps, not to an exact
 * start, since a re-cut can move a session's start by a reading.
 */
data class StoredChargePrice(
    val vehicleId: String,
    val startMillis: Long,
    val endMillis: Long,
    val price: SessionPrice?,
    /** The ISO 4217 code, resolved when stored: a backup restored on a phone of another locale keeps it. */
    val currencyCode: String,
    val byOwner: Boolean,
    /** Where the car charged: one of [ChargePlace] or the owner's own name for it; null when not known. */
    val place: String? = null,
)

/** The places a session's price comes from; the owner can also name a place of their own. */
object ChargePlace {
    const val HOME = "Home"
    const val WORK = "Work"
    const val SUPERCHARGER = "Supercharger"
}

/** What a new session is priced at when nobody priced it: the home price changes, and the currency to store. */
data class PriceDefaults(
    val homeChanges: List<PriceChange>,
    val currencyCode: String,
)

/** A session's price for the screens, in [currencyCode]; null currency is the one chosen in settings. */
data class ChargeSessionPrice(
    val price: SessionPrice?,
    val currencyCode: String?,
    val byOwner: Boolean,
    val place: String?,
)

/**
 * Which of [stored] belongs to each of [sessions] (one car, both oldest first). A pair matches when they overlap,
 * their ends stretched by [MATCH_SLACK_MILLIS] so a session of a single reading still finds its own. The best pairs
 * are taken first, across all sessions: the longest overlap, then the nearest start. Each stored price goes to one
 * session at most.
 */
fun matchStoredPrices(
    sessions: List<ChargeSession>,
    stored: List<StoredChargePrice>,
): List<StoredChargePrice?> {
    val pairs = sessions.indices.flatMap { s -> stored.indices.filter { overlaps(sessions[s], stored[it]) }.map { s to it } }
    val matched = arrayOfNulls<StoredChargePrice>(sessions.size)
    val taken = BooleanArray(stored.size)
    pairs
        .sortedWith(
            compareByDescending<Pair<Int, Int>> { (s, o) -> overlap(sessions[s], stored[o]) }
                .thenBy { (s, o) -> abs(stored[o].startMillis - sessions[s].startMillis) },
        ).forEach { (s, o) ->
            if (matched[s] == null && !taken[o]) {
                matched[s] = stored[o]
                taken[o] = true
            }
        }
    return matched.toList()
}

/**
 * The automatic price of [session]: [homePricePerKwh], the home price in effect when it ran (`pricesForSessions`).
 * There is no location yet, so every charge is taken as home except a DC one, since a fast charger is never at home:
 * that stays empty until the car's own Supercharger cost is read.
 */
fun automaticPrice(
    session: ChargeSession,
    homePricePerKwh: Float?,
): SessionPrice? =
    homePricePerKwh
        ?.takeIf { session.chargerKind != ChargerKind.DC && it.isFinite() && it > 0f }
        ?.let { SessionPrice.PerKwh(it) }

/**
 * The price each of [sessions] (one car, oldest first) shows: its stored price, even an empty one, else (the
 * newest session, until a build stores it) the automatic price in the settings currency. The one entry point the
 * screens read, so a charge shows the same price everywhere.
 */
fun sessionPrices(
    sessions: List<ChargeSession>,
    stored: List<StoredChargePrice>,
    homeChanges: List<PriceChange>,
): List<ChargeSessionPrice> {
    val matched = matchStoredPrices(sessions, stored)
    val home = pricesForSessions(homeChanges, sessions)
    return sessions.indices.map { i ->
        matched[i]?.let { ChargeSessionPrice(it.price, it.currencyCode, it.byOwner, it.place) }
            ?: ChargeSessionPrice(automaticPrice(sessions[i], home[i]), null, byOwner = false, place = automaticPlace(sessions[i]))
    }
}

internal fun overlaps(
    session: ChargeSession,
    row: StoredChargePrice,
): Boolean = row.startMillis <= session.endMillis + MATCH_SLACK_MILLIS && row.endMillis >= session.startMillis - MATCH_SLACK_MILLIS

internal fun overlap(
    session: ChargeSession,
    row: StoredChargePrice,
): Long = minOf(row.endMillis, session.endMillis) - maxOf(row.startMillis, session.startMillis)

/** How far apart a stored price and a session may lie and still be the same charge: a few of the history's charging reads. */
internal const val MATCH_SLACK_MILLIS = 5 * 60_000L
