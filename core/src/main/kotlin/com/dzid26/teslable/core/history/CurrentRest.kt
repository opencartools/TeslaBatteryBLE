// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.health.chargeSessionStart

/**
 * What the car is doing now: [CHARGING] or [DRIVING] (in gear), or resting: [PARKED] sitting still, or
 * [POWERED_DOWN] parked and left alone long enough to count as a power down.
 */
enum class RestKind { CHARGING, DRIVING, PARKED, POWERED_DOWN }

/** The car's state now and when it began: the start of the stretch that leads up to it. */
data class CurrentRest(
    val kind: RestKind,
    val sinceMillis: Long,
)

/**
 * The car's state now, or null when it is not known: nothing heard from it for longer than a status heartbeat
 * ([STATUS_CHANGE_MAX_GAP_MILLIS]) before [nowMillis], or no drive reading as new as the newest battery read to say
 * whether it moved. It is [RestKind.CHARGING] when the newest read is charging, since the charge session began
 * (a short pause does not restart it), and [RestKind.DRIVING] when the newest drive reading is in gear, since the
 * unbroken run of reads in gear began.
 *
 * Otherwise it rests, by the rules the stats use. The newest drive reading must show the car at the odometer the
 * stretch holds; a drive after it, unseen, is not known. It is parked when the odometer has not moved since
 * [CurrentRest.sinceMillis] (the first read of the unbroken run of [parkedBetween] pairs that ends at the newest read,
 * holes included). It is powered down when, on top of that, nobody is in it and Sentry and the climate are off now
 * ([idleAt]), and have been for at least an hour (the shortest pair that counts as a power down): a car that has just
 * been left is still awake.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param context the vehicle's status log and its Sentry and climate readings.
 */
fun currentRest(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    nowMillis: Long,
    context: ProjectionContext = ProjectionContext(),
): CurrentRest? {
    val last = samples.lastOrNull()
    val stale = nowMillis - heardFromAt(samples, context) > STATUS_CHANGE_MAX_GAP_MILLIS
    if (last == null || stale) return null
    val withOdometer = drives.filter { it.odometerInHundredthsOfAMile != null }
    val reads = Reads(samples, driveReadingsAt(samples, withOdometer), newestDriveAsNewAs(last, withOdometer))
    return activeNow(reads, nowMillis) ?: restNow(reads, nowMillis, context)
}

/** The battery reads, the drive reading nearest each, and the newest drive reading when it is as new as the newest read. */
private class Reads(
    val samples: List<BatterySample>,
    val drives: List<DriveSample?>,
    val newestDrive: DriveSample?,
) {
    val last: BatterySample get() = samples.last()
}

/** The newest drive reading, when it is about as new as [last]; an older one says nothing of the car now. */
private fun newestDriveAsNewAs(
    last: BatterySample,
    drives: List<DriveSample>,
): DriveSample? = drives.lastOrNull()?.takeIf { it.timestampMillis >= last.timestampMillis - DRIVE_MATCH_MILLIS }

private val DriveSample?.inGearNow: Boolean get() = this?.inGear == true

/** Charging or driving now, with the start of what leads up to it; null when the car is doing neither. */
private fun activeNow(
    reads: Reads,
    nowMillis: Long,
): CurrentRest? =
    when {
        reads.last.isCharging -> chargeSessionStart(reads.samples, nowMillis)?.let { CurrentRest(RestKind.CHARGING, it) }
        reads.newestDrive.inGearNow -> CurrentRest(RestKind.DRIVING, readAt(reads.samples[drivingStart(reads)]))
        else -> null
    }

/** The index of the first read of the unbroken run of reads in gear that ends at the newest one. */
private fun drivingStart(reads: Reads): Int = trailingRun(reads.samples.lastIndex, floor = 0) { reads.drives[it - 1].inGearNow }

/** Parked or powered down now, or null when the newest drive readings do not settle where the car is. */
private fun restNow(
    reads: Reads,
    nowMillis: Long,
    context: ProjectionContext,
): CurrentRest? {
    if (!settled(reads)) return null
    val samples = reads.samples
    val first =
        trailingRun(samples.lastIndex, floor = 0) { parkedBetween(samples[it - 1], samples[it], reads.drives[it - 1], reads.drives[it]) }
    val idleFirst =
        if (idleAt(nowMillis, context)) {
            trailingRun(samples.lastIndex, floor = first) { idleBetween(samples[it - 1], samples[it], context) }
        } else {
            null
        }
    val poweredDownSince = idleFirst?.let { readAt(samples[it]) }?.takeIf { nowMillis - it >= MIN_POWER_DOWN_GAP_MILLIS }
    return poweredDownSince?.let { CurrentRest(RestKind.POWERED_DOWN, it) } ?: CurrentRest(RestKind.PARKED, readAt(samples[first]))
}

/** The newest moment anything was heard from the car: its newest battery read or status reading. */
private fun heardFromAt(
    samples: List<BatterySample>,
    context: ProjectionContext,
): Long = maxOf(samples.lastOrNull()?.let { readAt(it) } ?: 0L, context.statuses.lastOrNull()?.timestampMillis ?: 0L)

/** True when the drive reading at the newest read and the newest drive reading are both out of gear, at one odometer. */
private fun settled(reads: Reads): Boolean {
    val atLast = reads.drives.lastOrNull()
    val newest = reads.newestDrive
    return atLast?.inGear == false && newest?.inGear == false && newest.odometerInHundredthsOfAMile == atLast.odometerInHundredthsOfAMile
}

/** The index the unbroken run of indexes ending at [from] reaches back to, stopping above [floor], while [accept] says so. */
private fun trailingRun(
    from: Int,
    floor: Int,
    accept: (Int) -> Boolean,
): Int {
    var index = from
    while (index > floor && accept(index)) index--
    return index
}
