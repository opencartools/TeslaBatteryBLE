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
 * readings since the last build are not stored yet, so that tail is cut again from the readings in memory; [live]
 * gets the time the tail starts at and cuts from a little before it. With nothing stored yet, [live] cuts everything.
 */
fun <S : Session> withLiveTail(
    stored: List<S>,
    live: (fromMillis: Long) -> List<S>,
): List<S> {
    val tailFrom = stored.lastOrNull()?.startMillis ?: Long.MIN_VALUE
    return stored.filter { it.startMillis < tailFrom } + live(tailFrom).filter { it.startMillis >= tailFrom }
}

/**
 * How far before a session's start the readings must reach to cut it the same way again: longer than the longest
 * gap that still joins two readings into one session (a charge's ten-minute pause, a drive's thirty-minute hole in
 * gear).
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
