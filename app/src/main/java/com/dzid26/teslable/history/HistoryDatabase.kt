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
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import java.io.File

/**
 * One raw record (ADR-0014). [blob] is the encoded `BleRecord`, so unknown and future Tesla fields
 * survive untouched and the schema never holds proto fields. [RecordKey.carTimestamp] is the car's own
 * timestamp and is null where the reply has none; such a row is timed by [phoneTime]. [odometer] is the
 * drive reading in hundredths of a mile, filled at insert from the records that carry one.
 *
 * The unique key is `(vehicleId, kind, coalesce(carTimestamp, phoneTime), originId)`, so a re-import is a
 * no-op. Room cannot declare an expression index, so [HistoryDatabase.KEY_INDEX_SQL] creates it.
 */
@Entity(
    tableName = "records",
    indices = [
        Index(value = ["vehicleId", "kind", "carTimestamp"]),
        Index(value = ["vehicleId", "odometer"]),
    ],
)
internal class RecordRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val key: RecordKey,
    val phoneTime: Long,
    val odometer: Long?,
    val blob: ByteArray,
)

/** The columns that identify a record, with the series (vehicle and kind) first. */
internal class RecordKey(
    val vehicleId: String,
    val kind: String,
    val carTimestamp: Long?,
    val originId: String,
)

/**
 * One phone-side event (ADR-0014): a command the app sent or the car refused, a connection change, or an
 * app-state change. These carry no car timestamp and several can share a millisecond, so the unique key
 * adds [EventKey.contentHash], a 64-bit hash of the blob: only an exact duplicate (a re-import) is
 * ignored. [EventKey.vehicleId] is empty for app events, which belong to no car.
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["vehicleId", "kind", "originId", "phoneTime", "contentHash"], unique = true),
        Index(value = ["vehicleId", "kind", "phoneTime"]),
    ],
)
internal class EventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val key: EventKey,
    val blob: ByteArray,
)

/** The columns that identify an event. */
internal class EventKey(
    val vehicleId: String,
    val kind: String,
    val phoneTime: Long,
    val originId: String,
    val contentHash: Long,
)

/** A (vehicle, kind) series, how many rows it holds and the bytes of their blobs. */
internal class SeriesSize(
    val vehicleId: String,
    val kind: String,
    val rowCount: Long,
    val blobBytes: Long,
)

/** A (vehicle, kind) series. */
internal class SeriesKey(
    val vehicleId: String,
    val kind: String,
)

@Dao
internal interface RecordDao {
    /** Inserts the rows not already stored (same vehicle, kind, car timestamp and origin). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<RecordRow>): List<Long>

    /** The newest [limit] rows of a series, newest first. */
    @Query(
        "SELECT * FROM records WHERE vehicleId = :vehicleId AND kind = :kind " +
            "ORDER BY coalesce(carTimestamp, phoneTime) DESC, id DESC LIMIT :limit",
    )
    fun newest(
        vehicleId: String,
        kind: String,
        limit: Int,
    ): List<RecordRow>

    /** Up to [limit] rows of a series after [afterId], in insertion order, for streaming reads. */
    @Query("SELECT * FROM records WHERE vehicleId = :vehicleId AND kind = :kind AND id > :afterId ORDER BY id LIMIT :limit")
    fun page(
        vehicleId: String,
        kind: String,
        afterId: Long,
        limit: Int,
    ): List<RecordRow>

    @Query("SELECT DISTINCT vehicleId FROM records WHERE kind = :kind")
    fun vehicleIds(kind: String): List<String>

    @Query("SELECT DISTINCT vehicleId, kind FROM records")
    fun series(): List<SeriesKey>

    @Query("SELECT COUNT(*) FROM records")
    fun count(): Int

    @Query("SELECT vehicleId, kind, COUNT(*) AS rowCount, SUM(length(blob)) AS blobBytes FROM records GROUP BY vehicleId, kind")
    fun seriesSizes(): List<SeriesSize>

