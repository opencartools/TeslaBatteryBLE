// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.climateOn
import com.dzid26.teslable.protocol.sentryOn

/** What keeps a locked car busy: Sentry mode or the climate. */
enum class ActivityKind { SENTRY, CLIMATE }

/**
 * One reading of whether [kind] was running, derived from a logged closures or climate [BleRecord] (ADR-0008).
 * Like [StatusSample] the time is the phone's clock at receipt.
 */
data class ActivitySample(
    val timestampMillis: Long,
    val vehicleId: String,
    val kind: ActivityKind,
    val on: Boolean,
)

/** The activity reading a closures or climate record carries; null for any other record or one with no time. */
fun BleRecord.toActivitySample(vehicleId: String): ActivitySample? {
    val time = device_timestamp?.toEpochMilli() ?: return null
    val closures = closures_state
    val climate = climate_state
    return when {
        closures != null -> ActivitySample(time, vehicleId, ActivityKind.SENTRY, closures.sentryOn)
        climate != null -> ActivitySample(time, vehicleId, ActivityKind.CLIMATE, climate.climateOn)
        else -> null
    }
}

/**
 * Thins a stream of [ActivitySample]s. While Sentry or the climate runs the car is read every ten seconds, so keeping
 * every reading would fill the history with copies of one state. Per car and kind it keeps a reading each
 * [ACTIVITY_KEEP_MILLIS], every change of state, and the reading just before a change, so a run ends at its true
 * last reading. That is closer together than the 16 minutes a reading may stand for a gap.
 */
class ActivityThinner {
    private class Seen(
        var kept: ActivitySample,
        var latest: ActivitySample,
    )

    private val seen = mutableMapOf<Pair<String, ActivityKind>, Seen>()

    /**
     * Starts [latest]'s car and kind from where an earlier run stopped: [kept] is the newest reading that run kept,
     * [latest] the newest it saw. Without it the series starts afresh and keeps its first reading.
     */
    fun resume(
        kept: ActivitySample,
        latest: ActivitySample,
    ) {
        seen[latest.vehicleId to latest.kind] = Seen(kept, latest)
    }

    /** The readings to keep because [next] arrived, in time order: the one before a change, if dropped, then [next] when kept. */
    fun accept(next: ActivitySample): List<ActivitySample> {
        val key = next.vehicleId to next.kind
        val state = seen[key]
        if (state == null) {
            seen[key] = Seen(next, next)
            return listOf(next)
        }
        val changed = next.on != state.latest.on
        val kept = mutableListOf<ActivitySample>()
        if (changed && state.latest !== state.kept) kept += state.latest
        if (changed || next.timestampMillis - state.kept.timestampMillis >= ACTIVITY_KEEP_MILLIS) {
            kept += next
            state.kept = next
        }
        state.latest = next
        return kept
    }
}

/** The readings of [this], oldest first, thinned by an [ActivityThinner]. */
fun List<ActivitySample>.thinned(): List<ActivitySample> {
    val thinner = ActivityThinner()
    return flatMap { thinner.accept(it) }
}

/** Kept readings of one state are at most this far apart: under the 16 minutes a reading may stand for a gap. */
private const val ACTIVITY_KEEP_MILLIS = 10 * 60_000L

/**
 * The stretches [kind] ran while the car stayed put: [activitySpans] without the runs a drive reading shows the car
 * moving through (in gear, or with a speed). A climate that runs on a drive is the trip's, not the parked car's.
 *
 * @param activity the vehicle's Sentry and climate readings, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 */
fun parkedActivitySpans(
    activity: List<ActivitySample>,
    kind: ActivityKind,
    drives: List<DriveSample>,
): List<TimeSpan> {
    val moving = drives.filter { it.inGear || (it.speed ?: 0) > 0 }
    return activitySpans(activity, kind).filter { span -> moving.none { it.timestampMillis in span.startMillis..span.endMillis } }
}

/**
 * The stretches [kind] was running: each unbroken run of on readings, ended by the off reading that follows it when
 * one comes within a heartbeat. A hole in the log (the phone out of range) ends the run at its last on reading, since
 * the car may have stopped unseen. Readings of the other kind are ignored.
 *
 * @param activity the vehicle's Sentry and climate readings, oldest first.
 */
fun activitySpans(
    activity: List<ActivitySample>,
    kind: ActivityKind,
): List<TimeSpan> {
    val spans = mutableListOf<TimeSpan>()
    var start: Long? = null
    var lastOn = 0L
    for (reading in activity) {
        if (reading.kind != kind) continue
        val open = start
        when {
            reading.on && open == null -> {
                start = reading.timestampMillis
                lastOn = reading.timestampMillis
            }
            reading.on && reading.timestampMillis - lastOn <= STATUS_CHANGE_MAX_GAP_MILLIS -> lastOn = reading.timestampMillis
            reading.on -> {
                // The log went quiet for longer than a heartbeat: the run ended unseen, and this reading starts another.
                if (open != null) spans += TimeSpan(open, lastOn)
                start = reading.timestampMillis
                lastOn = reading.timestampMillis
            }
            open != null -> {
                val adjacent = reading.timestampMillis - lastOn <= STATUS_CHANGE_MAX_GAP_MILLIS
                spans += TimeSpan(open, if (adjacent) reading.timestampMillis else lastOn)
                start = null
            }
        }
    }
    start?.let { spans += TimeSpan(it, lastOn) }
    return spans.filter { it.endMillis > it.startMillis }
}
