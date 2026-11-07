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
 * The price the owner set for one charge session, as `owner.db` keeps it: the only part of a session the owner
 * edits, with the currency it is in. [startMillis] and [endMillis] are the session's as last cut: an override
 * belongs to the stored session it overlaps, not to an exact start, so a re-cut that moves a session by a reading
 * keeps its price. A session without one takes the automatic price, worked out when it is read.
 */
data class ChargePriceOverride(
    val vehicleId: String,
    val startMillis: Long,
    val endMillis: Long,
    val price: SessionPrice,
    val currencyCode: String?,
)

/**
 * Which of [overrides] belongs to each of [sessions] (one car, both oldest first): the overrides a session
 * overlaps, its ends stretched by [MATCH_SLACK_MILLIS] so a session of a single reading still finds its own,
 * preferring the longest overlap, then the nearest start. Each override goes to one session at most; one that
 * matches none is kept for the owner, never dropped.
 */
fun matchOverrides(
    sessions: List<ChargeSession>,
    overrides: List<ChargePriceOverride>,
): List<ChargePriceOverride?> {
    val free = overrides.sortedBy { it.startMillis }.toMutableList()
    return sessions.map { session ->
        free
            .filter { it.startMillis <= session.endMillis + MATCH_SLACK_MILLIS && it.endMillis >= session.startMillis - MATCH_SLACK_MILLIS }
            .maxWithOrNull(
                compareBy<ChargePriceOverride>(
                    { minOf(it.endMillis, session.endMillis) - maxOf(it.startMillis, session.startMillis) },
                    { -abs(it.startMillis - session.startMillis) },
                ),
            )?.also { free.remove(it) }
    }
}

/**
 * The overrides of one car whose session moved in a re-cut, at the session's new start and end: the old ones to
 * replace and their moved copies. Matching tolerates a shift, but keeping the times in step stops shifts adding up.
 */
fun movedOverrides(
    sessions: List<ChargeSession>,
    overrides: List<ChargePriceOverride>,
): List<Pair<ChargePriceOverride, ChargePriceOverride>> =
    sessions.zip(matchOverrides(sessions, overrides)).mapNotNull { (session, override) ->
        override
            ?.takeIf { it.startMillis != session.startMillis || it.endMillis != session.endMillis }
            ?.let { it to it.copy(startMillis = session.startMillis, endMillis = session.endMillis) }
    }

/**
 * The price a session is billed at: the owner's [override] when there is one, else the automatic price. For now
 * that is the home price, [homePricePerKwh], for a charge that was not DC; a DC session has no automatic price until
 * the car's own fast-charging cost is read, and place defaults follow once the car's location is.
 */
fun sessionPrice(
    session: ChargeSession,
    override: ChargePriceOverride?,
    homePricePerKwh: Float?,
): SessionPrice? =
    override?.price
        ?: homePricePerKwh
            ?.takeIf { session.chargerKind != ChargerKind.DC && it.isFinite() && it > 0f }
            ?.let { SessionPrice.PerKwh(it) }

/** How far apart an override and a session may lie and still be the same charge: a few of the history's charging reads. */
private const val MATCH_SLACK_MILLIS = 5 * 60_000L
