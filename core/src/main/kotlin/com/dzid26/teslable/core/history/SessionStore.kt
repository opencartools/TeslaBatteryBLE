// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.chargeRunEndedBy
import com.dzid26.teslable.core.health.chargeSessions

/** When a stored session starts and ends, millis: what a re-cut needs to know of it. */
data class StoredSpan(
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * One car's stored sessions and kept samples, as [recutSessions] reads and replaces them. `derived.db` is one; a
 * list-backed one lets a replay drive the same re-cut over real logs.
 */
interface SessionStore {
    /** The newest stored charge session that starts before [millis]; null when none does. */
    fun newestChargeBefore(millis: Long): StoredSpan?

    /** The newest stored drive session that starts before [millis]; null when none does. */
    fun newestDriveBefore(millis: Long): StoredSpan?

    /** The newest kept battery sample before [millis] that is neither charging nor starting to; null when none is. */
    fun chargeBreakBefore(millis: Long): BatterySample?

    /** The time of the newest kept drive sample with an odometer before [millis]; null when there is none. */
    fun driveReadingBefore(millis: Long): Long?

    /** The kept battery samples from [millis] on, oldest first. */
    fun batteryFrom(millis: Long): List<BatterySample>

    /** The kept drive samples from [millis] on, oldest first. */
    fun drivesFrom(millis: Long): List<DriveSample>

    /** Replaces the stored charge sessions that start at or after [millis] with [sessions]. */
    fun replaceCharges(
        millis: Long,
        sessions: List<ChargeSession>,
    )

    /** Replaces the stored drive sessions that start at or after [millis] with [sessions]. */
    fun replaceDrives(
        millis: Long,
        sessions: List<DriveSession>,
    )
}

/**
 * Cuts again the sessions of [store] that samples kept from [fromMillis] on can change, so the stored sessions stay
 * the cut of all the kept samples. New samples can only extend the newest session that starts before them: a charge
 * joins across a hole in the reads of any length, a drive across a short stop. So the re-cut starts at that session's
 * start, unless a sample since already closed it, and then at that sample, so a build reads only back to the last
 * time a session could still grow:
 * - a charge is closed by a later sample that ends the run ([chargeRunEndedBy]);
 * - a drive is closed by a parked reading long enough after it ended ([driveClosedAfter]). With none stored, the first new
 *   sample may still end an unseen drive that starts at the parked reading before it, however old, so the drives
 *   start there.
 *
 * A drive re-cut from its own start also reads the reading before it (the odometer when the car set off), and the
 * drives' end levels the battery reads up to [SESSION_CUT_LEAD_MILLIS] around them. Re-cutting the same samples again
 * gives the same sessions.
 */
fun recutSessions(
    store: SessionStore,
    fromMillis: Long,
) {
    val chargesFrom = chargesFrom(store, fromMillis)
    val lastDriveReading = store.driveReadingBefore(fromMillis)
    val drive = store.newestDriveBefore(fromMillis)
    val driveOpen = drive != null && (lastDriveReading == null || !driveClosedAfter(lastDriveReading - drive.endMillis))
    val drivesFrom = if (driveOpen) drive.startMillis else lastDriveReading ?: fromMillis
    val drivesRead = if (driveOpen) store.driveReadingBefore(drivesFrom) ?: drivesFrom else drivesFrom
    val battery = store.batteryFrom(minOf(chargesFrom, drivesFrom) - SESSION_CUT_LEAD_MILLIS)
    val drives = store.drivesFrom(drivesRead)
    store.replaceCharges(chargesFrom, chargeSessions(battery).filter { it.startMillis >= chargesFrom })
    store.replaceDrives(drivesFrom, driveSessions(drives, battery).filter { it.startMillis >= drivesFrom })
}

/** Where the charges are re-cut: the newest stored charge's start while new samples can still extend it. */
private fun chargesFrom(
    store: SessionStore,
    fromMillis: Long,
): Long {
    val charge = store.newestChargeBefore(fromMillis) ?: return fromMillis
    val closing =
        store.chargeBreakBefore(fromMillis)?.takeIf {
            it.timestampMillis > charge.endMillis && chargeRunEndedBy(it.chargingState, fromMillis - charge.endMillis)
        }
    return closing?.timestampMillis ?: charge.startMillis
}
