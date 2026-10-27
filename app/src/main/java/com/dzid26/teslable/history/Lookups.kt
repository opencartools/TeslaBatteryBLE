// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

/**
 * The small lookup tables behind the integer columns of `history.db` (ADR-0014): the id of a
 * (vehicle, kind) series and of this install's [originUuid]. Ids are created on first use and cached.
 */
internal class Lookups(
    private val dao: MetaDao,
    originUuid: String,
) {
    /** The id of this install in `origins`. */
    val originId: Long

    private val seriesIds = HashMap<Pair<String, String>, Long>()

    init {
        dao.insertOrigin(OriginRow(uuid = originUuid))
        originId = checkNotNull(dao.originId(originUuid))
    }

    @Synchronized
    fun seriesId(
        vehicleId: String,
        kind: RecordKind,
    ): Long =
        seriesIds.getOrPut(vehicleId to kind.id) {
            dao.insertSeries(SeriesRow(vehicleId = vehicleId, kind = kind.id))
            checkNotNull(dao.seriesId(vehicleId, kind.id))
        }

    /** Every series stored, by id. */
    fun allSeries(): Map<Long, SeriesRow> = dao.allSeries().associateBy { it.id }
}

/** The (vehicle, kind) pairs among [ids] whose kind [matches], with the ids. */
internal fun Lookups.seriesOf(
    ids: List<Long>,
    matches: (RecordKind) -> Boolean,
): List<Triple<Long, String, RecordKind>> {
    val all = allSeries()
    return ids.mapNotNull { id ->
        val row = all[id] ?: return@mapNotNull null
        RecordKind.entries.firstOrNull { it.id == row.kind && matches(it) }?.let { Triple(id, row.vehicleId, it) }
    }
}
