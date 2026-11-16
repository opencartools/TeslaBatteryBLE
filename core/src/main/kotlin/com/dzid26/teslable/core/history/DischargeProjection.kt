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
    /** The mixed rate (every parked pair), percent per hour, when [percentPerHour] is the quiet rate; else null. */
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
 * One stretch the car spent powering down: a quiet parked gap of an hour or more between two
 * reads, one of the pairs the parked drain rate is fitted from. [startMillis] and [endMillis]
 * are the reads at its ends, so a gap covers exactly the stretch the rate counts. This is the
 * time bounds alone; [ParkedSpan] carries them with the drop across the stretch.
 */
data class PowerDownGap(
    val startMillis: Long,
    val endMillis: Long,
)

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
 * The line follows the quiet rate once the car has been left alone enough to tell: the
 * rate over the pairs with nobody at the car, Sentry and climate off, an hour or more
 * apart ([idleBetween]). A VCSEC status saying asleep is not required: a sleeping car
 * sends nothing, so demanding it would drop exactly the long overnight gaps. The mixed
 * rate (every parked pair) is reported beside it, and the beam's lower edge reaches the
 * mixed line, so the beam spans the quiet rate to what the battery really did. Without
 * enough quiet pairs the line is the mixed rate.
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

    val windowed = windowedParkedDrain(samples, drives, rangeMillis, context) ?: return null
    if (windowed.millis < MIN_RATE_SPAN_MILLIS || windowed.drop <= 0.0) return null

    val fromSoc = last.soc(scale)
    val mixedRate = windowed.drop / windowed.millis
    // The line follows the quiet rate when there is enough of it, else the mixed one.
    val sleepRate = windowed.sleepRatePerMilli?.takeIf { it < mixedRate }
    val mainRate = sleepRate ?: mixedRate
    val projectedSoc = (fromSoc - mainRate * horizon).toFloat().coerceIn(0f, FULL_PERCENT)
    val mixedSoc = (fromSoc - mixedRate * horizon).toFloat().coerceIn(0f, FULL_PERCENT)
    val halfWidth = 2.0 * hypot(windowed.errorPerMilli, mainRate * (scale.levelScaleError() ?: 0.0)) * horizon
    val hasStats = windowed.pairs >= MIN_BEAM_PAIRS
    val hasBeam = hasStats || sleepRate != null
    // The beam: the statistics around the line, reaching down to the mixed line when the main one is the quiet rate.
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
 * The power-down gaps of a range: the quiet parked stretches in it, each run of consecutive
 * quiet pairs joined into one. A quiet pair is the same one the parked rate counts as
 * [ProjectionContext]-checked power-down: parked, at least [MIN_SLEEP_PAIR_MILLIS] apart, with
 * nobody in the car and Sentry and climate off. Same window and same pairs as
 * [dischargeProjection], so a gap is exactly a stretch behind its rate; the gaps are listed
 * whether or not a projection is drawn, so a quiet night still shows once the line is gone.
 *
 * A gap ends at the read that ends it: a pair whose first read is before the range is not one of
 * the parked pairs, so a gap that straddles the range's start is left out rather than cut short.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param rangeMillis the selected range, read as [dischargeProjection] reads it.
 * @param context the fitted scale and the logs that say when the car was alone.
 */
fun powerDownGaps(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    rangeMillis: Long,
    context: ProjectionContext = ProjectionContext(),
): List<PowerDownGap> = windowedParkedDrain(samples, drives, rangeMillis, context)?.sleep?.gaps.orEmpty()

/**
 * The parked drain over the range, from the newest read back: the one window and drive filter that
 * both [dischargeProjection] and [powerDownGaps] read, so the gaps cannot drift from the rate.
 */
private fun windowedParkedDrain(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    rangeMillis: Long,
    context: ProjectionContext,
): ParkedDrain? {
    val last = samples.lastOrNull() ?: return null
    val windowed = samples.filter { it.timestampMillis >= last.timestampMillis - rangeMillis }
    return parkedDrain(windowed, drives.filter { it.odometerInHundredthsOfAMile != null }, context)
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

/** The drop (percent) and duration of the quiet pairs, and the stretches they span. */
private class SleepSpan(
    val drop: Double,
    val millis: Long,
    /** How many quiet pairs the sums cover: more than [gaps].size, since a run of hourly reads is one gap. */
    val pairs: Int,
    /** One stretch per run of consecutive quiet pairs: the seams between them are not breaks. */
    val gaps: List<PowerDownGap> = emptyList(),
)

/** The summed signed drop (percent) and duration of the parked pairs, with their spread. */
private class ParkedDrain(
    val drop: Double,
    val millis: Long,
    /** How many parked pairs the sums cover, quiet and busy together. */
    val pairs: Int,
    /** Sum of each pair's squared residual from the mean rate. */
    val squaredResiduals: Double,
    /** The rounding variance of the summed drop: two readings for every pair. */
    val roundingVariance: Double = 0.0,
    /** The drop and duration of the quiet pairs. */
    val sleep: SleepSpan = SleepSpan(0.0, 0L, 0),
) {
    /** The drain rate of the quiet pairs, percent per millisecond; null with too little of it. */
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
    val quietPairs = mutableListOf<PowerDownGap>()
    val readings = driveReadingsAt(samples, drives)
    for (index in 1 until samples.size) {
        val a = samples[index - 1]
        val b = samples[index]
        val readingA = readings[index - 1]
        val readingB = readings[index]
        if (parkedBetween(a, b, readingA, readingB)) {
            drops += (a.soc(scale) - b.soc(scale)).toDouble()
            spans += b.timestampMillis - a.timestampMillis
            val isQuiet = b.timestampMillis - a.timestampMillis >= MIN_SLEEP_PAIR_MILLIS && idleBetween(a, b, context)
            quiet += isQuiet
            if (isQuiet) quietPairs += PowerDownGap(a.timestampMillis, b.timestampMillis)
        }
    }
    val drop = drops.sum()
    val millis = spans.sum()
    val gaps = quietPairs.merged()
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
            gaps,
        ),
    )
}

/**
 * [this] gaps with each run of consecutive ones joined into one: two quiet pairs that meet at a
 * read are one stretch, not two bands with a seam between them.
 */
private fun List<PowerDownGap>.merged(): List<PowerDownGap> {
    val merged = mutableListOf<PowerDownGap>()
    for (gap in this) {
        val last = merged.lastOrNull()
        if (last != null && last.endMillis >= gap.startMillis) {
            merged[merged.lastIndex] = PowerDownGap(last.startMillis, maxOf(last.endMillis, gap.endMillis))
        } else {
            merged += gap
        }
    }
    return merged
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

/** A quiet rate needs this many quiet pairs: one pair's rounding can fake a rate or a zero. */
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
