// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.health

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.EnergyDeltaEstimator
import com.dzid26.teslable.core.health.MIN_SWING_PERCENT
import com.dzid26.teslable.core.health.chargeSessions
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.history.FullRangeScales
import kotlin.math.roundToInt

// Battery-health glue: turns the recorded raw samples into the numbers the car
// view shows. All estimation and session segmentation live in `core/health`;
// this file only selects inputs, counts sessions for the learning gate, and
// flags thin data.

/** Confidence in the estimates, gated on how many charge sessions were seen. */
enum class HealthConfidence { LEARNING, LOW, MEDIUM, HIGH }

/**
 * The health card's data. [sohPercent] is the fitted full range over the car's
 * rated range when new, and null until the owner enters that range, so the card
 * never invents a baseline.
 */
data class HealthSummary(
    val sessions: Int,
    /** Sessions needed before the learning gate opens. */
    val learningTarget: Int,
    val sohPercent: Float?,
    val capacityKwh: Float?,
    /** The session's rated constant, kWh per rated mile. */
    val ratedKwhPerMile: Float?,
    val capacitySwingPercent: Float?,
    val fullRangeMiles: Float?,
    /** Level steps the full-range fit used. */
    val fullRangeSteps: Int,
    val confidence: HealthConfidence,
    /** A plain-language caveat when the inputs are thin, else null. */
    val qualityNote: String?,
    /** The pack energy when new, the rated constant times the new-car rated range; null until both are known. */
    val newCapacityKwh: Float? = null,
)

/**
 * Builds the card's data from one car's samples. [epaRatedRangeMiles] is the
 * car's rated range when new; while it is unknown the card shows measured
 * capacity only and no SoH percentage.
 */
fun healthSummary(
    samples: List<BatterySample>,
    epaRatedRangeMiles: Float? = null,
    // The car's charge sessions, oldest first; the screen passes the stored ones joined with the live tail.
    sessions: List<ChargeSession> = chargeSessions(samples),
): HealthSummary {
    val lastSession = sessions.lastOrNull()
    // Rated miles are the backbone (research: range-as-soc-signal section 6).
    // The one full-range definition is the fitted scale of the level steps
    // (`FullRangeScaleEstimator`); a charge session contributes the rated
    // constant (kWh per rated mile) from the energy and miles it added.
    val scale = FullRangeScales.of(samples)
    val backboneSession = sessions.lastOrNull { it.kwhPerMile != null }
    val energySession =
        sessions.lastOrNull { it.energyAddedKwh != null && it.swingPercent >= MIN_SWING_PERCENT }
    // The range at 100%: the fitted line's value at displayed SOC 100.
    val fullRangeMiles = scale?.milesAtFull
    val ratedKwhPerMile = backboneSession?.kwhPerMile
    val capacityKwh =
        ratedKwhPerMile?.let { constant -> fullRangeMiles?.let { constant * it } }
            ?: energySession?.let { session ->
                EnergyDeltaEstimator
                    .estimate(
                        energyAddedKwh = session.energyAddedKwh!!,
                        socStartPercent = session.startPercent,
                        socEndPercent = session.endPercent,
                    )?.usableCapacityKwh
            }
    val sohPercent =
        fullRangeMiles?.let { full -> epaRatedRangeMiles?.takeIf { it > 0f }?.let { full / it * 100f } }
    return HealthSummary(
        sessions = sessions.size,
        learningTarget = LEARNING_SESSIONS,
        sohPercent = sohPercent,
        capacityKwh = capacityKwh,
        ratedKwhPerMile = ratedKwhPerMile,
        capacitySwingPercent = (backboneSession ?: energySession)?.swingPercent,
        fullRangeMiles = fullRangeMiles,
        fullRangeSteps = scale?.samples ?: 0,
        confidence = confidenceFor(sessions.size),
        qualityNote = qualityNote(backboneSession ?: energySession ?: lastSession),
        newCapacityKwh = ratedKwhPerMile?.let { constant -> epaRatedRangeMiles?.takeIf { it > 0f }?.let { constant * it } },
    )
}

private fun confidenceFor(sessions: Int): HealthConfidence =
    when {
        sessions < LEARNING_SESSIONS -> HealthConfidence.LEARNING
        sessions < MEDIUM_SESSIONS -> HealthConfidence.LOW
        sessions < HIGH_SESSIONS -> HealthConfidence.MEDIUM
        else -> HealthConfidence.HIGH
    }

private fun qualityNote(lastSession: ChargeSession?): String? {
    val swing = lastSession?.swingPercent
    return when {
        swing != null && swing < QUALITY_SWING_PERCENT ->
            "the last charge only swung ${swing.roundToInt()}% SOC"

        else -> null
    }
}

/** Sessions before the "Learning" label gives way to low confidence. */
private const val LEARNING_SESSIONS = 3
private const val MEDIUM_SESSIONS = 10

/** High confidence waits for this many sessions. */
private const val HIGH_SESSIONS = 20

/** A charge swing under this widens the energy-delta error. */
private const val QUALITY_SWING_PERCENT = 20f
