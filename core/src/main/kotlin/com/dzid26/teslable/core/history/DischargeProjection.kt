// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where a parked car's battery is heading at its parked drain rate over the range. */
data class DischargeProjection(
    /** The latest sample, where the projected line starts. */
    val from: BatterySample,
    /** Percent projected one horizon after the last sample. */
    val projectedPercent: Int,
    /** Wall-clock time the projection refers to. */
    val projectedAtMillis: Long,
    /** How far ahead the projection looks: half of the selected range. */
    val horizonMillis: Long,
    /** The parked drain rate the line follows, in percentage points lost per hour. */
    val percentPerHour: Double,
    /** The level the line starts at: sub-percent from the fitted scale when it is pinned. */
    val fromSoc: Float = from.batteryLevel.toFloat(),
    /** The unrounded level one horizon ahead; [projectedPercent] is this, rounded. */
    val projectedSoc: Float = projectedPercent.toFloat(),
    /** The lower edge of the beam at the horizon: two standard errors of the rate; null when the fit is too thin. */
    val lowSoc: Float? = null,
    /** The upper edge of the beam at the horizon; null like [lowSoc]. */
    val highSoc: Float? = null,
    /** The mixed rate (asleep and awake pairs together), percent per hour, when [percentPerHour] is the deep-sleep rate; else null. */
    val mixedPercentPerHour: Double? = null,
) {
    /** The beam's two edges together; null when the fit is too thin for one. */
    val beam: Pair<Float, Float>?
        get() {
            val low = lowSoc ?: return null
            val high = highSoc ?: return null
            return low to high
        }
}

/**
 * Projects the parked drain: the drain rate of the parked stretches in the
 * selected range is carried half the range forward, matching the chart's
 * expanded x-axis.
 *
 * Only parked pairs of consecutive battery samples feed the rate. A pair is
 * parked when neither sample is charging, neither is in gear (R, N or D: a
 * car stopped at a light keeps its odometer but is mid-drive), and the
 * odometer did not move between them. Each sample's drive reading is the
 * [DriveSample] nearest to it in time, within [DRIVE_MATCH_MILLIS] (a drive
 * read follows every charge read). A sample with no such reading is not known to be parked, so
 * its pairs are left out. A long gap with no readings still counts when the
 * odometer is unchanged across it: the overnight drop up to the wake-up
 * reading is exactly the drain this projects, while a drive no phone saw
 * shows up as an odometer change and is left out.
 *
 * The rate is the summed signed change over the summed duration of the
 * parked pairs, so a rounding blip that drops and recovers while parked
 * cancels out instead of inflating the drop. The change is read on the rated
 * range through the fitted [scale] when it is pinned, so a pair of readings
 * is a fraction of a percent, not a whole level; without it the integer
 * levels stand in. The beam is two standard errors of that rate: the spread
 * of the pairs around it and the rounding of every pair's readings, in
 * quadrature, joined with the scale's own slope error.
 *
 * The line follows the deep-sleep rate once the car has slept enough to tell: the
 * rate of the pairs it slept through (a VCSEC status inside the gap says asleep
 * and none says awake). The mixed rate (every parked pair, asleep or awake) is
 * reported beside it, and the beam's lower edge reaches the mixed line, so the
 * beam spans deep sleep to what the battery really did. Without enough sleep
 * the line is the mixed rate.
 *
 * While charging it still projects: the line shows where the battery would
 * head if unplugged now, next to the charge projection. Returns null once the
 * whole projection lies in the past (the last sample is older than the
 * horizon), when the range holds less than [MIN_RATE_SPAN_MILLIS] of parked
 * time, or when the summed drop is not positive: a rising or flat line would
 * make the rate meaningless.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param rangeMillis the selected range: its samples feed the rate; [projectionHorizonMillis] says how far ahead it looks.
 * @param context the fitted scale and the status log that refine the rate.
 */