    /** Size retention: drops the [count] oldest rows of a series. */
    @Query(
        "DELETE FROM records WHERE id IN (SELECT id FROM records WHERE vehicleId = :vehicleId AND kind = :kind " +
            "ORDER BY coalesce(carTimestamp, phoneTime), id LIMIT :count)",
    )
    fun deleteOldest(
        vehicleId: String,
        kind: String,
        count: Int,
    ): Int
}

@Dao
internal interface EventDao {
    /** Inserts the events not already stored (same vehicle, kind, origin, phone time and content). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<EventRow>): List<Long>

    /** The newest [limit] events of a series, newest first. */
    @Query("SELECT * FROM events WHERE vehicleId = :vehicleId AND kind = :kind ORDER BY phoneTime DESC, id DESC LIMIT :limit")
    fun newest(
        vehicleId: String,
        kind: String,
        limit: Int,
    ): List<EventRow>

    /** Up to [limit] events of a series after [afterId], in insertion order, for streaming reads. */
    @Query("SELECT * FROM events WHERE vehicleId = :vehicleId AND kind = :kind AND id > :afterId ORDER BY id LIMIT :limit")
    fun page(
        vehicleId: String,
        kind: String,
        afterId: Long,
        limit: Int,
    ): List<EventRow>

    @Query("SELECT DISTINCT vehicleId FROM events WHERE kind = :kind")
    fun vehicleIds(kind: String): List<String>

    @Query("SELECT DISTINCT vehicleId, kind FROM events")
    fun series(): List<SeriesKey>

    @Query("SELECT COUNT(*) FROM events")
    fun count(): Int

    @Query("SELECT vehicleId, kind, COUNT(*) AS rowCount, SUM(length(blob)) AS blobBytes FROM events GROUP BY vehicleId, kind")
    fun seriesSizes(): List<SeriesSize>

    /** Size retention: drops the [count] oldest events of a series. */
    @Query(
        "DELETE FROM events WHERE id IN (SELECT id FROM events WHERE vehicleId = :vehicleId AND kind = :kind " +
            "ORDER BY phoneTime, id LIMIT :count)",
    )
    fun deleteOldest(
        vehicleId: String,
        kind: String,
        count: Int,
    ): Int
}

@Database(entities = [RecordRow::class, EventRow::class], version = 1, exportSchema = true)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract fun records(): RecordDao

    abstract fun events(): EventDao

    companion object {
        const val FILE_NAME = "history.db"

        /** The unique key of [RecordRow], which Room has no annotation for. */
        const val KEY_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS index_records_time_key ON records " +
                "(vehicleId, kind, coalesce(carTimestamp, phoneTime), originId)"

        private val keyIndexCallback =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(KEY_INDEX_SQL)

                // The same, for a database opened through a [SQLiteDriver] (the JVM tests).
                override fun onCreate(connection: SQLiteConnection) = connection.execSQL(KEY_INDEX_SQL)

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA synchronous = NORMAL").close()
                }
            }

        /**
         * Opens `history.db` (WAL, `synchronous=NORMAL`). A database that does not open (damaged, or a
         * schema no migration covers) is never deleted: it is renamed to `history.db.corrupt-<time>` (with
         * its `-wal` and `-shm`) and a new one is created, and if that fails too the history runs from an
         * empty in-memory one, so a damaged file never crashes the app (ADR-0014). Call it off the main
         * thread: it opens the file.
         */
        fun open(
            context: Context,
            driver: SQLiteDriver? = null,
        ): HistoryDatabase =
            runCatching { opened(context, driver) }
                .recoverCatching {
                    quarantine(context)
                    opened(context, driver)
                }.getOrElse { inMemory(context, onMainThread = driver != null, driver = driver) }

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
                .addCallback(keyIndexCallback)
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
                .addCallback(keyIndexCallback)
                .apply {
                    // A driver is only given by the JVM tests, which have no main Looper to tell threads apart.
                    if (driver != null) setDriver(driver).allowMainThreadQueries()
                }.build()
    }
}
