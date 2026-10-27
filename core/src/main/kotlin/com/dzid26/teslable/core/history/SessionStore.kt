// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.chargeSessions

/**
 * One car's stored sessions and kept samples, as [recutSessions] reads and replaces them. `derived.db` is one; a
 * list-backed one lets a replay drive the same re-cut over real logs.
 */
interface SessionStore {
    /** The start of the newest stored charge session that starts before [millis]; null when none does. */
    fun newestChargeStartBefore(millis: Long): Long?

    /** The start of the newest stored drive session that starts before [millis]; null when none does. */
    fun newestDriveStartBefore(millis: Long): Long?

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
 * the cut of all the kept samples. New samples can only extend the newest session that starts before them (a charge
 * joins across a hole in the reads of any length, a drive across a short stop), so the re-cut starts at that
 * session's start, whatever the time since. With no drive stored before them, the first new sample may still end an
 * unseen drive that starts at the parked reading before it, however old, so the drives start there. Each drive cut
 * also reads the reading before its first (the odometer when the car set off), and its end levels the battery reads
 * up to [SESSION_CUT_LEAD_MILLIS] around it. Re-cutting the same samples again gives the same sessions.
 */
fun recutSessions(
    store: SessionStore,
    fromMillis: Long,
) {
    val chargesFrom = store.newestChargeStartBefore(fromMillis) ?: fromMillis
    val drivesFrom = store.newestDriveStartBefore(fromMillis) ?: store.driveReadingBefore(fromMillis) ?: fromMillis
    val battery = store.batteryFrom(minOf(chargesFrom, drivesFrom) - SESSION_CUT_LEAD_MILLIS)
    val drives = store.drivesFrom(store.driveReadingBefore(drivesFrom) ?: drivesFrom)
    store.replaceCharges(chargesFrom, chargeSessions(battery).filter { it.startMillis >= chargesFrom })
    store.replaceDrives(drivesFrom, driveSessions(drives, battery).filter { it.startMillis >= drivesFrom })
}
