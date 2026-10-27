// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * Thins a stream of readings for the long-term history (ADR-0014's `derived.db`): the car is read every ten seconds
 * while awake, and years of that would not fit in memory. Per [series] it keeps the first reading, both readings
 * around a [changed] one (so a step, a charge start or a gear change sits at its true time), and otherwise one
 * reading each [period] after the last one kept. Both readings around a hole of [period] or more are kept too, so
 * every pair of consecutive readings that spans a hole is the same after thinning. A reading older than the previous
 * one (a late reply) is kept as it is and changes nothing, so the stream need not be in perfect time order. [period]
 * may depend on the newest kept reading as well as the next one.
 */
class SampleThinner<T : Any>(
    private val series: (T) -> Any,
    private val time: (T) -> Long,
    private val changed: (previous: T, next: T) -> Boolean,
    private val period: (kept: T, next: T) -> Long,
) {
    private class Seen<T>(
        var kept: T,
        var latest: T,
    )

    private val seen = mutableMapOf<Any, Seen<T>>()

    /**
     * Starts [next]'s series from where an earlier run stopped: [kept] is the newest reading that run kept, [latest]
     * the newest it saw. Without it the series starts afresh and keeps its first reading.
     */
    fun resume(
        kept: T,
        latest: T,
    ) {
        seen[series(latest)] = Seen(kept, latest)
    }

    /** The readings to keep because [next] arrived, in time order: the one before a change, if dropped, then [next] when kept. */
    fun accept(next: T): List<T> {
        val state = seen[series(next)]
        return when {
            state == null -> listOf(next).also { seen[series(next)] = Seen(next, next) }
            time(next) < time(state.latest) -> listOf(next)
            else -> advance(state, next)
        }
    }

    private fun advance(
        state: Seen<T>,
        next: T,
    ): List<T> {
        val kept = mutableListOf<T>()
        // A hole in the reads (the phone away, the car asleep) counts as a change: the stats pair each read with
        // the next one, so the read before a hole must stay for the pair that spans it to stay the same.
        val change = changed(state.latest, next) || time(next) - time(state.latest) >= period(state.kept, next)
        if (change && state.latest !== state.kept && time(state.latest) > time(state.kept)) kept += state.latest
        if (change || time(next) - time(state.kept) >= period(state.kept, next)) {
            kept += next
            state.kept = next
        }
        state.latest = next
        return kept
    }
}

/**
 * The thinner for [BatterySample]s: a change of level, usable level, charging state or charge limit keeps both
 * sides, so level steps (the full-range fit) and charge sessions keep their exact edges. Otherwise one reading a
 * minute while charging, one each half minute while the rated range moves since the last kept one (a drive: the
 * drive chart places these on the odometer), and one each five minutes otherwise. On R's logs this keeps about 280
 * of some 1,060 readings a day, and the full-range fit, charge sessions, parked spans, deep sleeps and lowest drain
 * come out the same as from every reading.
 */
fun batteryThinner(): SampleThinner<BatterySample> =
    SampleThinner(
        series = { it.vehicleId },
        time = { it.timestampMillis },
        changed = { a, b ->
            a.batteryLevel != b.batteryLevel ||
                a.usableLevel != b.usableLevel ||
                a.chargingState != b.chargingState ||
                a.chargeLimit != b.chargeLimit
        },
        period = { kept, next ->
            when {
                next.isCharging -> CHARGING_KEEP_MILLIS
                next.ratedRangeMiles != kept.ratedRangeMiles -> MOVING_KEEP_MILLIS
                else -> PARKED_KEEP_MILLIS
            }
        },
    )

/**
 * The thinner for [DriveSample]s: a gear change keeps both sides, and so does an odometer change out of gear (a drive
 * the phone did not see); in gear one reading each half minute, out of gear one each [DRIVE_MATCH_MILLIS], so a kept
 * battery reading of an awake car still finds the odometer reading next to it.
 */
fun driveThinner(): SampleThinner<DriveSample> =
    SampleThinner(
        series = { it.vehicleId },
        time = { it.timestampMillis },
        changed = { a, b ->
            a.shiftState != b.shiftState ||
                (!b.inGear && a.odometerInHundredthsOfAMile != b.odometerInHundredthsOfAMile)
        },
        period = { _, next -> if (next.inGear) IN_GEAR_KEEP_MILLIS else DRIVE_MATCH_MILLIS },
    )

/** The readings of [this], oldest first, thinned by [thinner]. */
fun <T : Any> List<T>.thinnedBy(thinner: SampleThinner<T>): List<T> = flatMap { thinner.accept(it) }

private const val CHARGING_KEEP_MILLIS = 60_000L
private const val PARKED_KEEP_MILLIS = 5 * 60_000L
private const val MOVING_KEEP_MILLIS = 30_000L
private const val IN_GEAR_KEEP_MILLIS = 30_000L
