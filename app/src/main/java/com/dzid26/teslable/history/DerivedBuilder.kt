// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.ActivityThinner
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.batteryThinner
import com.dzid26.teslable.core.history.driveThinner
import com.dzid26.teslable.core.history.toActivitySample
import com.dzid26.teslable.core.history.toBatterySample
import com.dzid26.teslable.core.history.toDriveSample
import com.dzid26.teslable.core.history.toStatusSample

/**
 * Builds `derived.db` from the raw records (ADR-0014): every charge, drive, VCSEC, closures and climate series is
 * read in row order from where the last run stopped, mapped to samples, thinned, and stored. Each page and its
 * progress mark commit together, so an interrupted run resumes where it stopped and a re-run adds nothing twice
 * (rows are keyed by car and time). A mark that no longer matches its raw row (a recreated `history.db`) starts that
 * series again from the first row. Blocking; call it off the main thread.
 */
internal class DerivedBuilder(
    private val store: RecordStore,
    private val db: DerivedDatabase,
) {
    private val dao = db.derived()

    /** Brings every series up to date; returns how many raw records it read. */
    fun build(): Int = store.series().filter { it.kind in BUILT_KINDS }.sumOf(::buildSeries)

    private fun buildSeries(series: SeriesRef): Int {
        val sink = sinkFor(series)
        val atMark =
            dao.progress(series.vehicleId, series.kind.id)?.let { mark ->
                store.stream(series, mark.lastRecordId - 1, 1).firstOrNull()?.takeIf {
                    it.id == mark.lastRecordId &&
                        it.time == mark.lastTime
                }
            }
        atMark?.let { sink.resume(it.record) }
        var afterId = atMark?.id ?: 0L
        var read = 0
        while (true) {
            val page = store.stream(series, afterId, PAGE)
            if (page.isEmpty()) return read
            db.runInTransaction {
                sink.accept(page.map { it.record })
                dao.setProgress(ProgressRow(series.vehicleId, series.kind.id, page.last().id, page.last().time))
            }
            read += page.size
            afterId = page.last().id
        }
    }

    private fun sinkFor(series: SeriesRef): Sink =
        when (series.kind) {
            RecordKind.CHARGE -> BatterySink(dao, series.vehicleId)
            RecordKind.DRIVE -> DriveSink(dao, series.vehicleId)
            RecordKind.VCSEC -> StatusSink(dao, series.vehicleId)
            else -> ActivitySink(dao, series.vehicleId)
        }

    companion object {
        /** The raw series the long-term history is built from. */
        val BUILT_KINDS = setOf(RecordKind.CHARGE, RecordKind.DRIVE, RecordKind.VCSEC, RecordKind.CLOSURES, RecordKind.CLIMATE)

        private const val PAGE = 1_000
    }
}

/** What one raw series turns into: its thinner, resumed from the newest kept row and the raw record at the mark. */
private interface Sink {
    fun resume(latest: BleRecord)

    fun accept(records: List<BleRecord>)
}

private class BatterySink(
    private val dao: DerivedDao,
    private val vehicleId: String,
) : Sink {
    private val thinner = batteryThinner()

    override fun resume(latest: BleRecord) {
        val kept = dao.newestBattery(vehicleId)?.toSample() ?: return
        thinner.resume(kept, latest.toBatterySample(vehicleId) ?: kept)
    }

    override fun accept(records: List<BleRecord>) =
        dao.insertBattery(records.mapNotNull { it.toBatterySample(vehicleId) }.flatMap(thinner::accept).map { it.toRow() })
}

private class DriveSink(
    private val dao: DerivedDao,
    private val vehicleId: String,
) : Sink {
    private val thinner = driveThinner()

    override fun resume(latest: BleRecord) {
        val kept = dao.newestDrive(vehicleId)?.toSample() ?: return
        thinner.resume(kept, latest.toDriveSample(vehicleId) ?: kept)
    }

    override fun accept(records: List<BleRecord>) =
        dao.insertDrive(records.mapNotNull { it.toDriveSample(vehicleId) }.flatMap(thinner::accept).map { it.toRow() })
}

/** The status log already holds only changes and heartbeats, so every reading is kept. */
private class StatusSink(
    private val dao: DerivedDao,
    private val vehicleId: String,
) : Sink {
    override fun resume(latest: BleRecord) = Unit

    override fun accept(records: List<BleRecord>) = dao.insertStatus(records.mapNotNull { it.toStatusSample(vehicleId)?.toRow() })
}

/** Closures (Sentry) and climate readings, thinned as the read model thins them. */
private class ActivitySink(
    private val dao: DerivedDao,
    private val vehicleId: String,
) : Sink {
    private val thinner = ActivityThinner()

    override fun resume(latest: BleRecord) {
        val sample = latest.toActivitySample(vehicleId) ?: return
        val kept = dao.newestActivity(vehicleId, sample.kind.name)?.toSample() ?: return
        thinner.resume(kept, sample)
    }

    override fun accept(records: List<BleRecord>) =
        dao.insertActivity(records.mapNotNull { it.toActivitySample(vehicleId) }.flatMap(thinner::accept).map { it.toRow() })
}
