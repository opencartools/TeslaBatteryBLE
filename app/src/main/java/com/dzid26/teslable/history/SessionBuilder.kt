// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.DriveSession
import com.dzid26.teslable.core.history.SessionStore
import com.dzid26.teslable.core.history.recutSessions

/**
 * Cuts the charge and drive sessions of `derived.db` from its stored samples (ADR-0014), with the same core functions
 * the screen uses. The sample builder marks, per car, the oldest sample time it stored since the last cut; this
 * re-cuts from there with [recutSessions], replacing the sessions it can change in one transaction. Re-cutting is
 * idempotent, so late replies and re-runs are safe, and since it reads `derived.db`'s own samples, sessions survive
 * the raw records they came from. Levels are stored as the car reported them; readers apply the current full-range
 * scale. Blocking; call it off the main thread.
 */
internal class SessionBuilder(
    private val db: DerivedDatabase,
) {
    private val sessions = db.sessions()
    private val marks = db.sessionMarks()
    private val reads = db.reads()

    /** Re-cuts every car with new samples; returns how many cars it re-cut. */
    fun build(): Int =
        marks.dirty().count { mark ->
            db.runInTransaction {
                recutSessions(Stored(mark.vehicleId), mark.fromTime)
                marks.clearDirty(mark.vehicleId, mark.fromTime)
            }
            true
        }

    private inner class Stored(
        private val vehicleId: String,
    ) : SessionStore {
        override fun newestChargeStartBefore(millis: Long) = sessions.newestChargeStartBefore(vehicleId, millis)

        override fun newestDriveStartBefore(millis: Long) = sessions.newestDriveStartBefore(vehicleId, millis)

        override fun driveReadingBefore(millis: Long) = reads.driveReadingBefore(vehicleId, millis)

        override fun batteryFrom(millis: Long): List<BatterySample> = reads.batteryFrom(vehicleId, millis).map { it.toSample() }

        override fun drivesFrom(millis: Long): List<DriveSample> = reads.driveFrom(vehicleId, millis).map { it.toSample() }

        override fun replaceCharges(
            millis: Long,
            sessions: List<ChargeSession>,
        ) {
            this@SessionBuilder.sessions.deleteChargesFrom(vehicleId, millis)
            this@SessionBuilder.sessions.insertCharges(sessions.map { it.toRow() })
        }

        override fun replaceDrives(
            millis: Long,
            sessions: List<DriveSession>,
        ) {
            this@SessionBuilder.sessions.deleteDrivesFrom(vehicleId, millis)
            this@SessionBuilder.sessions.insertDrives(sessions.map { it.toRow() })
        }
    }
}