fun dischargeProjection(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    rangeMillis: Long,
    nowMillis: Long,
    context: ProjectionContext = ProjectionContext(),
): DischargeProjection? {
    val scale = context.scale
    val last = samples.lastOrNull() ?: return null
    val horizon = projectionHorizonMillis(rangeMillis)
    if (nowMillis - last.timestampMillis > horizon) return null

    val windowed = samples.filter { it.timestampMillis >= last.timestampMillis - rangeMillis }
    val parked = parkedDrain(windowed, drives.filter { it.odometerInHundredthsOfAMile != null }, context)
    if (parked.millis < MIN_RATE_SPAN_MILLIS || parked.drop <= 0.0) return null

    val fromSoc = last.soc(scale)
    val mixedRate = parked.drop / parked.millis
    // The line follows the deep-sleep rate when the car has slept enough to tell, else the mixed one.
    val sleepRate = parked.sleepRatePerMilli?.takeIf { it < mixedRate }
    val mainRate = sleepRate ?: mixedRate
    val projectedSoc = (fromSoc - mainRate * horizon).toFloat().coerceIn(0f, FULL_PERCENT)
    val mixedSoc = (fromSoc - mixedRate * horizon).toFloat().coerceIn(0f, FULL_PERCENT)
    val halfWidth = 2.0 * hypot(parked.errorPerMilli, mainRate * (scale.levelScaleError() ?: 0.0)) * horizon
    val hasStats = parked.pairs >= MIN_BEAM_PAIRS
    val hasBeam = hasStats || sleepRate != null
    // The beam: the statistics around the line, reaching down to the mixed line when the main one is the sleep rate.
    val low = if (hasStats) minOf((projectedSoc - halfWidth).toFloat(), mixedSoc) else mixedSoc
    val high = if (hasStats) (projectedSoc + halfWidth).toFloat() else projectedSoc
    return DischargeProjection(
        from = last,
        projectedPercent = projectedSoc.roundToInt(),
        projectedAtMillis = last.timestampMillis + horizon,
        horizonMillis = horizon,
        percentPerHour = mainRate * HOUR,
        fromSoc = fromSoc,
        projectedSoc = projectedSoc,
        lowSoc = if (hasBeam) low.coerceIn(0f, FULL_PERCENT) else null,
        highSoc = if (hasBeam) high.coerceIn(0f, minOf(fromSoc, FULL_PERCENT)) else null,
        mixedPercentPerHour = if (sleepRate != null) mixedRate * HOUR else null,
    )
}

/**
 * What refines the parked rate beyond the raw samples: the fitted rated-range
 * [scale] (null reads the integer level), the vehicle's VCSEC [statuses], oldest first,
 * which say when the car slept and when someone was in it, and its Sentry and climate
 * [activity] readings, oldest first, which say when it was kept busy.
 */
data class ProjectionContext(
    val scale: FullRangeScale? = null,
    val statuses: List<StatusSample> = emptyList(),
    val activity: List<ActivitySample> = emptyList(),
) {
    /** The Sentry readings of [activity], split once, when first read, for the searches that use them. */
    internal val sentryReadings: List<ActivitySample> by lazy { activity.filter { it.kind == ActivityKind.SENTRY } }

    /** The climate readings of [activity]. */
    internal val climateReadings: List<ActivitySample> by lazy { activity.filter { it.kind == ActivityKind.CLIMATE } }
}

/** The drop (percent) and duration of the pairs the car slept through. */
private class SleepSpan(
    val drop: Double,
    val millis: Long,
    val pairs: Int,
)

/** The summed signed drop (percent) and duration of the parked pairs, with their spread. */
private class ParkedDrain(
    val drop: Double,
    val millis: Long,
    val pairs: Int,
    /** Sum of each pair's squared residual from the mean rate. */
    val squaredResiduals: Double,
    /** The rounding variance of the summed drop: two readings for every pair. */
    val roundingVariance: Double = 0.0,
    /** The drop and duration of the pairs the car slept through. */
    val sleep: SleepSpan = SleepSpan(0.0, 0L, 0),
) {
    /** The drain rate of the slept-through pairs, percent per millisecond; null with too little sleep. */
    val sleepRatePerMilli: Double? get() =
        if (sleep.pairs >= MIN_SLEEP_PAIRS &&
            sleep.millis >= MIN_RATE_SPAN_MILLIS
        ) {
            sleep.drop / sleep.millis
        } else {
            null
        }

    /**
     * One standard error of the rate, percent per millisecond: the pairs' scatter
     * (sample variance, over n - 1) and the rounding of every pair's two readings,
     * in quadrature.
     */
    val errorPerMilli: Double
        get() = sqrt(squaredResiduals * pairs / maxOf(pairs - 1, 1) + roundingVariance) / millis
}

