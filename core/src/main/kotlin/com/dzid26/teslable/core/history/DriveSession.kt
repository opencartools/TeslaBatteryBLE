// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.math.abs

/**
 * One drive: from the first reading in gear to the last, with stops shorter than [DRIVE_JOIN_MILLIS] (a light, a
 * drop-off) inside it. Every drive counts, navigated or not; a navigation (`Trip`) attaches by time overlap. When the
 * phone saw no reading in gear but the odometer moved between two parked readings, the drive is [unseen] and spans
 * those two readings.
 */
data class DriveSession(
    override val startMillis: Long,
    override val endMillis: Long,
    val vehicleId: String,
    /** The odometer when the car set off and when it stopped, hundredths of a mile. */
    val startOdometer: Int,
    val endOdometer: Int,
    override val socStartPercent: Float? = null,
    override val socEndPercent: Float? = null,
    /** The rated range at the start and at the end, miles; null without a battery reading close enough. */
    val startRangeMiles: Float? = null,
    val endRangeMiles: Float? = null,
    val unseen: Boolean = false,
    override val inProgress: Boolean = false,
) : Session {
    override val id: String get() = "drive-$vehicleId-$startMillis"

    /** Not measured yet; the drive chart's estimate takes over with the trip cost work. */
    override val energyKwh: Float? get() = null
    override val energyIsEstimate: Boolean get() = true

    val distanceMiles: Double get() = (endOdometer - startOdometer) / HUNDREDTHS_PER_MILE
}

/**
 * Cuts one car's [drives] (oldest first) into [DriveSession]s, the readings without an odometer left out. A drive
 * starts at a reading in gear and ends at the last reading in gear before a stop of [DRIVE_JOIN_MILLIS] or more; its
 * odometer runs from the parked reading just before it (when the phone saw the car set off) to the parked reading
 * just after it. Between two parked readings outside a drive, an odometer change of at least [MIN_UNSEEN_HUNDREDTHS]
 * is an unseen drive. [battery] (the same car, oldest first) gives each end its level and rated range, from the
 * parked battery reading just before the start and just after the end, with [scale] for sub-percent levels.
 */
fun driveSessions(
    drives: List<DriveSample>,
    battery: List<BatterySample> = emptyList(),
    scale: FullRangeScale? = null,
): List<DriveSession> {
    val cutter = DriveCutter()
    for (sample in drives) sample.odometerInHundredthsOfAMile?.let { cutter.add(Reading(sample, it)) }
    return cutter.cuts.map { it.toSession(battery, scale) }
}

/**
 * [driveSessions], with the newest marked in progress when the car was in gear at its newest reading and that
 * reading is younger than [DRIVE_JOIN_MILLIS] at [nowMillis].
 */
fun driveSessions(
    drives: List<DriveSample>,
    battery: List<BatterySample>,
    scale: FullRangeScale?,
    nowMillis: Long,
): List<DriveSession> {
    val sessions = driveSessions(drives, battery, scale)
    val newest = drives.lastOrNull { it.odometerInHundredthsOfAMile != null }
    val going = newest != null && newest.inGear && nowMillis - newest.timestampMillis < DRIVE_JOIN_MILLIS
    return if (going && sessions.isNotEmpty()) sessions.dropLast(1) + sessions.last().copy(inProgress = true) else sessions
}

/** A drive reading with its odometer, hundredths of a mile. */
private class Reading(
    val sample: DriveSample,
    val odometer: Int,
) {
    val millis: Long get() = sample.timestampMillis
    val inGear: Boolean get() = sample.inGear
}

/** Walks the readings oldest first, opening, extending and closing [cuts]. */
private class DriveCutter {
    val cuts = mutableListOf<Cut>()
    private var open: Cut? = null
    private var previous: Reading? = null

    fun add(reading: Reading) {
        val current = open
        val sinceInGear = current?.let { reading.millis - it.lastInGear.millis }
        when {
            current != null && reading.inGear && joins(reading.millis - current.lastInGear.millis) -> {
                current.lastInGear = reading
                current.after = null
            }
            reading.inGear -> open = start(reading)
            current != null -> if (current.after == null) current.after = reading
            else -> unseen(reading)
        }
        if (!reading.inGear && sinceInGear != null && sinceInGear >= DRIVE_JOIN_MILLIS) open = null
        previous = reading
    }

