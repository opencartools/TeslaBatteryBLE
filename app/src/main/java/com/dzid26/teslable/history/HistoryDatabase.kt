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
import androidx.sqlite.db.SupportSQLiteDatabase

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
        Index(value = ["phoneTime"]),
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

/** A (vehicle, kind) series and how many rows it holds. */
internal class SeriesCount(
    val vehicleId: String,
    val kind: String,
    val total: Int,
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

    /** Age retention: drops every row acquired before [phoneTime]. */
    @Query("DELETE FROM records WHERE phoneTime < :phoneTime")
    fun deleteAcquiredBefore(phoneTime: Long): Int

    @Query("SELECT vehicleId, kind, COUNT(*) AS total FROM records GROUP BY vehicleId, kind HAVING COUNT(*) > :max")
    fun seriesOver(max: Int): List<SeriesCount>

    /** The time of the row that is [offset] places from the newest in a series, or null. */
    @Query(
        "SELECT coalesce(carTimestamp, phoneTime) FROM records WHERE vehicleId = :vehicleId AND kind = :kind " +
            "ORDER BY coalesce(carTimestamp, phoneTime) DESC LIMIT 1 OFFSET :offset",
    )
    fun carTimestampAt(
        vehicleId: String,
        kind: String,
        offset: Int,
    ): Long?

    /** Backstop retention: drops a series' rows timed on or before [time]. */
    @Query("DELETE FROM records WHERE vehicleId = :vehicleId AND kind = :kind AND coalesce(carTimestamp, phoneTime) <= :time")
    fun deleteUpTo(
        vehicleId: String,
        kind: String,
        time: Long,
    ): Int
}

@Database(entities = [RecordRow::class], version = 1, exportSchema = true)
internal abstract class HistoryDatabase : RoomDatabase() {
    abstract fun records(): RecordDao

    companion object {
        const val FILE_NAME = "history.db"

        /** The unique key of [RecordRow], which Room has no annotation for. */
        const val KEY_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS index_records_time_key ON records " +
                "(vehicleId, kind, coalesce(carTimestamp, phoneTime), originId)"

        private val keyIndexCallback =
            object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(KEY_INDEX_SQL)

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA synchronous = NORMAL").close()
                }
            }

        /**
         * Opens `history.db` (WAL, `synchronous=NORMAL`). A database that does not open is deleted and
         * recreated, and if that fails too the history runs from an empty in-memory one, so a damaged
         * file never crashes the app (ADR-0014). Call it off the main thread: it opens the file.
         */
        fun open(context: Context): HistoryDatabase =
            runCatching { opened(context) }
                .recoverCatching {
                    context.deleteDatabase(FILE_NAME)
                    opened(context)
                }.getOrElse { inMemory(context) }

        private fun opened(context: Context): HistoryDatabase = build(context).also { it.openHelper.writableDatabase }

        /** An empty database that lives in memory: the last-resort fallback, and the tests' ([onMainThread] for them). */
        fun inMemory(
            context: Context,
            onMainThread: Boolean = false,
        ): HistoryDatabase =
            Room
                .inMemoryDatabaseBuilder(context, HistoryDatabase::class.java)
                .addCallback(keyIndexCallback)
                .apply { if (onMainThread) allowMainThreadQueries() }
                .build()

        private fun build(context: Context): HistoryDatabase =
            Room
                .databaseBuilder(context, HistoryDatabase::class.java, FILE_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(true)
                .addCallback(keyIndexCallback)
                .build()
    }
}
