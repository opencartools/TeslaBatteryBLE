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
 * A (vehicle, kind) series; its integer id stands for the pair in the record tables, and [clock] says
 * whose clock times it ([Clock.id]). Both `history.db` and `events.db` carry their own copy (ADR-0014).
 */
@Entity(tableName = "series", indices = [Index(value = ["vehicleId", "kind"], unique = true)])
internal class SeriesRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val kind: String,
    val clock: String,
)

/** An install that acquired records, by its random id; rows hold the integer [id] instead of the text. */
@Entity(tableName = "origins", indices = [Index(value = ["uuid"], unique = true)])
internal class OriginRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
)

/** Where a row came from: its series and the install that acquired it. */
internal class RowSource(
    val seriesId: Long,
    val originId: Long,
)

/**
 * One raw record of the car (ADR-0014). [blob] is the encoded `BleRecord`, so unknown and future Tesla
 * fields survive untouched and the schema never holds proto fields. [time] is epoch millis on the series'
 * clock: the car's own timestamp for a car-clock series, the phone's arrival time otherwise (and for a
 * car reply that lacks its timestamp, which is never dropped). [odometer] is the drive reading in
 * hundredths of a mile, filled at insert from records that carry one, with no index until a reader needs
 * one (adding it is a `CREATE INDEX`, no data change). The unique key makes a re-import a no-op, and
 * serves every read, which orders by [time] and never by `id`.
 */
@Entity(
    tableName = "records",
    indices = [Index(value = ["seriesId", "time", "originId"], unique = true)],
)
internal class RecordRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val source: RowSource,
    val time: Long,
    val odometer: Long?,
    val blob: ByteArray,
)

/** The lookup tables, the same in both databases. */
@Dao
internal interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSeries(row: SeriesRow): Long

    @Query("SELECT id FROM series WHERE vehicleId = :vehicleId AND kind = :kind")
    fun seriesId(
        vehicleId: String,
        kind: String,
    ): Long?

    @Query("SELECT * FROM series")
    fun allSeries(): List<SeriesRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrigin(row: OriginRow): Long

    @Query("SELECT id FROM origins WHERE uuid = :uuid")
    fun originId(uuid: String): Long?
}

@Dao
internal interface RecordDao {
    /** Inserts the rows not already stored (same series, time and origin). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<RecordRow>): List<Long>

    /** The newest [limit] rows of a series, newest first; the unique key gives this order. */
    @Query("SELECT * FROM records WHERE seriesId = :seriesId ORDER BY time DESC, originId DESC LIMIT :limit")
    fun newest(
        seriesId: Long,
        limit: Int,
    ): List<RecordRow>

    /** The rows of a series with `from <= time <= to`, oldest first. */
    @Query("SELECT * FROM records WHERE seriesId = :seriesId AND time BETWEEN :from AND :to ORDER BY time, originId")
    fun range(
        seriesId: Long,
        from: Long,
        to: Long,
    ): List<RecordRow>

    /**
     * Up to [limit] rows of a series after [afterId], in insertion order, for the exporters only. The
     * unary plus keeps SQLite from picking the series index, which would sort the whole series for every
     * page; it walks the primary key from [afterId] instead and stops after [limit] matches.
     */
    @Query("SELECT * FROM records WHERE id > :afterId AND +seriesId = :seriesId ORDER BY id LIMIT :limit")
    fun page(
        seriesId: Long,
        afterId: Long,
        limit: Int,
    ): List<RecordRow>

    @Query("SELECT DISTINCT seriesId FROM records")
    fun seriesIds(): List<Long>

    @Query("SELECT COUNT(*) FROM records")
    fun count(): Int

    @Query("SELECT MIN(time) FROM records WHERE seriesId = :seriesId")
    fun firstTime(seriesId: Long): Long?

    /** The newest time of a series that is not after [notAfter] (a far-future stamp does not count). */
    @Query("SELECT MAX(time) FROM records WHERE seriesId = :seriesId AND time <= :notAfter")
    fun lastTime(
        seriesId: Long,
        notAfter: Long,
    ): Long?

    /** Size retention: drops a series' rows older than [cutoff]. */
    @Query("DELETE FROM records WHERE seriesId = :seriesId AND time < :cutoff")
    fun deleteBefore(
        seriesId: Long,
        cutoff: Long,
    ): Int
}

/** `history.db`: the car's raw records (ADR-0014). */
@Database(
    entities = [SeriesRow::class, OriginRow::class, RecordRow::class],
    version = 1,
    exportSchema = true,
)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract fun meta(): MetaDao

    abstract fun records(): RecordDao

    companion object {
        const val FILE_NAME = "history.db"

        /**
         * Opens `history.db` (WAL, `synchronous=NORMAL`); one that does not open is kept aside, never
         * deleted (see [openQuarantining]). Call it off the main thread: it opens the file.
         */
        fun open(
            context: Context,
            driver: SQLiteDriver? = null,
            log: (String) -> Unit = defaultLog(),
        ): HistoryDatabase =
            openQuarantining(
                context = context,
                fileName = FILE_NAME,
                log = log,
                build = { build(context, driver) },
                inMemory = { inMemory(context, driver != null, driver) },
            )

        /** Opens the existing file as it is, without ever moving it aside (for a second reader). */
        fun openExisting(context: Context): HistoryDatabase = build(context, null).also { it.meta().allSeries() }

        /**
         * An empty database that lives in memory: the last-resort fallback, and the tests' ([onMainThread]
         * and a JVM [driver] are for them).
         */
        fun inMemory(
            context: Context,
            onMainThread: Boolean = false,
            driver: SQLiteDriver? = null,
        ): HistoryDatabase =
            Room
                .inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
                .apply {
                    if (onMainThread) allowMainThreadQueries()
                    if (driver != null) setDriver(driver)
                }.build()

        private fun build(
            context: Context,
            driver: SQLiteDriver?,
        ): HistoryDatabase =
            Room
                .databaseBuilder(context, HistoryDatabase::class.java, FILE_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(SynchronousNormal)
                .apply {
                    // A driver is only given by the JVM tests, which have no main Looper to tell threads apart.
                    if (driver != null) setDriver(driver).allowMainThreadQueries()
                }.build()
    }
}
