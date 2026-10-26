// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord

/**
 * The [RecordStore] over `history.db` (ADR-0014). Each record is one row: the encoded [BleRecord] as a
 * blob next to the key columns, so retention is a `DELETE` and nothing is rewritten. [originId] marks
 * this install's rows. Records are kept for years: there is no age retention, only a safety cap on the
 * database's size ([maxBytes]) that runs every [TRIM_EVERY] appends and whenever [trim] is called.
 */
internal class RoomBackedRecordStore(
    private val db: HistoryDatabase,
    private val originId: String,
    private val maxBytes: Long = MAX_BYTES,
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
     * The size cap, as `DELETE`s in one transaction: while the rows add up to more than [maxBytes], the
     * oldest rows of the largest series go, down to [TRIM_TARGET_PERCENT] of the cap. Nothing yet folds
     * records into summaries first; that joins this transaction with the derived store (ADR-0012).
     */
    fun trim() {
        appendsSinceTrim = 0
        db.runInTransaction {
            var sizes = dao.seriesSizes()
            var total = sizes.sumOf { it.bytes() }
            val target = maxBytes / PERCENT * TRIM_TARGET_PERCENT
            while (total > maxBytes) {
                val largest = sizes.maxBy { it.bytes() }
                val perRow = largest.bytes() / largest.rowCount
                val count = ((total - target) / perRow + 1).coerceAtMost(largest.rowCount)
                dao.deleteOldest(largest.vehicleId, largest.kind, count.toInt())
                sizes = dao.seriesSizes()
                total = sizes.sumOf { it.bytes() }
            }
        }
    }

    private fun SeriesSize.bytes(): Long = blobBytes + rowCount * ROW_OVERHEAD_BYTES

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
        /** The safety cap on the rows (blobs plus [ROW_OVERHEAD_BYTES] each); years of history fit under it. */
        const val MAX_BYTES = 2_000_000_000L

        /** What a row costs on disk besides its blob: the key columns and the indexes (190 bytes measured on real logs). */
        const val ROW_OVERHEAD_BYTES = 200L
        private const val PERCENT = 100
        private const val TRIM_TARGET_PERCENT = 90
        private const val TRIM_EVERY = 1_000
        private const val EXPORT_BATCH = 1_000

        /** The car's own timestamp in the record's payload, or null when the reply has none. */
        internal fun carTimeMillis(record: BleRecord): Long? =
            record.charge_state?.timestamp?.toEpochMilli() ?: record.drive_state?.timestamp?.toEpochMilli()
    }
}
