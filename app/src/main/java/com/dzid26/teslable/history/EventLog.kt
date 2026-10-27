// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The phone-side half of `history.db` (ADR-0014): the app's commands, connection changes and own state
 * ([RecordKind.isEvent]), in the `events` table. Callers hold the store's transaction.
 */
internal class EventLog(
    private val dao: EventDao,
    private val lookups: Lookups,
) {
    fun append(
        vehicleId: String,
        kind: RecordKind,
        records: List<BleRecord>,
        now: Long,
    ) {
        val seriesId = lookups.seriesId(vehicleId, kind)
        dao.insertAll(records.map { rowOf(seriesId, it, now) })
    }

    fun readRecent(
        kind: RecordKind,
        limitPerVehicle: Int,
    ): Map<String, List<BleRecord>> =
        lookups
            .seriesOf(dao.seriesIds()) { it == kind }
            .associate { (id, vehicleId, _) -> vehicleId to dao.newest(id, limitPerVehicle).asReversed().mapNotNull(::decode) }

    fun series(): List<Pair<String, RecordKind>> =
        lookups.seriesOf(dao.seriesIds()) { it.isEvent }.map { (_, vehicleId, kind) ->
            vehicleId to
                kind
        }

    fun stream(
        vehicleId: String,
        kind: RecordKind,
        onBatch: (List<BleRecord>) -> Unit,
    ) {
        val seriesId = lookups.seriesId(vehicleId, kind)
        var afterId = 0L
        while (true) {
            val rows = dao.page(seriesId, afterId, STREAM_BATCH)
            if (rows.isEmpty()) return
            onBatch(rows.mapNotNull(::decode))
            afterId = rows.last().id
        }
    }

    fun rowCount(): Int = dao.count()

    /** Age retention for the phone-side logs: drops the events older than [before] (epoch millis). */
    fun expire(before: Long) = dao.deleteBefore(before)

    /** The sizes of the event series, for the size cap. */
    fun sizes(): List<SeriesSize> = dao.seriesSizes()

    fun deleteOldest(
        seriesId: Long,
        count: Int,
    ): Int = dao.deleteOldest(seriesId, count)

    private fun rowOf(
        seriesId: Long,
        record: BleRecord,
        now: Long,
    ): EventRow {
        val blob = record.encode()
        val phone = record.device_timestamp?.toEpochMilli() ?: now
        return EventRow(seriesId = seriesId, originId = lookups.originId, phoneTime = phone, contentHash = contentHash(blob), blob = blob)
    }

    private fun decode(row: EventRow): BleRecord? = runCatching { BleRecord.ADAPTER.decode(row.blob) }.getOrNull()

    private companion object {
        const val STREAM_BATCH = 1_000

        /** The first 64 bits of the blob's SHA-256: equal events collide, different ones do not. */
        fun contentHash(blob: ByteArray): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(blob)).getLong()
    }
}
