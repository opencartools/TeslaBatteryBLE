// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import java.time.Duration

/**
 * The [RecordStore] over Room (ADR-0014): the car's records in `history.db` ([RecordLog]) and the phone's
 * own logs in `events.db` ([EventLog]), each file with its own lookups and size cap. [originUuid] marks
 * this install's rows. Car records are kept for years (only a size cap, [maxBytes]); phone-side events for
 * [EVENT_RETENTION] and a cap of their own ([eventMaxBytes]). Retention runs every [TRIM_EVERY] appends and
 * whenever [trim] is called.
 */
internal class RoomBackedRecordStore(
    history: HistoryDatabase,
    events: EventsDatabase,
    override val originUuid: String,
    private val maxBytes: Long = MAX_BYTES,
    private val eventMaxBytes: Long = EVENT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecordStore {
    private val records = RecordLog(history, Lookups(history.meta(), originUuid))
    private val eventLog = EventLog(events, Lookups(events.meta(), originUuid))
    private var appendsSinceTrim = 0

    private fun logFor(kind: RecordKind): TimedLog = if (kind.isEvent) eventLog else records

    private fun logFor(series: SeriesRef): TimedLog = logFor(series.kind)

    override fun appendAll(
        vehicleId: String,
        kind: RecordKind,
        records: List<BleRecord>,
    ) {
        if (records.isEmpty()) return
        val log = logFor(kind)
        val now = clock()
        log.db.runInTransaction { log.append(SeriesRef(vehicleId, kind), records, now) }
        appendsSinceTrim += records.size
        if (appendsSinceTrim >= TRIM_EVERY) trim()
    }

    override fun series(): List<SeriesRef> = records.series() + eventLog.series()

    override fun readNewest(
        series: SeriesRef,
        n: Int,
    ): List<BleRecord> = logFor(series).readNewest(series, n)

    override fun readRange(
        series: SeriesRef,
        from: Long,
        to: Long,
    ): List<BleRecord> = logFor(series).readRange(series, from, to)

    override fun stream(
        series: SeriesRef,
        afterId: Long,
        limit: Int,
    ): List<StoredRecord> = logFor(series).stream(series, afterId, limit)

    /**
     * Retention as `DELETE`s. The phone-side events are dropped after [EVENT_RETENTION]. Each file then
     * stays under its size cap by dropping its oldest tenth of the time span, all series together, while
     * the pages in use exceed it ([trimToCap]); the check costs nothing while there is room. Car records
     * have no age retention (they are kept for years). [HistoryStore] runs the first trim after building
     * `derived.db`, and the build catches up as often as this runs, so what the cap drops is already there.
     */
    fun trim() {
        appendsSinceTrim = 0
        eventLog.db.runInTransaction { eventLog.expire(clock() - EVENT_RETENTION.toMillis()) }
        records.trimToCap(maxBytes, clock())
        eventLog.trimToCap(eventMaxBytes, clock())
    }

    override fun isEmpty(events: Boolean): Boolean = (if (events) eventLog else records).rowCount() == 0

    /** All rows of both files. */
    fun rowCount(): Int = records.rowCount() + eventLog.rowCount()

    companion object {
        /** The safety cap on `history.db`'s pages in use; years of history fit under it. */
        const val MAX_BYTES = 2_000_000_000L

        /** The cap on `events.db`: 90 days at the current rate is 50 to 60 MB, so this is a backstop. */
        const val EVENT_MAX_BYTES = 256_000_000L

        /** How long the phone-side events (commands, connection changes, app state) are kept. */
        val EVENT_RETENTION: Duration = Duration.ofDays(90)
        private const val TRIM_EVERY = 1_000
    }
}
