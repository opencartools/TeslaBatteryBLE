// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * A stretch of the history the app lists and charts as one thing: a drive or a charge. The chart, the session list
 * and `derived.db` read only this (`proposals/sessions-and-chart.md`); the kinds add their own figures. Cost comes
 * with the pricing work.
 */
interface Session {
    /** `"<kind>-<vehicleId>-<startMillis>"`: a display and selection key only; stored rows match by time overlap. */
    val id: String

    /** The car's clock at the first reading of the session, millis. */
    val startMillis: Long

    /** The car's clock at the last reading of the session, millis. */
    val endMillis: Long

    /** True for the session that is still going; its figures grow until it ends. */
    val inProgress: Boolean

    /** The level at the start and at the end, percent; null when no battery reading lies close enough. */
    val socStartPercent: Float?
    val socEndPercent: Float?

    /** The energy the session moved, kWh: measured for a charge, estimated for a drive; null when unknown. */
    val energyKwh: Float?
    val energyIsEstimate: Boolean
}

/**
 * The stored sessions joined with the live ones: every one of [stored] (oldest first) that starts before the newest
 * stored start, then [live]'s sessions from that start on. The newest stored session may still be growing, and the
 * readings since the last build are not stored yet, so that tail is cut again from the readings in memory: [live]
 * gets the time to read from, [SESSION_CUT_LEAD_MILLIS] before the tail starts, or [Long.MIN_VALUE] with nothing
 * stored yet, when it cuts everything.
 */
fun <S : Session> withLiveTail(
    stored: List<S>,
    live: (readFromMillis: Long) -> List<S>,
): List<S> {
    val tailFrom = stored.lastOrNull()?.startMillis ?: return live(Long.MIN_VALUE)
    return stored.filter { it.startMillis < tailFrom } +
        live(tailFrom - SESSION_CUT_LEAD_MILLIS).filter { it.startMillis >= tailFrom }
}

/**
 * How far around a session the battery readings must reach to cut it the same way again: a drive's end levels come
 * from the parked reading next to each end, and a charge's tail is cut from a little before its start.
 */
const val SESSION_CUT_LEAD_MILLIS = 60 * 60_000L

/** The items of [this] (oldest first by [time]) from [millis] on: a binary search, so the tail costs no scan. */
fun <T> List<T>.fromTime(
    millis: Long,
    time: (T) -> Long,
): List<T> {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) / 2
        if (time(this[middle]) < millis) low = middle + 1 else high = middle
    }
    return subList(low, size)
}
