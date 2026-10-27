// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.ChargeStats
import com.dzid26.teslable.core.history.Session
import com.dzid26.teslable.core.history.chargeStats
import com.dzid26.teslable.protocol.ChargingStateKind

/**
 * One charge session as the history saw it: from its first to its last charging
 * sample (see [chargeSessions] for how sessions are cut).
 *
 * [energyAddedKwh] and [milesAddedRated] are what the car added between those two
 * samples, so they always pair with [swingPercent]. Each is null when the car
 * reported nothing or the counter did not grow.
 */
data class ChargeSession(
    /** The car's clock at the first charging sample, millis. */
    override val startMillis: Long,
    /** The car's clock at the last charging sample, millis. */
    override val endMillis: Long,
    val startPercent: Float,
    val endPercent: Float,
    val energyAddedKwh: Float?,
    val milesAddedRated: Float?,
    /** True for the session that is still going; its figures grow until it ends. */
    override val inProgress: Boolean = false,
    /** The car this session belongs to. */
    val vehicleId: String = "",
) : Session {
    override val id: String get() = "charge-$vehicleId-$startMillis"
    override val socStartPercent: Float get() = startPercent
    override val socEndPercent: Float get() = endPercent
    override val energyKwh: Float? get() = energyAddedKwh

    /** Measured by the car, not estimated. */
    override val energyIsEstimate: Boolean get() = false

    val durationMillis: Long get() = endMillis - startMillis

    /** Energy over duration, kW; null without energy or under five minutes of data, where the sampling interval at each end skews it. */
    val averageKw: Float?
        get() {
            val energy = energyAddedKwh ?: return null
            return if (durationMillis >= MIN_AVERAGE_MILLIS) energy / (durationMillis / MILLIS_PER_HOUR) else null
        }

    val swingPercent: Float get() = endPercent - startPercent

    /** The rated constant this session measured: kWh per rated mile. */
    val kwhPerMile: Float?
        get() {
            val energy = energyAddedKwh ?: return null
            val miles = milesAddedRated?.takeIf { it > 0f } ?: return null
            return energy / miles
        }
}

/**
 * Splits [samples], one car's history oldest first, into charge sessions.
 *
 * Sessions are cut by `chargingState`:
 * - A session is a run of `Charging` or `Starting` samples.
 * - `Stopped` and `NoPower` samples are a pause. A pause shorter than ten minutes,
 *   counted from the last charging sample to the one that resumes charging, does
 *   not end the session. A pause of ten minutes or longer does, and the resuming
 *   sample opens the next session.
 * - `Complete`, `Disconnected` and any other state (a missing, `Unknown` or
 *   `Calibrating` one included) end the session.
 * - Silence is not a pause: when the app saw nothing for a while (the link
 *   dropped), the session continues across the gap.
 *
 * A session still in progress counts, so the learning gate moves while charging.
 *
 * The car reports `charge_energy_added` and `charge_miles_added_rated` as running
 * totals since it began the session, and the first sample seen is often not that
 * start (the app was opened or came into range mid-charge). Each session therefore
 * carries the growth of those totals from its first to its last sample, which pairs
 * with the SOC swing between the same two samples. A total missing from the first
 * sample counts as zero there. A total that goes down inside the session was
 * restarted by the car (a stale value from the previous session on a `Starting`
 * sample, say); the session then carries the last value.
 */
fun chargeSessions(samples: List<BatterySample>): List<ChargeSession> = chargeRuns(samples).map { it.toSession() }

/**
 * [chargeSessions], with the newest marked in progress when the history says one is still going at [nowMillis]
 * (see [chargeSessionOpen]).
 */
fun chargeSessions(
    samples: List<BatterySample>,
    nowMillis: Long,
): List<ChargeSession> {
    val sessions = chargeSessions(samples)
    return if (sessions.isNotEmpty() && chargeSessionOpen(samples, nowMillis)) {
        sessions.dropLast(1) + sessions.last().copy(inProgress = true)
    } else {
        sessions
    }
}

private fun chargeRuns(samples: List<BatterySample>): List<List<BatterySample>> {
    val runs = mutableListOf<MutableList<BatterySample>>()
    var current: MutableList<BatterySample>? = null
    var paused = false
    for (sample in samples) {
        when (sample.chargingState) {
            ChargingStateKind.Charging, ChargingStateKind.Starting -> {
                val pausedTooLong =
                    paused && current != null && sample.timestampMillis - current.last().timestampMillis >= MAX_PAUSE_MILLIS
                if (current == null || pausedTooLong) {
                    current = mutableListOf()
                    runs.add(current)
                }
                current.add(sample)
                paused = false
            }

            ChargingStateKind.Stopped, ChargingStateKind.NoPower -> paused = true

            else -> {
                current = null
                paused = false
            }
        }
    }
    return runs
}

