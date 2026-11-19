// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * What the battery lost while parked around a sleep: from [fromPercent] to [toPercent] over [spanMillis], between
 * the two reads that bracket it. With [ceiling] the reads lie far from the sleep, so the span holds awake time too
 * and the rate is an upper bound on the sleep's own.
 */
data class SleepDrop(
    val fromPercent: Double,
    val toPercent: Double,
    val spanMillis: Long,
    val ceiling: Boolean = false,
) {
    /** The points lost, negative when the level rose. */
    val percent: Double get() = fromPercent - toPercent
}

/**
 * One deep sleep: the status log first said asleep at [startMillis] and said awake (or was last heard asleep)
 * at [endMillis]. [drop] is what the battery lost around it, null when no parked reads bracket it closely.
 */
data class DeepSleep(
    val startMillis: Long,
    val endMillis: Long,
    val drop: SleepDrop? = null,
) {
    val durationMillis: Long get() = endMillis - startMillis

    /** The drain around the sleep, percentage points lost per hour; null with no [drop]. */
    val percentPerHour: Double? get() = drop?.let { it.percent * HOUR_MILLIS / it.spanMillis }
}

/**
 * A stretch the car slept through, and the parts of it the status log did not witness: holes the car was asleep on
 * both sides of. [unseen] is empty for a sleep the log followed throughout.
 */
data class SleepSpan(
    val startMillis: Long,
    val endMillis: Long,
    val unseen: List<TimeSpan> = emptyList(),
)

/**
 * The longest of [deepSleeps]. Ties go to the later sleep.
 *
 * @see deepSleeps for the parameters.
 */
fun longestDeepSleep(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    statuses: List<StatusSample>,
    scale: FullRangeScale? = null,
): DeepSleep? = deepSleeps(samples, drives, statuses, scale).maxWithOrNull(compareBy({ it.durationMillis }, { it.startMillis }))

/**
 * Every deep sleep in the status log: an unbroken run of asleep readings, ended by the next awake reading when
 * one follows within a heartbeat. The log writes a reading on every change and a heartbeat while the phone is
 * connected, so a hole in it (the phone out of range or reconnecting) ends the run at the last asleep reading:
 * the car may have woken unseen. A deep sleep needs the phone connected throughout; the projection's wider
 * notion of a quiet parked car, holes included, is [idleBetween]. A run shorter than
 * [MIN_SLEEP_SPAN_MILLIS] is not a deep sleep.
 *
 * The drain is read from the last battery read at or before the sleep and the first at or after it, when the car
 * stayed parked between them (no charge, the odometer and gear unchanged). When both reads lie within
 * [DROP_SLACK_MILLIS] of the sleep's ends the drain stands for the sleep; further away it is marked
 * [SleepDrop.ceiling]. A sleep without such reads has no drain.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param statuses the same vehicle's status log, oldest first.
 * @param scale the fitted rated-range scale that refines the drop; null reads the integer levels.
 */
fun deepSleeps(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    statuses: List<StatusSample>,
    scale: FullRangeScale? = null,
): List<DeepSleep> {
    val parkedDrives = drives.filter { it.odometerInHundredthsOfAMile != null }
    return asleepSpans(statuses).map { span ->
        DeepSleep(span.startMillis, span.endMillis, dropAround(span, samples, parkedDrives, scale))
    }
}

/**
 * Every stretch the status log says the car was asleep: an unbroken run of asleep readings, ended by the awake
 * reading that follows it when one comes within a heartbeat. A hole in the log (the phone out of range or
 * reconnecting) ends the run at the last asleep reading: the car may have woken unseen. A run shorter than
 * [MIN_SLEEP_SPAN_MILLIS] is not a sleep. [bridgedSleepSpans] joins the runs across such holes for the chart.
 *
 * @param statuses the vehicle's status log, oldest first.
 */
fun asleepSpans(statuses: List<StatusSample>): List<TimeSpan> =
    sleepRuns(statuses, bridgeHoles = false)
        .filter { it.endMillis - it.startMillis >= MIN_SLEEP_SPAN_MILLIS }
        .map { TimeSpan(it.startMillis, it.endMillis) }

/**
 * The stretches the car slept through, with holes in the log joined when the car was asleep on both sides: the chart
 * shows one sleep rather than a row of fragments. Each joined hole is reported in [SleepSpan.unseen]. An awake
 * reading ends a stretch wherever it comes, so a hole followed by a wake is not joined. There is no cap on a hole's
 * length: the unseen stretch is marked in the band, not hidden. A stretch shorter than [MIN_SLEEP_SPAN_MILLIS] is
 * not a sleep.
 *
 * The stats card keeps to [asleepSpans]: a drain rate needs a sleep the log followed throughout.
 *
 * @param statuses the vehicle's status log, oldest first.
 */
