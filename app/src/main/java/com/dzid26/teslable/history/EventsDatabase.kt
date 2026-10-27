// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver

/**
 * One phone-side event (ADR-0014): a command the app sent or the car refused, a connection change, or an
 * app-state change. These carry no car timestamp and several can share a millisecond, so the unique key
 * adds [contentHash], a 64-bit hash of the blob: only an exact duplicate (a re-import) is ignored.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["seriesId", "phoneTime", "originId", "contentHash"], unique = true)],
)
internal class EventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val source: RowSource,
    val phoneTime: Long,
    val contentHash: Long,
    val blob: ByteArray,
)

@Dao
internal interface EventDao {
    /** Inserts the events not already stored (same series, phone time, origin and content). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<EventRow>): List<Long>

    /** The newest [limit] events of a series, newest first; the unique key gives this order. */
    @Query("SELECT * FROM events WHERE seriesId = :seriesId ORDER BY phoneTime DESC, originId DESC, contentHash DESC LIMIT :limit")
    fun newest(
        seriesId: Long,
        limit: Int,
    ): List<EventRow>

    /** The events of a series with `from <= phoneTime <= to`, oldest first. */
    @Query("SELECT * FROM events WHERE seriesId = :seriesId AND phoneTime BETWEEN :from AND :to ORDER BY phoneTime, originId, contentHash")
    fun range(
        seriesId: Long,
        from: Long,
        to: Long,
    ): List<EventRow>

    /** Up to [limit] events of a series after [afterId], in insertion order, for the exporters only. */
    @Query("SELECT * FROM events WHERE id > :afterId AND +seriesId = :seriesId ORDER BY id LIMIT :limit")
    fun page(
        seriesId: Long,
        afterId: Long,
        limit: Int,
    ): List<EventRow>

    @Query("SELECT DISTINCT seriesId FROM events")
    fun seriesIds(): List<Long>

    @Query("SELECT COUNT(*) FROM events")
    fun count(): Int

    @Query("SELECT MIN(phoneTime) FROM events WHERE seriesId = :seriesId")
    fun firstTime(seriesId: Long): Long?

    @Query("SELECT MAX(phoneTime) FROM events WHERE seriesId = :seriesId")
    fun lastTime(seriesId: Long): Long?

    /** Retention: drops a series' events older than [cutoff]. */
    @Query("DELETE FROM events WHERE seriesId = :seriesId AND phoneTime < :cutoff")
    fun deleteBefore(
        seriesId: Long,
        cutoff: Long,
    ): Int
}

/** `events.db`: the phone's own logs (commands, connection changes, app state), kept apart from the car's records (ADR-0014). */
@Database(
    entities = [SeriesRow::class, OriginRow::class, EventRow::class],
    version = 1,
    exportSchema = true,
)
internal abstract class EventsDatabase : RoomDatabase() {
    abstract fun meta(): MetaDao

    abstract fun events(): EventDao

    companion object {
        const val FILE_NAME = "events.db"

        /** Opens `events.db`; one that does not open is kept aside, never deleted (see [openQuarantining]). */
        fun open(
            context: Context,
            driver: SQLiteDriver? = null,
            log: (String) -> Unit = defaultLog(),
        ): EventsDatabase =
            openQuarantining(
                context = context,
                fileName = FILE_NAME,
                log = log,
                build = { build(context, driver) },
                inMemory = { inMemory(context, driver != null, driver) },
            )

        /** Opens the existing file as it is, without ever moving it aside (for a second reader). */
        fun openExisting(context: Context): EventsDatabase = build(context, null).also { it.meta().allSeries() }

        /** An empty database that lives in memory: the last-resort fallback, and the tests'. */
        fun inMemory(
            context: Context,
            onMainThread: Boolean = false,
            driver: SQLiteDriver? = null,
        ): EventsDatabase =
            Room
                .inMemoryDatabaseBuilder(context, EventsDatabase::class.java)
                .apply {
                    if (onMainThread) allowMainThreadQueries()
                    if (driver != null) setDriver(driver)
                }.build()

        private fun build(
            context: Context,
            driver: SQLiteDriver?,
        ): EventsDatabase =
            Room
                .databaseBuilder(context, EventsDatabase::class.java, FILE_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(SynchronousNormal)
                .apply {
                    if (driver != null) setDriver(driver).allowMainThreadQueries()
                }.build()
    }
}
