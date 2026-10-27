// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.history.DriveSession

/**
 * One finished or open [ChargeSession] as `derived.db` keeps it (ADR-0014), cut by core from the stored samples.
 * Keyed by car and start time; the session builder re-cuts and replaces every row a new sample touches.
 */
@Entity(tableName = "charge_sessions", primaryKeys = ["vehicleId", "startTime"])
internal data class ChargeSessionRow(
    val vehicleId: String,
    val startTime: Long,
    val endTime: Long,
    val startPercent: Float,
    val endPercent: Float,
    val energyAdded: Float?,
    val milesAddedRated: Float?,
)

/** One [DriveSession]; odometer in hundredths of a mile, levels in percent, ranges in rated miles. */
@Entity(tableName = "drive_sessions", primaryKeys = ["vehicleId", "startTime"])
internal data class DriveSessionRow(
    val vehicleId: String,
    val startTime: Long,
    val endTime: Long,
    val startOdometer: Int,
    val endOdometer: Int,
    val startPercent: Float?,
    val endPercent: Float?,
    val startRange: Float?,
    val endRange: Float?,
    val unseen: Boolean,
)

/**
 * The oldest sample time per car that the sample builder stored since the sessions were last cut: the session
 * builder re-cuts from there. Written in the same transaction as the samples, so a crash between the two builds
 * loses nothing.
 */
@Entity(tableName = "session_dirty", primaryKeys = ["vehicleId"])
internal data class SessionDirtyRow(
    val vehicleId: String,
    val fromTime: Long,
)

/** The per-car marks the sample builder lowers and the session builder clears. */
@Dao
internal interface SessionDirtyDao {
    @Query("SELECT * FROM session_dirty")
    fun dirty(): List<SessionDirtyRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setDirty(row: SessionDirtyRow)

    @Query("DELETE FROM session_dirty WHERE vehicleId = :vehicleId AND fromTime = :fromTime")
    fun clearDirty(
        vehicleId: String,
        fromTime: Long,
    )

    /** The car's mark, if it has one. */
    @Query("SELECT fromTime FROM session_dirty WHERE vehicleId = :vehicleId")
    fun dirtyFrom(vehicleId: String): Long?
}

/** What the session builder and the session readers use. */
@Dao
internal interface SessionDao {
    /** The start of the car's newest charge session that starts before [time], where a re-cut begins. */
    @Query("SELECT MAX(startTime) FROM charge_sessions WHERE vehicleId = :vehicleId AND startTime < :time")
    fun newestChargeStartBefore(
        vehicleId: String,
        time: Long,
    ): Long?

    @Query("SELECT MAX(startTime) FROM drive_sessions WHERE vehicleId = :vehicleId AND startTime < :time")
    fun newestDriveStartBefore(
        vehicleId: String,
        time: Long,
    ): Long?

    @Query("DELETE FROM charge_sessions WHERE vehicleId = :vehicleId AND startTime >= :from")
    fun deleteChargesFrom(
        vehicleId: String,
        from: Long,
    )

    @Query("DELETE FROM drive_sessions WHERE vehicleId = :vehicleId AND startTime >= :from")
    fun deleteDrivesFrom(
        vehicleId: String,
        from: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCharges(rows: List<ChargeSessionRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDrives(rows: List<DriveSessionRow>)

    /** Every car's charge sessions, oldest first. */
    @Query("SELECT * FROM charge_sessions ORDER BY startTime")
    fun charges(): List<ChargeSessionRow>

    /** Every car's drives, oldest first. */
    @Query("SELECT * FROM drive_sessions ORDER BY startTime")
    fun drives(): List<DriveSessionRow>
}

internal fun ChargeSession.toRow(): ChargeSessionRow =
    ChargeSessionRow(vehicleId, startMillis, endMillis, startPercent, endPercent, energyAddedKwh, milesAddedRated)

internal fun ChargeSessionRow.toSession(): ChargeSession =
    ChargeSession(startTime, endTime, startPercent, endPercent, energyAdded, milesAddedRated, vehicleId = vehicleId)

internal fun DriveSession.toRow(): DriveSessionRow =
    DriveSessionRow(
        vehicleId,
        startMillis,
        endMillis,
        startOdometer,
        endOdometer,
        socStartPercent,
        socEndPercent,
        startRangeMiles,
        endRangeMiles,
        unseen,
    )

internal fun DriveSessionRow.toSession(): DriveSession =
    DriveSession(
        startMillis = startTime,
        endMillis = endTime,
        vehicleId = vehicleId,
        startOdometer = startOdometer,
        endOdometer = endOdometer,
        socStartPercent = startPercent,
        socEndPercent = endPercent,
        startRangeMiles = startRange,
        endRangeMiles = endRange,
        unseen = unseen,
    )
