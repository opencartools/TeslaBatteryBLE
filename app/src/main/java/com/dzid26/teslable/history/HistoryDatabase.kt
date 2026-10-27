// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import android.util.Log
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
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File

/** A (vehicle, kind) series; its integer id stands for the pair in [RecordRow] and [EventRow] (ADR-0014). */
@Entity(tableName = "series", indices = [Index(value = ["vehicleId", "kind"], unique = true)])
internal class SeriesRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val kind: String,
)

/** An install that acquired records, by its random id; rows hold the integer [id] instead of the text. */
@Entity(tableName = "origins", indices = [Index(value = ["uuid"], unique = true)])
internal class OriginRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
)

/**
 * One raw record (ADR-0014). [blob] is the encoded `BleRecord`, so unknown and future Tesla fields
 * survive untouched and the schema never holds proto fields. [carTimestamp] is the car's own timestamp
 * and is null where the reply has none; such a row is timed by [phoneTime]. [odometer] is the drive
 * reading in hundredths of a mile, filled at insert from the records that carry one.
 *
 * Room cannot declare an expression index, so [HistoryDatabase.EXTRA_INDEXES_SQL] creates the unique key
 * `(seriesId, coalesce(carTimestamp, phoneTime), originId)`, so a re-import is a no-op (it also serves the
 * newest-first reads). The odometer has a column but no index until a reader needs one: adding it later
 * is a `CREATE INDEX`, with no change to the data.
 */
@Entity(tableName = "records")
internal class RecordRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val source: RecordSource,
    val carTimestamp: Long?,
    val phoneTime: Long,
    val odometer: Long?,
    val blob: ByteArray,
)

/** Where a record came from: its series and the install that acquired it. */
internal class RecordSource(
    val seriesId: Long,
    val originId: Long,
)

/**
 * One phone-side event (ADR-0014): a command the app sent or the car refused, a connection change, or an
 * app-state change. These carry no car timestamp and several can share a millisecond, so the unique key
 * adds [contentHash], a 64-bit hash of the blob: only an exact duplicate (a re-import) is ignored. The
 * 90-day retention deletes by [phoneTime] with a scan, which is cheap at the events' share of the cap.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["seriesId", "originId", "phoneTime", "contentHash"], unique = true)],
)
internal class EventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: Long,
    val originId: Long,
    val phoneTime: Long,
    val contentHash: Long,
    val blob: ByteArray,
)

/** A series, how many rows it holds and the bytes of their blobs. */
internal class SeriesSize(
    val seriesId: Long,
    val rowCount: Long,
    val blobBytes: Long,
)

/** The lookup tables. */
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
    /** Inserts the rows not already stored (same series, car timestamp or phone time, and origin). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<RecordRow>): List<Long>

    /** The newest [limit] rows of a series, newest first. */
    @Query(
        "SELECT * FROM records WHERE seriesId = :seriesId " +
            "ORDER BY coalesce(carTimestamp, phoneTime) DESC, id DESC LIMIT :limit",
    )
    fun newest(
        seriesId: Long,
        limit: Int,
    ): List<RecordRow>

    /** Up to [limit] rows of a series after [afterId], in insertion order, for streaming reads. */
    @Query("SELECT * FROM records WHERE seriesId = :seriesId AND id > :afterId ORDER BY id LIMIT :limit")
    fun page(
        seriesId: Long,
        afterId: Long,
        limit: Int,
    ): List<RecordRow>

    @Query("SELECT DISTINCT seriesId FROM records")
    fun seriesIds(): List<Long>

    @Query("SELECT COUNT(*) FROM records")
    fun count(): Int

    @Query("SELECT seriesId, COUNT(*) AS rowCount, SUM(length(blob)) AS blobBytes FROM records GROUP BY seriesId")
    fun seriesSizes(): List<SeriesSize>

    /** Size retention: drops the [count] oldest rows of a series. */
    @Query(
        "DELETE FROM records WHERE id IN (SELECT id FROM records WHERE seriesId = :seriesId " +
            "ORDER BY coalesce(carTimestamp, phoneTime), id LIMIT :count)",
    )
    fun deleteOldest(
        seriesId: Long,
        count: Int,
    ): Int
}

@Dao
internal interface EventDao {
    /** Inserts the events not already stored (same series, origin, phone time and content). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<EventRow>): List<Long>

    /** The newest [limit] events of a series, newest first. */
    @Query("SELECT * FROM events WHERE seriesId = :seriesId ORDER BY phoneTime DESC, id DESC LIMIT :limit")
    fun newest(
        seriesId: Long,
        limit: Int,
    ): List<EventRow>

    /** Up to [limit] events of a series after [afterId], in insertion order, for streaming reads. */
    @Query("SELECT * FROM events WHERE seriesId = :seriesId AND id > :afterId ORDER BY id LIMIT :limit")
    fun page(
        seriesId: Long,
        afterId: Long,
        limit: Int,
    ): List<EventRow>