fun bridgedSleepSpans(statuses: List<StatusSample>): List<SleepSpan> =
    sleepRuns(statuses, bridgeHoles = true).filter { it.endMillis - it.startMillis >= MIN_SLEEP_SPAN_MILLIS }

/**
 * The runs of asleep readings in [statuses], each ended by the awake reading that follows it when one does. With
 * [bridgeHoles] a hole that resumes asleep continues the run, and the hole is listed in its [SleepSpan.unseen].
 */
private fun sleepRuns(
    statuses: List<StatusSample>,
    bridgeHoles: Boolean,
): List<SleepSpan> {
    val runs = mutableListOf<SleepSpan>()
    var first: Long? = null
    var lastAsleep = 0L
    val unseen = mutableListOf<TimeSpan>()
    for (status in statuses) {
        val start = first
        if (start != null) {
            val gap = status.timestampMillis - lastAsleep
            if (status.asleep) {
                when {
                    gap <= STATUS_CHANGE_MAX_GAP_MILLIS -> lastAsleep = status.timestampMillis
                    bridgeHoles -> {
                        unseen.addHole(lastAsleep, status.timestampMillis)
                        lastAsleep = status.timestampMillis
                    }
                    else -> {
                        runs += SleepSpan(start, lastAsleep, unseen.toList())
                        first = status.timestampMillis
                        lastAsleep = status.timestampMillis
                        unseen.clear()
                    }
                }
                continue
            }
            val end = if (gap <= STATUS_CHANGE_MAX_GAP_MILLIS) status.timestampMillis else lastAsleep
            runs += SleepSpan(start, end, unseen.toList())
            first = null
            unseen.clear()
        }
        if (status.asleep) {
            first = status.timestampMillis
            lastAsleep = status.timestampMillis
            unseen.clear()
        }
    }
    first?.let { runs += SleepSpan(it, lastAsleep, unseen.toList()) }
    return runs
}

/** Adds a hole, joining it to the previous one when the log's next reading continued the same silence. */
private fun MutableList<TimeSpan>.addHole(
    from: Long,
    to: Long,
) {
    val last = lastOrNull()
    if (last != null && last.endMillis == from) {
        this[lastIndex] = TimeSpan(last.startMillis, to)
    } else {
        add(TimeSpan(from, to))
    }
}

private fun dropAround(
    sleep: TimeSpan,
    samples: List<BatterySample>,
    parkedDrives: List<DriveSample>,
    scale: FullRangeScale?,
): SleepDrop? {
    val after = firstReadAtOrAfter(samples, sleep.endMillis)
    val before = firstReadAtOrAfter(samples, sleep.startMillis + 1) - 1
    if (before < 0 || after >= samples.size || before >= after) return null
    val a = samples[before]
    val b = samples[after]
    val readings = driveReadingsAt(listOf(a, b), parkedDrives)
    val close = sleep.startMillis - readAt(a) <= DROP_SLACK_MILLIS && readAt(b) - sleep.endMillis <= DROP_SLACK_MILLIS
    return SleepDrop(a.soc(scale).toDouble(), b.soc(scale).toDouble(), readAt(b) - readAt(a), ceiling = !close)
        .takeIf { parkedBetween(a, b, readings[0], readings[1]) }
}

/** The index of the first sample read at or after [millis], or the size when none was; samples are oldest first. */
private fun firstReadAtOrAfter(
    samples: List<BatterySample>,
    millis: Long,
): Int {
    var low = 0
    var high = samples.size
    while (low < high) {
        val middle = (low + high) / 2
        if (readAt(samples[middle]) >= millis) high = middle else low = middle + 1
    }
    return low
}

internal fun readAt(sample: BatterySample): Long = sample.readAtMillis ?: sample.timestampMillis

/** Less sleep than this is too short to tell from a pause between reads; a shorter drain span is too noisy to extrapolate. */
internal const val MIN_SLEEP_SPAN_MILLIS = 10 * 60_000L

/** A battery read this close to either end of a sleep stands for the level it started and ended at. */
private const val DROP_SLACK_MILLIS = 30 * 60_000L

private const val HOUR_MILLIS = 60 * 60_000L
