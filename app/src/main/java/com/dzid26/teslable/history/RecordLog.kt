// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord

/**
 * The car's records in `history.db` (ADR-0014). Callers hold the store's transaction where they need one.
 */
internal class RecordLog(
    override val db: HistoryDatabase,
    private val lookups: Lookups,
) : TimedLog {
    private val dao = db.records()

    override fun append(
        series: SeriesRef,
        records: List<BleRecord>,
        now: Long,
    ) {
        val seriesId = lookups.seriesId(series)
        dao.insertAll(records.map { rowOf(seriesId, series.kind, it, now) })
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
            decode(row)?.let { StoredRecord(row.id, row.time, it) }
        }

    override fun rowCount(): Int = dao.count()

    override val bounds =
        object : TimeBounds {
            override fun seriesIds(): List<Long> = dao.seriesIds()

            override fun firstTime(seriesId: Long): Long? = dao.firstTime(seriesId)

            override fun lastTime(seriesId: Long): Long? = dao.lastTime(seriesId)

            override fun deleteBefore(
                seriesId: Long,
                cutoff: Long,
            ): Int = dao.deleteBefore(seriesId, cutoff)
        }

    private fun rowOf(
        seriesId: Long,
        kind: RecordKind,
        record: BleRecord,
        now: Long,
    ): RecordRow =
        RecordRow(
            source = RowSource(seriesId, lookups.originId),
            time = timeOf(kind, record, now),
            odometer = record.drive_state?.odometer_in_hundredths_of_a_mile?.toLong(),
            blob = record.encode(),
        )

    private fun decode(row: RecordRow): BleRecord? = runCatching { BleRecord.ADAPTER.decode(row.blob) }.getOrNull()

    companion object {
        /**
         * The time of a record on its series' clock (epoch millis): the car's own timestamp for a car-clock
         * kind, the phone's arrival time otherwise. A car reply without its timestamp takes the phone time
         * and is never dropped.
         */
        fun timeOf(
            kind: RecordKind,
            record: BleRecord,
            now: Long,
        ): Long {
            val phone = record.device_timestamp?.toEpochMilli()
            val car = if (kind.clock == Clock.CAR) carTimeMillis(record) else null
            return car ?: phone ?: now
        }

        /** The car's own timestamp in whichever CarServer payload the record holds, or null. */
        internal fun carTimeMillis(record: BleRecord): Long? =
            (
                record.charge_state?.timestamp
                    ?: record.drive_state?.timestamp
                    ?: record.climate_state?.timestamp
                    ?: record.closures_state?.timestamp
                    ?: record.location_state?.timestamp
                    ?: record.tire_pressure_state?.timestamp
            )?.toEpochMilli()
    }
}
