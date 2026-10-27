// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.room.RoomDatabase
import com.dzid26.teslable.core.history.BleRecord

/**
 * One database file's table of timestamped records (ADR-0014): `records` in `history.db`, `events` in
 * `events.db`. Both are keyed by series, time and origin, read by time, and share the size cap.
 */
internal interface TimedLog {
    val db: RoomDatabase

    fun append(
        series: SeriesRef,
        records: List<BleRecord>,
        now: Long,
    )

    fun series(): List<SeriesRef>

    fun readNewest(
        series: SeriesRef,
        n: Int,
    ): List<BleRecord>

    fun readRange(
        series: SeriesRef,
        from: Long,
        to: Long,
    ): List<BleRecord>

    fun stream(
        series: SeriesRef,
        afterId: Long,
        limit: Int,
    ): List<StoredRecord>

    fun rowCount(): Int

    /** The time bounds of its series, for the size cap. */
    val bounds: TimeBounds
}

/** What the size cap needs of a table: which series have rows, their time span, and a ranged delete. */
internal interface TimeBounds {
    /** The ids of the series with rows. */
    fun seriesIds(): List<Long>

    fun firstTime(seriesId: Long): Long?

    /** The newest time of a series that is not after [notAfter]. */
    fun lastTime(
        seriesId: Long,
        notAfter: Long,
    ): Long?

    /** Drops a series' rows older than [cutoff]; returns how many. */
    fun deleteBefore(
        seriesId: Long,
        cutoff: Long,
    ): Int
}

/**
 * The safety cap on one file's size (ADR-0014). While the pages in use exceed [maxBytes], the rows older
 * than a cutoff go: the oldest tenth of the time span, all series together, in one transaction, at most
 * [maxPasses] times per call. The span ends at the newest time that is not more than a day after [nowMillis],
 * so one stray far-future timestamp cannot stretch it and take the real history with it; that row itself is
 * never the reason anything else goes. The size check is two pragmas, so a call costs nothing while there is room,
 * and it runs outside the transaction because it reads over a separate connection.
 */
internal fun TimedLog.trimToCap(
    maxBytes: Long,
    nowMillis: Long,
    maxPasses: Int = MAX_PASSES,
) {
    val notAfter = nowMillis + DAY_MILLIS
    repeat(maxPasses) {
        if (db.usedBytes() <= maxBytes) return
        var progressed = false
        db.runInTransaction {
            val ids = bounds.seriesIds()
            val first = ids.mapNotNull(bounds::firstTime).minOrNull()
            val last = ids.mapNotNull { bounds.lastTime(it, notAfter) }.maxOrNull()
            if (first != null && last != null && last > first) {
                val cutoff = first + maxOf(1L, (last - first) / TENTH)
                progressed = ids.sumOf { bounds.deleteBefore(it, cutoff) } > 0
            }
        }
        if (!progressed) return
    }
}

private const val MAX_PASSES = 20
private const val DAY_MILLIS = 86_400_000L
private const val TENTH = 10
