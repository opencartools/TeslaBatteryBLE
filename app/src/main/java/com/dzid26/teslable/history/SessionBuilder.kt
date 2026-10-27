// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.health.chargeSessions
import com.dzid26.teslable.core.history.SESSION_CUT_LEAD_MILLIS
import com.dzid26.teslable.core.history.driveSessions

/**
 * Cuts the charge and drive sessions of `derived.db` from its stored samples (ADR-0014), with the same core functions
 * the screen uses. The sample builder marks, per car, the oldest sample time it stored since the last cut; this
 * re-cuts from the start of the oldest session that reaches that time (or from the time itself), replacing every
 * stored session from there in one transaction. Re-cutting is idempotent, so late replies and re-runs are safe, and
 * since it reads `derived.db`'s own samples, sessions survive the raw records they came from. Levels are stored as
 * the car reported them; readers apply the current full-range scale. Blocking; call it off the main thread.
 */
internal class SessionBuilder(
    private val db: DerivedDatabase,
) {
    private val sessions = db.sessions()
    private val reads = db.reads()

    /** Re-cuts every car with new samples; returns how many cars it re-cut. */
    fun build(): Int =
        sessions.dirty().count { mark ->
            db.runInTransaction {
                recut(mark.vehicleId, mark.fromTime)
                sessions.clearDirty(mark.vehicleId, mark.fromTime)
            }
            true
        }

    private fun recut(
        vehicleId: String,
        from: Long,
    ) {
        val reach = from - SESSION_CUT_LEAD_MILLIS
        val chargesFrom = minOf(from, sessions.chargeStartEndingAfter(vehicleId, reach) ?: from)
        val drivesFrom = minOf(from, sessions.driveStartEndingAfter(vehicleId, reach) ?: from)
        val readFrom = minOf(chargesFrom, drivesFrom) - SESSION_CUT_LEAD_MILLIS
        val battery = reads.batteryFrom(vehicleId, readFrom).map { it.toSample() }
        val drives = reads.driveFrom(vehicleId, readFrom).map { it.toSample() }
        sessions.deleteChargesFrom(vehicleId, chargesFrom)
        sessions.insertCharges(chargeSessions(battery).filter { it.startMillis >= chargesFrom }.map { it.toRow() })
        sessions.deleteDrivesFrom(vehicleId, drivesFrom)
        sessions.insertDrives(driveSessions(drives, battery).filter { it.startMillis >= drivesFrom }.map { it.toRow() })
    }
}
