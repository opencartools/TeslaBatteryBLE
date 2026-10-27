// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver

/**
 * One kept [com.dzid26.teslable.core.history.BatterySample] of the long-term history (ADR-0014): the columns are the
 * sample's fields, so loading years of them decodes no blob. [chargingState] is the `ChargingStateKind` name.
 */
@Entity(tableName = "battery_samples", primaryKeys = ["vehicleId", "time"])
internal data class BatterySampleRow(
    val vehicleId: String,
    val time: Long,
    val level: Int,
    val usableLevel: Int,
    val chargingState: String?,
    val chargeLimit: Int?,
    val ratedRangeMiles: Float?,
    val chargeEnergyAdded: Float?,
    val chargeMilesAddedRated: Float?,
    val chargerVoltage: Int?,
    val chargeRateMph: Float?,
    val readAt: Long?,
)

/** One kept [com.dzid26.teslable.core.history.DriveSample]; [shiftState] is the `ShiftStateKind` name. */
@Entity(tableName = "drive_samples", primaryKeys = ["vehicleId", "time"])
internal data class DriveSampleRow(
    val vehicleId: String,
    val time: Long,
    val shiftState: String?,
    val speed: Int?,
    val power: Int?,
    val odometer: Int?,
)

/** One [com.dzid26.teslable.core.history.StatusSample]; the status log only holds changes and heartbeats, so all are kept. */
@Entity(tableName = "status_samples", primaryKeys = ["vehicleId", "time"])
internal data class StatusSampleRow(
    val vehicleId: String,
    val time: Long,
    val asleep: Boolean,
    val userPresent: Boolean,
    val locked: Boolean,
)

/** One kept [com.dzid26.teslable.core.history.ActivitySample]; [kind] is the `ActivityKind` name. */
@Entity(tableName = "activity_samples", primaryKeys = ["vehicleId", "kind", "time"])
internal data class ActivitySampleRow(
    val vehicleId: String,
    val kind: String,
    val time: Long,
    val on: Boolean,
)

/**
 * How far the builder has read one raw series of `history.db`: the row id and time of the newest record it
 * processed. The time tells a recreated `history.db` (whose ids start again) from the one the mark was made on.
 */
@Entity(tableName = "progress", primaryKeys = ["vehicleId", "kind"])
internal data class ProgressRow(
    val vehicleId: String,
    val kind: String,
    val lastRecordId: Long,
    val lastTime: Long,
)

/** What [DerivedBuilder] writes and resumes from. */
@Dao
internal interface DerivedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBattery(rows: List<BatterySampleRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDrive(rows: List<DriveSampleRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStatus(rows: List<StatusSampleRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertActivity(rows: List<ActivitySampleRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setProgress(row: ProgressRow)

    @Query("SELECT * FROM progress WHERE vehicleId = :vehicleId AND kind = :kind")
    fun progress(
        vehicleId: String,
        kind: String,
    ): ProgressRow?

    /** The newest kept battery sample of a car, where a resumed thinner continues from. */
    @Query("SELECT * FROM battery_samples WHERE vehicleId = :vehicleId ORDER BY time DESC LIMIT 1")
    fun newestBattery(vehicleId: String): BatterySampleRow?

    @Query("SELECT * FROM drive_samples WHERE vehicleId = :vehicleId ORDER BY time DESC LIMIT 1")
    fun newestDrive(vehicleId: String): DriveSampleRow?

    @Query("SELECT * FROM activity_samples WHERE vehicleId = :vehicleId AND kind = :kind ORDER BY time DESC LIMIT 1")
    fun newestActivity(
        vehicleId: String,
        kind: String,
    ): ActivitySampleRow?
}

/**
 * What the read models and the session builder load: the long-term history before the recent windows, and a car's
 * samples from a time on.
 */
@Dao
internal interface DerivedReadDao {
    /**
     * Every car's kept battery samples older than [before], oldest first. The recent windows end at one time for all
     * cars (the newest samples of all of them, merged), so one cutoff fits every car, including one whose raw records
     * are gone.
     */
    @Query("SELECT * FROM battery_samples WHERE time < :before ORDER BY time")
    fun batteryBefore(before: Long): List<BatterySampleRow>

    @Query("SELECT * FROM drive_samples WHERE time < :before ORDER BY time")
    fun driveBefore(before: Long): List<DriveSampleRow>

    @Query("SELECT * FROM status_samples WHERE time < :before ORDER BY time")
    fun statusBefore(before: Long): List<StatusSampleRow>

    @Query("SELECT * FROM activity_samples WHERE time < :before ORDER BY time")
    fun activityBefore(before: Long): List<ActivitySampleRow>

    /** One car's kept battery samples from [from] on, oldest first: what a session re-cut reads. */
    @Query("SELECT * FROM battery_samples WHERE vehicleId = :vehicleId AND time >= :from ORDER BY time")
    fun batteryFrom(
        vehicleId: String,
        from: Long,
    ): List<BatterySampleRow>

    @Query("SELECT * FROM drive_samples WHERE vehicleId = :vehicleId AND time >= :from ORDER BY time")
    fun driveFrom(
        vehicleId: String,
        from: Long,
    ): List<DriveSampleRow>
}

/**
 * `derived.db` (ADR-0014): the long-term history the charts and stats read, built from `history.db` by
 * [DerivedBuilder]. Everything here can be rebuilt from the raw records while they last, so the file is not
 * backed up, and one that does not open is kept aside like the others and rebuilt.
 */
@Database(
    entities = [
        BatterySampleRow::class,
        DriveSampleRow::class,
        StatusSampleRow::class,
        ActivitySampleRow::class,
        ProgressRow::class,
        ChargeSessionRow::class,
        DriveSessionRow::class,
        SessionDirtyRow::class,
    ],
    // 2 adds the session tables; the samples and marks of 1 stay as they are.
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class DerivedDatabase : RoomDatabase() {
    abstract fun derived(): DerivedDao

    abstract fun reads(): DerivedReadDao

    abstract fun sessions(): SessionDao

    companion object {
        const val FILE_NAME = "derived.db"

        /** Opens `derived.db`; one that does not open is kept aside, never deleted (see [openQuarantining]). */
        fun open(
            context: Context,
            driver: SQLiteDriver? = null,
            log: (String) -> Unit = defaultLog(),
        ): DerivedDatabase =
            openQuarantining(
                context = context,
                fileName = FILE_NAME,
                log = log,
                build = { build(context, driver) },
                inMemory = { inMemory(context, driver != null, driver) },
            )

        /** An empty database that lives in memory: the last-resort fallback, and the tests'. */
        fun inMemory(
            context: Context,
            onMainThread: Boolean = false,
            driver: SQLiteDriver? = null,
        ): DerivedDatabase =
            Room
                .inMemoryDatabaseBuilder(context, DerivedDatabase::class.java)
                .apply {
                    if (onMainThread) allowMainThreadQueries()
                    if (driver != null) setDriver(driver)
                }.build()

        private fun build(
            context: Context,
            driver: SQLiteDriver?,
        ): DerivedDatabase =
            Room
                .databaseBuilder(context, DerivedDatabase::class.java, FILE_NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(SynchronousNormal)
                .apply {
                    if (driver != null) setDriver(driver).allowMainThreadQueries()
                }.build()
    }
}
