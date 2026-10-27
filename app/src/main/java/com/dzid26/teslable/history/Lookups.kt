// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

/**
 * The small lookup tables behind the integer columns of one database file (ADR-0014): the id of a
 * (vehicle, kind) series, with its clock, and of this install's [originUuid]. Ids are created on first use
 * and cached.
 */
internal class Lookups(
    private val dao: MetaDao,
    originUuid: String,
) {
    /** The id of this install in `origins`. */
    val originId: Long

    private val seriesIds = HashMap<SeriesRef, Long>()

    init {
        dao.insertOrigin(OriginRow(uuid = originUuid))
        originId = checkNotNull(dao.originId(originUuid))
    }

    @Synchronized
    fun seriesId(series: SeriesRef): Long =
        seriesIds.getOrPut(series) {
            dao.insertSeries(SeriesRow(vehicleId = series.vehicleId, kind = series.kind.id, clock = series.kind.clock.id))
            checkNotNull(dao.seriesId(series.vehicleId, series.kind.id))
        }

    /** The id of [series] if it has been created, or null: reads use this and never create a series row. */
    @Synchronized
    fun existingSeriesId(series: SeriesRef): Long? =
        seriesIds[series] ?: dao.seriesId(series.vehicleId, series.kind.id)?.also { seriesIds[series] = it }

    /** The series among [ids] (rows of a table) as references; ids of unknown kinds are skipped. */
    fun resolve(ids: List<Long>): List<Pair<Long, SeriesRef>> {
        val rows = dao.allSeries().associateBy { it.id }
        return ids.mapNotNull { id ->
            val row = rows[id] ?: return@mapNotNull null
            RecordKind.entries.firstOrNull { it.id == row.kind }?.let { id to SeriesRef(row.vehicleId, it) }
        }
    }
}
