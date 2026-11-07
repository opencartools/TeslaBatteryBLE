// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/*
 * How the session list and the history chart point at each other (`proposals/sessions-and-chart.md`): a session
 * picked in the list gives the chart a window around it, and a tap on the chart finds the session under the finger.
 */

/**
 * The stretch of time the chart shows for [session]: the session with a margin each side, a [FOCUS_MARGIN] of its
 * length and at least [MIN_FOCUS_MARGIN_MILLIS], so the levels before and after it are in view too.
 */
fun focusWindow(session: Session): LongRange {
    val span = (session.endMillis - session.startMillis).coerceAtLeast(0L)
    val margin = maxOf((span * FOCUS_MARGIN).toLong(), MIN_FOCUS_MARGIN_MILLIS)
    return (session.startMillis - margin)..(session.endMillis + margin)
}

/**
 * The readings that place this drive on the distance axis: the drive with up to just under [DRIVE_JOIN_MILLIS] each
 * side, for the parked readings at its ends. The drive before or after it is at least that far away, so none of its
 * readings in gear come along.
 */
fun DriveSession.distanceFocus(): LongRange = (startMillis - DRIVE_JOIN_MILLIS + 1)..(endMillis + DRIVE_JOIN_MILLIS - 1)

/** The items of [this] (oldest first by [time]) within [span]: two binary searches' worth, no scan of the rest. */
fun <T> List<T>.inSpan(
    span: LongRange,
    time: (T) -> Long,
): List<T> {
    val tail = fromTime(span.first, time)
    val end = tail.fromTime(span.last + 1, time)
    return tail.subList(0, tail.size - end.size)
}

/**
 * The session at [millis] among these: the one whose span holds it, else the nearest one whose span ends within
 * [toleranceMillis] of it (a short drive is a sliver on a day's chart, too narrow to hit exactly); null for none.
 */
fun <S : Session> List<S>.sessionAt(
    millis: Long,
    toleranceMillis: Long,
): S? {
    firstOrNull { millis in it.startMillis..it.endMillis }?.let { return it }
    return map { it to distance(it, millis) }
        .filter { (_, distance) -> distance <= toleranceMillis }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

private fun distance(
    session: Session,
    millis: Long,
): Long = if (millis < session.startMillis) session.startMillis - millis else millis - session.endMillis

/**
 * The navigation the car ran during this drive: of [trips], the one that overlaps it longest, allowing
 * [NAVIGATION_SLACK_MILLIS] at each end (a route is often set a moment before setting off, and readings are sparse).
 * Both sides are on the car's clock. Null when no trip overlaps it.
 */
fun DriveSession.navigation(trips: List<Trip>): Trip? {
    val from = startMillis - NAVIGATION_SLACK_MILLIS
    val to = endMillis + NAVIGATION_SLACK_MILLIS
    return trips
        .map { it to minOf(to, it.last.timestampMillis) - maxOf(from, it.first.timestampMillis) }
        .filter { (_, overlap) -> overlap >= 0L }
        .maxByOrNull { (_, overlap) -> overlap }
        ?.first
}

/** Each side of a focused session gets this share of its length as margin. */
const val FOCUS_MARGIN = 0.15

/** The least margin each side of a focused session, millis: a five-minute drive still shows the levels around it. */
const val MIN_FOCUS_MARGIN_MILLIS = 15 * 60_000L

private const val NAVIGATION_SLACK_MILLIS = 5 * 60_000L