/** Sums the parked pairs of [samples]; [drives] all carry an odometer and are oldest first. */
private fun parkedDrain(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    context: ProjectionContext,
): ParkedDrain {
    val scale = context.scale
    val drops = mutableListOf<Double>()
    val spans = mutableListOf<Long>()
    val quiet = mutableListOf<Boolean>()
    val readings = driveReadingsAt(samples, drives)
    for (index in 1 until samples.size) {
        val a = samples[index - 1]
        val b = samples[index]
        val readingA = readings[index - 1]
        val readingB = readings[index]
        if (parkedBetween(a, b, readingA, readingB)) {
            drops += (a.soc(scale) - b.soc(scale)).toDouble()
            spans += b.timestampMillis - a.timestampMillis
            quiet += b.timestampMillis - a.timestampMillis >= MIN_SLEEP_PAIR_MILLIS && idleBetween(a, b, context)
        }
    }
    val drop = drops.sum()
    val millis = spans.sum()
    if (millis <= 0L) return ParkedDrain(drop, millis, drops.size, 0.0)
    val rate = drop / millis
    var squared = 0.0
    for (index in drops.indices) {
        val residual = drops[index] - rate * spans[index]
        squared += residual * residual
    }
    // Every pair's two rounded readings count, not only the two ends: within a run
    // the pairs telescope, but the band should reflect how many readings went into
    // the rate. The residuals above already carry some of that rounding, so counting
    // it again errs wide, which is the intended side.
    val resolution = readingResolution(scale)
    val rounding = TWO_READINGS_VARIANCE * drops.size * resolution * resolution
    return ParkedDrain(
        drop,
        millis,
        drops.size,
        squared,
        rounding,
        SleepSpan(
            drops.filterIndexed { index, _ -> quiet[index] }.sum(),
            spans.filterIndexed { index, _ -> quiet[index] }.sum(),
            quiet.count { it },
        ),
    )
}

/** True when two drive readings put the car at one odometer reading and out of gear: it stayed parked between them. */
internal fun stayedParked(
    a: DriveSample?,
    b: DriveSample?,
): Boolean =
    a != null &&
        b != null &&
        a.odometerInHundredthsOfAMile == b.odometerInHundredthsOfAMile &&
        !a.inGear &&
        !b.inGear

/**
 * How far ahead a range's projection looks, rounded to a figure a person can
 * say: half the range up to a week (3 h, 12 h, 3 days), a quarter beyond
 * (a week for a month), floored to 1, 2, 3, 6 or 12 hours or 1, 2, 3 or 7
 * days, then whole weeks.
 */
fun projectionHorizonMillis(rangeMillis: Long): Long {
    val raw = if (rangeMillis <= WEEK_MILLIS) rangeMillis / 2 else rangeMillis / 4
    return NICE_HORIZONS_MILLIS.lastOrNull { it <= raw } ?: raw.coerceAtLeast(HOUR)
}

private val NICE_HORIZONS_MILLIS =
    listOf(1L, 2L, 3L, 6L, 12L).map { it * 60 * 60_000L } +
        listOf(1L, 2L, 3L, 7L, 14L, 21L, 28L).map { it * 24 * 60 * 60_000L }

private const val WEEK_MILLIS = 7 * 24 * 60 * 60_000L

/** A full battery, percent. */
private const val FULL_PERCENT = 100f

/** An asleep rate needs this many slept-through pairs: one pair's rounding can fake a rate or a zero. */
private const val MIN_SLEEP_PAIRS = 2

/** A beam needs this many parked pairs behind the rate. */
private const val MIN_BEAM_PAIRS = 3

/** Two end readings, each with the same rounding variance. */
private const val TWO_READINGS_VARIANCE = 2.0

private const val MINUTE = 60_000L

private const val HOUR = 60 * MINUTE

/** A pair of reads at least this far apart is mostly sleep: a locked car stays awake only a while after the owner leaves. */
internal const val MIN_SLEEP_PAIR_MILLIS = HOUR

/** Less parked time than this makes the rate too noisy to extrapolate. */
private const val MIN_RATE_SPAN_MILLIS = 10 * MINUTE
