// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.protocol.LastPaidCharge

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
    /**
     * Null is empty. An empty automatic price means unpriced (a DC charge, or none set yet), which a later source such
     * as the car's own Supercharger cost may fill; an empty edit is the owner's choice and is never filled.
     */
    val price: SessionPrice?,
    /** The ISO 4217 code, resolved when stored: a backup restored on a phone of another locale keeps it. */
    val currencyCode: String,
    val byOwner: Boolean,
    /**
     * Where the car charged: one of [ChargePlace] or the owner's own name for it; null when not known. A name the owner
     * types that equals a [ChargePlace] means that place.
     */
    val place: String? = null,
)

/** The places a session's price comes from; the owner can also name a place of their own. */
object ChargePlace {
    const val HOME = "Home"
    const val WORK = "Work"
    const val SUPERCHARGER = "Supercharger"
}

/**
 * What a new session is priced at when nobody priced it: the home price changes, the currency to store, and every
 * car's paid charges as it reported them (`paidCharges`), by car.
 */
data class PriceDefaults(
    val homeChanges: List<PriceChange>,
    val currencyCode: String,
    val paidCharges: Map<String, List<LastPaidCharge>> = emptyMap(),
)

/** A session's price for the screens, in [currencyCode]; null currency is the one chosen in settings. */
data class ChargeSessionPrice(
    val price: SessionPrice?,
    val currencyCode: String?,
    val byOwner: Boolean,
    val place: String?,
)

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
 * The price each of [sessions] (one car, oldest first) shows: its stored price, even an empty one, read by the exact
 * start and end each build writes it at (`reconcilePrices`), else (the newest session, until a build stores it) the
 * automatic price in the settings currency. A session the car reports as a [paid] charge shows the car's cost until
 * a build stores it, unless the owner priced it. The one entry point the screens read, so a charge shows the same
 * price everywhere.
 */
fun sessionPrices(
    sessions: List<ChargeSession>,
    stored: List<StoredChargePrice>,
    homeChanges: List<PriceChange>,
    paid: List<LastPaidCharge> = emptyList(),
): List<ChargeSessionPrice> {
    val byShape = stored.associateBy { Triple(it.vehicleId, it.startMillis, it.endMillis) }
    val home = pricesForSessions(homeChanges, sessions)
    val paidFor = matchPaidCharges(sessions, paid)
    return sessions.indices.map { i ->
        val session = sessions[i]
        val row = byShape[Triple(session.vehicleId, session.startMillis, session.endMillis)]
        val unpriced = row == null || (row.price == null && !row.byOwner)
        val shown = paidFor[i]?.takeIf { unpriced }?.let { paidPrice(session, it) } ?: row
        shown?.let { ChargeSessionPrice(it.price, it.currencyCode, it.byOwner, it.place) }
            ?: ChargeSessionPrice(automaticPrice(session, home[i]), null, byOwner = false, place = automaticPlace(session))
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