    /**
     * A stop shorter than the join time stays in the drive; so does a hole in the reads while in gear, up to the
     * drive chart's trip gap, since nothing says the car parked.
     */
    private fun joins(sinceInGear: Long): Boolean =
        sinceInGear < DRIVE_JOIN_MILLIS || previous?.inGear == true && sinceInGear < IN_GEAR_HOLE_MILLIS

    private fun start(reading: Reading): Cut {
        val before = previous?.takeIf { !it.inGear && reading.millis - it.millis < DRIVE_JOIN_MILLIS }
        return Cut(first = reading, lastInGear = reading, before = before).also { cuts += it }
    }

    private fun unseen(reading: Reading) {
        val parked = previous?.takeIf { !it.inGear } ?: return
        if (reading.odometer - parked.odometer >= MIN_UNSEEN_HUNDREDTHS) {
            cuts += Cut(parked, parked, before = null, after = reading, unseen = true)
        }
    }
}

private class Cut(
    val first: Reading,
    var lastInGear: Reading,
    val before: Reading?,
    var after: Reading? = null,
    val unseen: Boolean = false,
) {
    fun toSession(
        battery: List<BatterySample>,
        scale: FullRangeScale?,
    ): DriveSession {
        val startReading = if (unseen) first else before ?: first
        val endReading = after ?: lastInGear
        val startMillis = first.millis
        val endMillis = if (unseen) endReading.millis else lastInGear.millis
        val atStart = parkedRead(battery, startMillis, before = true)
        val atEnd = parkedRead(battery, endMillis, before = false)
        return DriveSession(
            startMillis = startMillis,
            endMillis = endMillis,
            vehicleId = first.sample.vehicleId,
            startOdometer = startReading.odometer,
            endOdometer = endReading.odometer,
            socStartPercent = atStart?.soc(scale),
            socEndPercent = atEnd?.soc(scale),
            startRangeMiles = atStart?.ratedRangeMiles,
            endRangeMiles = atEnd?.ratedRangeMiles,
            unseen = unseen,
        )
    }
}

/**
 * The battery reading that stands for the parked car on one side of [millis]: the newest one at or before it when
 * [before], else the oldest at or after it, within [PARKED_READ_MILLIS]; failing that, the nearest one on the other
 * side within [DRIVE_MATCH_MILLIS]. A parked car's level and range hold still, so this side gives the level the drive
 * started or ended at, whichever readings the history kept. [battery] is oldest first.
 */
private fun parkedRead(
    battery: List<BatterySample>,
    millis: Long,
    before: Boolean,
): BatterySample? {
    var low = 0
    var high = battery.size
    while (low < high) {
        val middle = (low + high) / 2
        if (battery[middle].timestampMillis < millis) low = middle + 1 else high = middle
    }
    // battery[low] is the first reading at or after millis; the one before it is the last before.
    val atOrAfter = battery.getOrNull(low)
    val atOrBefore = if (atOrAfter?.timestampMillis == millis) atOrAfter else battery.getOrNull(low - 1)
    val near = if (before) atOrBefore else atOrAfter
    val far = if (before) atOrAfter else atOrBefore
    return near?.takeIf { abs(it.timestampMillis - millis) <= PARKED_READ_MILLIS }
        ?: far?.takeIf { abs(it.timestampMillis - millis) <= DRIVE_MATCH_MILLIS }
}

/** How far from a drive's end the parked reading on that side may be: two of the history's parked steps. */
private const val PARKED_READ_MILLIS = 10 * 60_000L

/** A stop shorter than this (a light, a drop-off) stays inside the drive. */
const val DRIVE_JOIN_MILLIS = 10 * 60_000L

/** A hole in the reads this long while in gear still joins the readings around it; the drive chart's trip gap. */
private const val IN_GEAR_HOLE_MILLIS = 30 * 60_000L

/** An odometer change between two parked readings this large (0.1 mi) is a drive the phone did not see. */
private const val MIN_UNSEEN_HUNDREDTHS = 10

private const val HUNDREDTHS_PER_MILE = 100.0