/**
 * Stats of the last charge that has finished: [chargeStats] once the session is over, and while one is
 * still open (see [chargeSessionOpen]) the charge before it, so the figures stay put and are swapped
 * when the session ends. Null when there is no earlier charge to show.
 */
fun lastFinishedChargeStats(
    samples: List<BatterySample>,
    nowMillis: Long,
): ChargeStats? {
    val currentStart = chargeSessionStart(samples, nowMillis)
    return chargeStats(currentStart?.let { start -> samples.filter { it.timestampMillis < start } } ?: samples)
}

/**
 * When the charge session that is still going at [nowMillis] began (see [chargeSessionOpen]): its first charging
 * sample, with a pause shorter than ten minutes not counted as a break. Null when no session is open.
 */
fun chargeSessionStart(
    samples: List<BatterySample>,
    nowMillis: Long,
): Long? = if (chargeSessionOpen(samples, nowMillis)) chargeRuns(samples).lastOrNull()?.first()?.timestampMillis else null

private fun List<BatterySample>.toSession(): ChargeSession =
    ChargeSession(
        startMillis = first().timestampMillis,
        endMillis = last().timestampMillis,
        startPercent = first().batteryLevel.toFloat(),
        endPercent = last().batteryLevel.toFloat(),
        energyAddedKwh = counterGrowth { it.chargeEnergyAdded },
        milesAddedRated = counterGrowth { it.chargeMilesAddedRated },
        vehicleId = first().vehicleId,
    )

/**
 * How far one of the car's running totals grew from the first to the last sample
 * of a run. Null when the last sample has no value or the total did not grow.
 */
private fun List<BatterySample>.counterGrowth(counter: (BatterySample) -> Float?): Float? {
    val end = counter(last()) ?: return null
    val start = counter(first()) ?: 0f
    val restarted = mapNotNull(counter).zipWithNext().any { (before, after) -> after < before }
    return (if (restarted) end else end - start).takeIf { it > 0f }
}

/**
 * Whether a charge session is still going at [nowMillis]: the car is charging or starting, or it paused
 * (Stopped or NoPower) less than ten minutes after [lastChargingMillis], the same margin [chargeSessions] uses.
 * Past that the session has ended, and its energy belongs with the finished charge.
 */
fun chargeSessionOpen(
    state: ChargingStateKind?,
    lastChargingMillis: Long?,
    nowMillis: Long,
): Boolean =
    when (state) {
        ChargingStateKind.Charging, ChargingStateKind.Starting -> true
        ChargingStateKind.Stopped, ChargingStateKind.NoPower ->
            lastChargingMillis != null && nowMillis - lastChargingMillis < MAX_PAUSE_MILLIS
        else -> false
    }

/** [chargeSessionOpen] for a history: the newest sample's state and the newest charging sample's time. */
fun chargeSessionOpen(
    samples: List<BatterySample>,
    nowMillis: Long,
): Boolean =
    chargeSessionOpen(
        samples.maxByOrNull { it.timestampMillis }?.chargingState,
        samples.filter { it.isCharging }.maxOfOrNull { it.timestampMillis },
        nowMillis,
    )

/** Below this swing the estimator itself refuses to compute a capacity. */
const val MIN_SWING_PERCENT = 5f

private const val MIN_AVERAGE_MILLIS = 5 * 60_000L
private const val MILLIS_PER_HOUR = 3_600_000f

/** A pause (Stopped or NoPower) shorter than this does not end a session. */
private const val MAX_PAUSE_MILLIS = 10 * 60_000L

/** When each charge that had begun by [byMillis] began, oldest first: the time of its first charging sample. */
fun chargeStartsBy(
    samples: List<BatterySample>,
    byMillis: Long,
): List<Long> = chargeRuns(samples).map { it.first().timestampMillis }.filter { it <= byMillis }

/** When the charge whose last charging sample is at [endMillis] began, or [endMillis] when no charge ends there. */
fun chargeStartOf(
    samples: List<BatterySample>,
    endMillis: Long,
): Long = chargeRuns(samples).firstOrNull { it.last().timestampMillis == endMillis }?.first()?.timestampMillis ?: endMillis
