// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind
import kotlin.math.abs

/**
 * One stretch the car sat parked: the odometer unchanged, out of gear and not charging from the first read
 * at [startMillis] to the last at [endMillis], however far apart the reads were. [dropPercent] is what the battery lost.
 */
data class ParkedSpan(
    val startMillis: Long,
    val endMillis: Long,
    val dropPercent: Double,
) {
    val durationMillis: Long get() = endMillis - startMillis

    /** The drain over the stretch, percentage points lost per hour. */
    val percentPerHour: Double get() = dropPercent * HOUR_MILLIS / durationMillis
}

/**
 * The longest stretch of consecutive parked pairs of battery reads: holes in the reads count, since the
 * odometer says the car did not move and [chargedBetween] says it was not plugged in meanwhile. Ties go
 * to the later stretch.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param scale the fitted rated-range scale that refines the drop; null reads the integer levels.
 */
fun longestParked(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    scale: FullRangeScale? = null,
): ParkedSpan? = longestRun(samples, drives, scale) { _, _ -> true }

/**
 * The longest stretch of consecutive pairs that were parked and idle: nobody in the car and neither Sentry nor the
 * climate running in any of them ([idleBetween]), awake or asleep. This is the power down the parked-drain projection follows. Ties go
 * to the later stretch.
 *
 * @param context the fitted scale, the vehicle's status log and its Sentry and climate readings.
 * @see longestParked for the other parameters.
 */
fun longestPowerDown(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    context: ProjectionContext,
): ParkedSpan? = longestRun(samples, drives, context.scale) { a, b -> idleBetween(a, b, context) }

/** The longest run of consecutive parked pairs of [samples] that [also] accepts. */
private fun longestRun(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    scale: FullRangeScale?,
    also: (BatterySample, BatterySample) -> Boolean,
): ParkedSpan? {
    val readings = driveReadingsAt(samples, drives.filter { it.odometerInHundredthsOfAMile != null })
    var longest: ParkedSpan? = null
    var first = 0
    for (index in 1..samples.size) {
        val continues =
            index < samples.size &&
                parkedBetween(samples[index - 1], samples[index], readings[index - 1], readings[index]) &&
                also(samples[index - 1], samples[index])
        if (continues) continue
        val span = parkedSpan(samples, first, index - 1, scale)
        if (span != null && span.durationMillis >= (longest?.durationMillis ?: 1L)) longest = span
        first = index
    }
    return longest
}

private fun parkedSpan(
    samples: List<BatterySample>,
    first: Int,
    last: Int,
    scale: FullRangeScale?,
): ParkedSpan? {
    if (last <= first) return null
    val a = samples[first]
    val b = samples[last]
    return ParkedSpan(readAt(a), readAt(b), (a.soc(scale) - b.soc(scale)).toDouble())
}

/**
 * True when the car stayed parked between two consecutive reads, and the pair can be trusted: it did not move,
 * was not charged in between, and the level at the first read was the usable level. While the car holds part of
 * a cold battery back, the two differ and its levels and ranges are temperature-adjusted (vehicle-command#526),
 * so a drain measured from such a read would mix weather into battery loss.
 */
internal fun parkedBetween(
    a: BatterySample,
    b: BatterySample,
    readingA: DriveSample?,
    readingB: DriveSample?,
): Boolean = a.usableLevel == a.batteryLevel && stayedParked(readingA, readingB) && !chargedBetween(a, b)

/**
 * True when the car was plugged in between two reads. A charge running at [a] counts. At [b] it counts unless
 * the session has only just begun (the energy added is still near zero: the owner came to the car and plugged
 * in, so the stretch before it was parked). With no charge showing at [b], a changed energy-added figure that
 * is not near zero, or a level that rose by more than rounding, says a session ran unseen in the hole. The
 * exception is a car still plugged in and Complete at [a]: its figure creeps down as the battery drains (47.62 to
 * 47.22 kWh over nine hours at 100%), so only a rise or a fall past [ENERGY_DRIFT_KWH] counts there.
 */
internal fun chargedBetween(
    a: BatterySample,
    b: BatterySample,
): Boolean {
    val before = a.chargeEnergyAdded
    val after = b.chargeEnergyAdded
    val begun = after != null && after <= NEAR_ZERO_KWH
    return when {
        a.isCharging -> true
        b.isCharging -> !begun
        else ->
            b.batteryLevel - a.batteryLevel >= LEVEL_RISE_POINTS ||
                (before != null && after != null && !begun && energyMoved(before, after, a.chargingState == ChargingStateKind.Complete))
    }
}

