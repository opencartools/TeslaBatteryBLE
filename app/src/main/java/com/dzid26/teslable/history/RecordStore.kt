// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord

/** A (vehicle, kind) series of records; [vehicleId] is empty for the app-state series. */
internal data class SeriesRef(
    val vehicleId: String,
    val kind: RecordKind,
)

/** A record as the exporters read it: with its row id and its time on the series' clock. */
internal class StoredRecord(
    val id: Long,
    val time: Long,
    val record: BleRecord,
)

/**
 * Where the raw [BleRecord]s live (ADR-0014): append, read a series back by time, and stream a series for
 * export. The implementation is the app's; the interface keeps [HistoryStore] free of the engine. Readers
 * order by time and never expose row ids; only [stream], for the exporters, does. Calls block, so callers
 * run them off the main thread.
 */
internal interface RecordStore {
    /** The random id of this install, written into every row it acquires. */
    val originUuid: String

    fun append(
        vehicleId: String,
        kind: RecordKind,
        record: BleRecord,
    ) = appendAll(vehicleId, kind, listOf(record))

    /** Stores [records] in one short transaction; ones already stored are skipped. */
    fun appendAll(
        vehicleId: String,
        kind: RecordKind,
        records: List<BleRecord>,
    )

    /** Whether the file behind the car's records (or, with [events], the phone's events) holds no rows. */
    fun isEmpty(events: Boolean): Boolean

    /** Every series stored. */
    fun series(): List<SeriesRef>

    /** The newest [n] records of [series], oldest first. */
    fun readNewest(
        series: SeriesRef,
        n: Int,
    ): List<BleRecord>

    /** The records of [series] with `from <= time <= to` (epoch millis), oldest first. */
    fun readRange(
        series: SeriesRef,
        from: Long,
        to: Long,
    ): List<BleRecord>

    /** For the exporters: up to [limit] records of [series] after row [afterId], in storage order. */
    fun stream(
        series: SeriesRef,
        afterId: Long,
        limit: Int,
    ): List<StoredRecord>

    /** For each vehicle with records of [kind]: its newest [limitPerVehicle], oldest first. */
    fun readRecent(
        kind: RecordKind,
        limitPerVehicle: Int,
    ): Map<String, List<BleRecord>> =
        series()
            .filter { it.kind == kind }
            .associate { it.vehicleId to readNewest(it, limitPerVehicle) }
}

/** Joins per-car record maps of different kinds, keeping every kind's records for a car; `Map + Map` would drop one. */
internal fun mergeByVehicle(vararg logs: Map<String, List<BleRecord>>): Map<String, List<BleRecord>> =
    logs.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
