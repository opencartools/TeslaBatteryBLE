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

    /** The ids of the series with rows. */
    fun seriesIds(): List<Long>

    fun firstTime(seriesId: Long): Long?

    fun lastTime(seriesId: Long): Long?

    /** Drops a series' rows older than [cutoff]; returns how many. */
    fun deleteBefore(
        seriesId: Long,
        cutoff: Long,
    ): Int
}

/**
 * The safety cap on one file's size (ADR-0014). While the pages in use exceed [maxBytes], the rows older
 * than a cutoff go: the oldest tenth of the time span, all series together, in one transaction, at most
 * [MAX_PASSES] times per call. The size check is two pragmas, so a call costs nothing while there is room,
 * and it runs outside the transaction because it reads over a separate connection.
 */
internal fun TimedLog.trimToCap(maxBytes: Long) {
    repeat(MAX_PASSES) {
        if (db.usedBytes() <= maxBytes) return
        var progressed = false
        db.runInTransaction {
            val ids = seriesIds()
            val first = ids.mapNotNull(::firstTime).minOrNull()
            val last = ids.mapNotNull(::lastTime).maxOrNull()
            if (first != null && last != null && last > first) {
                val cutoff = first + maxOf(1L, (last - first) / TENTH)
                progressed = ids.sumOf { deleteBefore(it, cutoff) } > 0
            }
        }
        if (!progressed) return
    }
}

private const val MAX_PASSES = 20
private const val TENTH = 10
