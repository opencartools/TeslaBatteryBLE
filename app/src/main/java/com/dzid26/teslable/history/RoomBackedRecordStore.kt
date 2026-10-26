// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import java.time.Duration

/**
 * The [RecordStore] over `history.db` (ADR-0014). Each record is one row: the encoded [BleRecord] as a
 * blob next to the key columns, so retention is a `DELETE` and nothing is rewritten. [originId] marks
 * this install's rows. Car records are kept for years (only a safety cap on the database's size,
 * [maxBytes]); phone-side events for [EVENT_RETENTION]. Retention runs every [TRIM_EVERY] appends and
 * whenever [trim] is called.
 */
internal class RoomBackedRecordStore(
    private val db: HistoryDatabase,
    private val originId: String,
    private val maxBytes: Long = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecordStore {
    private val dao = db.records()
    private val events = EventLog(db.events(), originId)
    private var appendsSinceTrim = 0

    override fun appendAll(
        vehicleId: String,
        kind: RecordKind,
        records: List<BleRecord>,
    ) {
        if (records.isEmpty()) return
        val now = clock()
        db.runInTransaction {
            if (kind.isEvent) {
                events.append(vehicleId, kind, records, now)
            } else {
                dao.insertAll(records.map { rowOf(vehicleId, kind, it, now) })
            }
        }
        appendsSinceTrim += records.size
        if (appendsSinceTrim >= TRIM_EVERY) trim()
    }

    override fun readRecent(
        kind: RecordKind,
        limitPerVehicle: Int,
    ): Map<String, List<BleRecord>> =
        if (kind.isEvent) {
            events.readRecent(kind, limitPerVehicle)
        } else {
            dao.vehicleIds(kind.id).associateWith { vehicleId ->
                dao.newest(vehicleId, kind.id, limitPerVehicle).asReversed().mapNotNull(::decode)
            }
        }

    override fun series(): List<Pair<String, RecordKind>> =
        dao.series().mapNotNull { key ->
            RecordKind.entries.firstOrNull { it.id == key.kind && !it.isEvent }?.let { key.vehicleId to it }
        } + events.series()

    override fun streamSeries(
        vehicleId: String,
        kind: RecordKind,
        onBatch: (List<BleRecord>) -> Unit,
    ) {
        if (kind.isEvent) return events.stream(vehicleId, kind, onBatch)
        var afterId = 0L
        while (true) {
            val rows = dao.page(vehicleId, kind.id, afterId, EXPORT_BATCH)
            if (rows.isEmpty()) return
            onBatch(rows.mapNotNull(::decode))
            afterId = rows.last().id
        }
    }

    /**
     * Retention as `DELETE`s in one transaction. Car records are kept for years: only a size cap applies.
     * The phone-side events are dropped after [EVENT_RETENTION], and also get [EVENT_SHARE_PERCENT] of
     * [maxBytes] (the car records the rest), each trimmed oldest-first within itself, so a chatty
     * command log cannot evict car history. Nothing yet folds records into summaries first; that joins
     * this transaction with the derived store (ADR-0012).
     */
    fun trim() {
        appendsSinceTrim = 0
        val eventCap = maxBytes / PERCENT * EVENT_SHARE_PERCENT
        db.runInTransaction {
            trimToCap(maxBytes - eventCap, dao::seriesSizes) { series, count -> dao.deleteOldest(series.vehicleId, series.kind, count) }
            events.expire(clock() - EVENT_RETENTION.toMillis())
            events.trim(eventCap)
        }
    }

    fun rowCount(): Int = dao.count() + events.rowCount()

    private fun rowOf(
        vehicleId: String,
        kind: RecordKind,
        record: BleRecord,
        now: Long,
    ): RecordRow {
        val phone = record.device_timestamp?.toEpochMilli()
        val car = carTimeMillis(record)
        return RecordRow(
            key = RecordKey(vehicleId = vehicleId, kind = kind.id, carTimestamp = car, originId = originId),
            phoneTime = phone ?: car ?: now,
            odometer = record.drive_state?.odometer_in_hundredths_of_a_mile?.toLong(),
            blob = record.encode(),
        )
    }

    private fun decode(row: RecordRow): BleRecord? = runCatching { BleRecord.ADAPTER.decode(row.blob) }.getOrNull()

    companion object {
        /** The safety cap on the rows (blobs plus the key and index bytes of each row); years of history fit under it. */
        const val MAX_BYTES = 2_000_000_000L

        private const val PERCENT = 100

        /** How long the phone-side events (commands, connection changes, app state) are kept. */
        val EVENT_RETENTION: Duration = Duration.ofDays(90)

        /** The share of the size cap the phone-side events may use. */
        private const val EVENT_SHARE_PERCENT = 15
        private const val TRIM_EVERY = 1_000
        private const val EXPORT_BATCH = 1_000

        /** The car's own timestamp in the record's payload, or null when the reply has none. */
        internal fun carTimeMillis(record: BleRecord): Long? =
            record.charge_state?.timestamp?.toEpochMilli() ?: record.drive_state?.timestamp?.toEpochMilli()
    }
}
