// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import java.time.Duration

/**
 * The [RecordStore] over `history.db` (ADR-0014). Each record is one row: the encoded [BleRecord] as a
 * blob next to the key columns, so retention is a `DELETE` and nothing is rewritten. [originId] marks
 * this install's rows. Age retention (90 days) and the per-series row backstop (200,000) run every
 * [TRIM_EVERY] appends and whenever [trim] is called.
 */
internal class RoomBackedRecordStore(
    private val db: HistoryDatabase,
    private val originId: String,
    private val maxRowsPerSeries: Int = MAX_ROWS_PER_SERIES,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecordStore {
    private val dao = db.records()
    private var appendsSinceTrim = 0

    override fun appendAll(
        vehicleId: String,
        kind: RecordKind,
        records: List<BleRecord>,
    ) {
        if (records.isEmpty()) return
        val now = clock()
        val rows = records.map { rowOf(vehicleId, kind, it, now) }
        db.runInTransaction { dao.insertAll(rows) }
        appendsSinceTrim += rows.size
        if (appendsSinceTrim >= TRIM_EVERY) trim()
    }

    override fun readRecent(
        kind: RecordKind,
        limitPerVehicle: Int,
    ): Map<String, List<BleRecord>> =
        dao.vehicleIds(kind.id).associateWith { vehicleId ->
            dao.newest(vehicleId, kind.id, limitPerVehicle).asReversed().mapNotNull(::decode)
        }

    override fun series(): List<Pair<String, RecordKind>> =
        dao.series().mapNotNull { key ->
            RecordKind.entries.firstOrNull { it.id == key.kind }?.let { key.vehicleId to it }
        }

    override fun streamSeries(
        vehicleId: String,
        kind: RecordKind,
        onBatch: (List<BleRecord>) -> Unit,
    ) {
        var afterId = 0L
        while (true) {
            val rows = dao.page(vehicleId, kind.id, afterId, EXPORT_BATCH)
            if (rows.isEmpty()) return
            onBatch(rows.mapNotNull(::decode))
            afterId = rows.last().id
        }
    }

    /**
     * Retention as `DELETE`s in one transaction: rows acquired more than [RETENTION] before [now], then
     * the oldest rows of any series over [maxRowsPerSeries]. Nothing yet folds records into
     * summaries first; that joins this transaction with the derived store (ADR-0012).
     */
    fun trim(now: Long = clock()) {
        appendsSinceTrim = 0
        db.runInTransaction {
            dao.deleteAcquiredBefore(now - RETENTION.toMillis())
            for (over in dao.seriesOver(maxRowsPerSeries)) {
                dao.carTimestampAt(over.vehicleId, over.kind, maxRowsPerSeries)?.let {
                    dao.deleteUpTo(over.vehicleId, over.kind, it)
                }
            }
        }
    }

    fun rowCount(): Int = dao.count()

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
        val RETENTION: Duration = Duration.ofDays(90)
        const val MAX_ROWS_PER_SERIES = 200_000
        private const val TRIM_EVERY = 1_000
        private const val EXPORT_BATCH = 1_000

        /** The car's own timestamp in the record's payload, or null when the reply has none. */
        internal fun carTimeMillis(record: BleRecord): Long? =
            record.charge_state?.timestamp?.toEpochMilli() ?: record.drive_state?.timestamp?.toEpochMilli()
    }
}