/** Any change in the energy added is a session; for a [complete] car a fall counts only past the drift its figure shows. */
private fun energyMoved(
    before: Float,
    after: Float,
    complete: Boolean,
): Boolean =
    if (complete) {
        after - before > ENERGY_EPSILON_KWH || before - after > ENERGY_DRIFT_KWH
    } else {
        abs(after - before) > ENERGY_EPSILON_KWH
    }

/**
 * True when nobody was in the car and nothing kept it busy between two reads: the status log never says someone
 * was present, and no Sentry or climate reading says it was running. The logs write a reading only on a change, as
 * a heartbeat, or while the car is awake and read, so a short gap usually holds none: the reading carried in, the
 * last one at or before the first read, stands for it while it is no older than [STATUS_CHANGE_MAX_GAP_MILLIS]. A
 * signal with no reading to go on (the phone was away) is not held against the gap. Whether the car is locked does
 * not matter: only a person in it and what draws power do.
 *
 * @param context the vehicle's status log and its Sentry and climate readings, oldest first.
 */
internal fun idleBetween(
    a: BatterySample,
    b: BatterySample,
    context: ProjectionContext,
): Boolean {
    val from = readAt(a)
    val to = readAt(b)
    return !busyBetween(context.statuses, from, to, StatusSample::timestampMillis) { it.userPresent } &&
        !busyBetween(context.sentryReadings, from, to, ActivitySample::timestampMillis) { it.on } &&
        !busyBetween(context.climateReadings, from, to, ActivitySample::timestampMillis) { it.on }
}

/** True when nobody is in the car and neither Sentry nor the climate is running at [nowMillis], by the readings carried in. */
internal fun idleAt(
    nowMillis: Long,
    context: ProjectionContext,
): Boolean =
    !busyBetween(context.statuses, nowMillis, nowMillis, StatusSample::timestampMillis) { it.userPresent } &&
        !busyBetween(context.sentryReadings, nowMillis, nowMillis, ActivitySample::timestampMillis) { it.on } &&
        !busyBetween(context.climateReadings, nowMillis, nowMillis, ActivitySample::timestampMillis) { it.on }

/** True when [busy] holds for the reading carried in at [from] or for any reading strictly inside the gap to [to]. */
private fun <T> busyBetween(
    readings: List<T>,
    from: Long,
    to: Long,
    millis: (T) -> Long,
    busy: (T) -> Boolean,
): Boolean {
    var low = 0
    var high = readings.size
    while (low < high) {
        val middle = (low + high) / 2
        if (millis(readings[middle]) > from) high = middle else low = middle + 1
    }
    val carried = readings.getOrNull(low - 1)?.takeIf { from - millis(it) <= STATUS_CHANGE_MAX_GAP_MILLIS }
    var index = low
    while (index < readings.size && millis(readings[index]) < to) index++
    return listOfNotNull(carried).plus(readings.subList(low, index)).any(busy)
}

/**
 * The parked pair of reads at least [MIN_LOWEST_DRAIN_MILLIS] apart that lost the battery's level slowest: the
 * best the car did between two readings, however long it sat. A level that rose or held counts as no loss, and
 * of equal rates the longer pair wins.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param scale the fitted rated-range scale that refines the drops; null reads the integer levels.
 */
fun lowestDrain(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    scale: FullRangeScale? = null,
): ParkedSpan? {
    val readings = driveReadingsAt(samples, drives.filter { it.odometerInHundredthsOfAMile != null })
    return (1 until samples.size)
        .filter { parkedBetween(samples[it - 1], samples[it], readings[it - 1], readings[it]) }
        .mapNotNull { parkedSpan(samples, it - 1, it, scale) }
        .filter { it.durationMillis >= MIN_LOWEST_DRAIN_MILLIS }
        .minWithOrNull(compareBy({ it.percentPerHour.coerceAtLeast(0.0) }, { -it.durationMillis }))
}

/** Two reads closer than this say too little about a drain: a rounding step looks like a rate. */
private const val MIN_LOWEST_DRAIN_MILLIS = 6 * 60 * 60_000L

/** A charge session this young at a read has only just begun: the energy added is still near zero. */
private const val NEAR_ZERO_KWH = 0.1f

/** A level up by this many points while not charging is a session, not a rounding blip. */
private const val LEVEL_RISE_POINTS = 2

/** Energy-added figures closer than this are the same reading. */
private const val ENERGY_EPSILON_KWH = 0.01f

/**
 * A Complete car's energy-added figure may fall this far as the battery drains (0.4 kWh in nine hours seen); about 3%
 * of the pack, the scale of [LEVEL_RISE_POINTS]. A bigger fall is the figure resetting for a new session.
 */
private const val ENERGY_DRIFT_KWH = 2f

private const val HOUR_MILLIS = 60 * 60_000L
