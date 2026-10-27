// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The phone-side logs in `events.db` (ADR-0014): the app's commands, connection changes and own state
 * ([RecordKind.isEvent]). They carry no car timestamp, so every row is timed by the phone.
 */
internal class EventLog(
    override val db: EventsDatabase,
    private val lookups: Lookups,
) : TimedLog {
    private val dao = db.events()

    override fun append(
        series: SeriesRef,
        records: List<BleRecord>,
        now: Long,
    ) {
        val seriesId = lookups.seriesId(series)
        dao.insertAll(records.map { rowOf(seriesId, it, now) })
    }

    override fun series(): List<SeriesRef> = lookups.resolve(dao.seriesIds()).map { it.second }

    override fun readNewest(
        series: SeriesRef,
        n: Int,
    ): List<BleRecord> = dao.newest(lookups.seriesId(series), n).asReversed().mapNotNull(::decode)

    override fun readRange(
        series: SeriesRef,
        from: Long,
        to: Long,
    ): List<BleRecord> = dao.range(lookups.seriesId(series), from, to).mapNotNull(::decode)

    override fun stream(
        series: SeriesRef,
        afterId: Long,
        limit: Int,
    ): List<StoredRecord> =
        dao.page(lookups.seriesId(series), afterId, limit).mapNotNull { row ->
            decode(row)?.let { StoredRecord(row.id, row.phoneTime, it) }
        }

    override fun rowCount(): Int = dao.count()

    override fun seriesIds(): List<Long> = dao.seriesIds()

    override fun firstTime(seriesId: Long): Long? = dao.firstTime(seriesId)

    override fun lastTime(seriesId: Long): Long? = dao.lastTime(seriesId)

    override fun deleteBefore(
        seriesId: Long,
        cutoff: Long,
    ): Int = dao.deleteBefore(seriesId, cutoff)

    /** Age retention: drops the events older than [before] (epoch millis), every series. */
    fun expire(before: Long) = dao.seriesIds().forEach { dao.deleteBefore(it, before) }

    private fun rowOf(
        seriesId: Long,
        record: BleRecord,
        now: Long,
    ): EventRow {
        val blob = record.encode()
        val phone = record.device_timestamp?.toEpochMilli() ?: now
        return EventRow(source = RowSource(seriesId, lookups.originId), phoneTime = phone, contentHash = contentHash(blob), blob = blob)
    }

    private fun decode(row: EventRow): BleRecord? = runCatching { BleRecord.ADAPTER.decode(row.blob) }.getOrNull()

    private companion object {
        /** The first 64 bits of the blob's SHA-256: equal events collide, different ones do not. */
        fun contentHash(blob: ByteArray): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(blob)).getLong()
    }
}