    @Query("SELECT DISTINCT seriesId FROM events")
    fun seriesIds(): List<Long>

    @Query("SELECT COUNT(*) FROM events")
    fun count(): Int

    /** Age retention: drops every event acquired before [phoneTime]. */
    @Query("DELETE FROM events WHERE phoneTime < :phoneTime")
    fun deleteBefore(phoneTime: Long): Int

    @Query("SELECT seriesId, COUNT(*) AS rowCount, SUM(length(blob)) AS blobBytes FROM events GROUP BY seriesId")
    fun seriesSizes(): List<SeriesSize>

    /** Size retention: drops the [count] oldest events of a series. */
    @Query(
        "DELETE FROM events WHERE id IN (SELECT id FROM events WHERE seriesId = :seriesId " +
            "ORDER BY phoneTime, id LIMIT :count)",
    )
    fun deleteOldest(
        seriesId: Long,
        count: Int,
    ): Int
}

@Database(
    entities = [SeriesRow::class, OriginRow::class, RecordRow::class, EventRow::class],
    version = 1,
    exportSchema = true,
)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract fun meta(): MetaDao

    abstract fun records(): RecordDao

    abstract fun events(): EventDao

    /**
     * The bytes of pages in use, `(page_count - freelist_count) * page_size`. A trim frees pages but the
     * file keeps its length, so the file size alone would never fall back under the cap.
     */
    fun usedBytes(): Long =
        runBlocking {
            useReaderConnection { connection ->
                suspend fun pragma(name: String): Long =
                    connection.usePrepared("PRAGMA $name") {
                        it.step()
                        it.getLong(0)
                    }
                (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
            }
        }

    companion object {
        const val FILE_NAME = "history.db"
        private const val TAG = "HistoryDatabase"

        /** The indexes of [RecordRow] that Room has no annotation for. */
        val EXTRA_INDEXES_SQL =
            listOf(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_records_time_key ON records " +
                    "(seriesId, coalesce(carTimestamp, phoneTime), originId)",
            )

        private val extraIndexesCallback =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = EXTRA_INDEXES_SQL.forEach(db::execSQL)

                // The same, for a database opened through a [SQLiteDriver] (the JVM tests).
                override fun onCreate(connection: SQLiteConnection) = EXTRA_INDEXES_SQL.forEach(connection::execSQL)

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA synchronous = NORMAL").close()
                }
            }

        /**
         * Opens `history.db` (WAL, `synchronous=NORMAL`). A database that does not open (damaged, or a
         * schema no migration covers) is never deleted: it is renamed to `history.db.corrupt-<time>` (with
         * its `-wal` and `-shm`) and a new one is created, and if that fails too the history runs from an
         * empty in-memory one (logged once through [log]), so a damaged file never crashes the app
         * (ADR-0014). Call it off the main thread: it opens the file.
         */
        fun open(
            context: Context,
            driver: SQLiteDriver? = null,
            log: (String) -> Unit = { Log.w(TAG, it) },
        ): HistoryDatabase =
            runCatching { opened(context, driver) }
                .recoverCatching {
                    quarantine(context)
                    opened(context, driver)
                }.getOrElse {
                    log("history.db did not open; history runs in memory until the app restarts (${it.javaClass.simpleName})")
                    inMemory(context, onMainThread = driver != null, driver = driver)
                }

        /** Opens the existing `history.db` as it is, without ever moving it aside (for a second reader). */
        fun openExisting(context: Context): HistoryDatabase = opened(context, null)

        /** Builds and opens the database; one that fails to open is closed again so its files can move. */
        private fun opened(
            context: Context,
            driver: SQLiteDriver?,
        ): HistoryDatabase {
            val db = build(context, driver)
            return runCatching { db.also { it.warmUp() } }
                .onFailure { runCatching { db.close() } }
                .getOrThrow()
        }

        /** Moves the database files aside under a name no later quarantine reuses. */
        private fun quarantine(context: Context) {
            val stamp = System.currentTimeMillis()
            for (suffix in listOf("", "-wal", "-shm")) {
                val file = context.getDatabasePath(FILE_NAME + suffix)
                if (file.exists()) file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt-$stamp$suffix"))
            }
        }

        /** Opens the file now, so a damaged one fails here and not on the first write. */
        private fun HistoryDatabase.warmUp() {
            records().count()
        }

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
                .addCallback(extraIndexesCallback)
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
                .addCallback(extraIndexesCallback)
                .apply {
                    // A driver is only given by the JVM tests, which have no main Looper to tell threads apart.
                    if (driver != null) setDriver(driver).allowMainThreadQueries()
                }.build()
    }
}
