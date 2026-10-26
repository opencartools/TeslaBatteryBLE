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
    private val originId: String,
) {
    fun append(
        vehicleId: String,
        kind: RecordKind,
        records: List<BleRecord>,
        now: Long,
    ) {
        dao.insertAll(records.map { rowOf(vehicleId, kind, it, now) })
    }

    fun readRecent(
        kind: RecordKind,
        limitPerVehicle: Int,
    ): Map<String, List<BleRecord>> =
        dao.vehicleIds(kind.id).associateWith { vehicleId ->
            dao.newest(vehicleId, kind.id, limitPerVehicle).asReversed().mapNotNull(::decode)
        }

    fun series(): List<Pair<String, RecordKind>> =
        dao.series().mapNotNull { key ->
            RecordKind.entries.firstOrNull { it.id == key.kind && it.isEvent }?.let { key.vehicleId to it }
        }

    fun stream(
        vehicleId: String,
        kind: RecordKind,
        onBatch: (List<BleRecord>) -> Unit,
    ) {
        var afterId = 0L
        while (true) {
            val rows = dao.page(vehicleId, kind.id, afterId, STREAM_BATCH)
            if (rows.isEmpty()) return
            onBatch(rows.mapNotNull(::decode))
            afterId = rows.last().id
        }
    }

    fun rowCount(): Int = dao.count()

    fun trim(cap: Long) = trimToCap(cap, dao::seriesSizes) { series, count -> dao.deleteOldest(series.vehicleId, series.kind, count) }

    private fun rowOf(
        vehicleId: String,
        kind: RecordKind,
        record: BleRecord,
        now: Long,
    ): EventRow {
        val blob = record.encode()
        val phone = record.device_timestamp?.toEpochMilli() ?: now
        return EventRow(key = EventKey(vehicleId, kind.id, phone, originId, contentHash(blob)), blob = blob)
    }

    private fun decode(row: EventRow): BleRecord? = runCatching { BleRecord.ADAPTER.decode(row.blob) }.getOrNull()

    private companion object {
        const val STREAM_BATCH = 1_000

        /** The first 64 bits of the blob's SHA-256: equal events collide, different ones do not. */
        fun contentHash(blob: ByteArray): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(blob)).getLong()
    }
}
