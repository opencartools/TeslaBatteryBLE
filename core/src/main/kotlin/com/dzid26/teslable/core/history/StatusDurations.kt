// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * How long the car has been asleep (or awake) and locked (or unlocked). Each
 * duration is null when the status log cannot say (see [statusDurations]).
 * The flag values are the newest sample's, so a caller shows a duration only
 * while the live flag still matches. User presence is not tracked.
 */
data class StatusDurations(
    val asleep: Boolean,
    val locked: Boolean,
    val asleepForMillis: Long?,
    val lockedForMillis: Long?,
)

/**
 * The status durations for [vehicleId] at [nowMillis], from its status log.
 *
 * The status log records a reading when it differs from the previous one, so a
 * flag's value began at the first record after the last record that held a
 * different value. That record's time is the since-time. The duration is
 * unknown (null) when:
 * - no record with a different value exists, so the log does not reach back to the change;
 * - the changing reading followed the different one by more than [maxGapMillis]
 *   (the app was not watching when it happened, so the real change time is unknown).
 *   A change seen on the first reading after a short reconnect is timed at that
 *   reading, so it can be late by at most the gap;
 * - the since-time is in the future (the phone's clock moved back).
 *
 * Durations are exact; [compactDuration] drops the ones under a minute for display.
 * Returns null when the vehicle has no records.
 *
 * @param samples status readings in any order; other vehicles' are ignored.
 */
fun statusDurations(
    samples: List<StatusSample>,
    vehicleId: String,
    nowMillis: Long,
    maxGapMillis: Long = STATUS_CHANGE_MAX_GAP_MILLIS,
): StatusDurations? {
    val own = samples.filter { it.vehicleId == vehicleId }.sortedBy { it.timestampMillis }
    val newest = own.lastOrNull() ?: return null

    fun forMillis(flag: (StatusSample) -> Boolean): Long? {
        val current = flag(newest)
        val differing = own.indexOfLast { flag(it) != current }
        if (differing < 0) return null
        val since = own[differing + 1]
        if (since.timestampMillis - own[differing].timestampMillis > maxGapMillis) return null
        return (nowMillis - since.timestampMillis).takeIf { it >= 0 }
    }
    return StatusDurations(
        asleep = newest.asleep,
        locked = newest.locked,
        asleepForMillis = forMillis { it.asleep },
        lockedForMillis = forMillis { it.locked },
    )
}

/**
 * A compact duration: "20min", "1h12m", "3h", "2d4h". Null under a minute. Minutes alone are
 * spelled "min", so "12min" next to a distance is never read as miles or metres; from an hour on
 * the units shorten to h and m. Minutes are dropped from a day on, as are hours that are zero.
 * The one duration format of the app: the trip card and notification, the status lines and the
 * health card all use it. There are no gaps.
 */
fun compactDuration(millis: Long): String? {
    val minutes = millis / MINUTE_MILLIS
    return when {
        minutes < 1 -> null
        minutes < 60 -> "${minutes}min"
        minutes < 24 * 60 -> "${minutes / 60}h" + (minutes % 60).takeIf { it > 0 }?.let { "${it}m" }.orEmpty()
        else -> "${minutes / (24 * 60)}d" + (minutes / 60 % 24).takeIf { it > 0 }?.let { "${it}h" }.orEmpty()
    }
}

/**
 * A flag change is trusted only when the two readings around it are no further
 * apart than this: sixteen minutes, a quarter hour of slack over the one minute
 * heartbeat. The log writes an unchanged status once a minute, so with a
 * continuous link consecutive records are seconds apart; the limit stays wide so
 * a change seen after the phone was away for a while is still timed by the first
 * reading that saw it.
 */
const val STATUS_CHANGE_MAX_GAP_MILLIS = 16 * 60_000L

private const val MINUTE_MILLIS = 60_000L
