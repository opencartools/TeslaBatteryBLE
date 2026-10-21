// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord

/**
 * Where the raw [BleRecord]s live (ADR-0014): append, read back the newest of a kind, stream a series
 * for export. The implementation is the app's; the interface keeps [HistoryStore] free of the engine.
 * Calls block, so callers run them off the main thread.
 */
internal interface RecordStore {
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

    /** For each vehicle with records of [kind]: its newest [limitPerVehicle], oldest first. */
    fun readRecent(
        kind: RecordKind,
        limitPerVehicle: Int,
    ): Map<String, List<BleRecord>>

    /** Every (vehicle, kind) series stored. */
    fun series(): List<Pair<String, RecordKind>>

    /** Calls [onBatch] with a series' records in storage order, a batch at a time. */
    fun streamSeries(
        vehicleId: String,
        kind: RecordKind,
        onBatch: (List<BleRecord>) -> Unit,
    )
}

/** Joins per-car record maps of different kinds, keeping every kind's records for a car; `Map + Map` would drop one. */
internal fun mergeByVehicle(vararg logs: Map<String, List<BleRecord>>): Map<String, List<BleRecord>> =
    logs.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
