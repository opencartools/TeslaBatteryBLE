// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

/**
 * Energy-delta capacity method: capacity from energy added over an SOC swing.
 *
 * Reliability grows with larger SOC swings; clean uninterrupted sessions with
 * rested endpoints are preferred.
 */
data class CapacityEstimate(
    val usableCapacityKwh: Float,
    val socDeltaPercent: Float,
)

/**
 * Below this swing the estimator refuses to compute a capacity: a small top-up
 * carries a couple of rated miles of counter resolution, which pins nothing.
 */
const val MIN_SWING_PERCENT = 5f

object EnergyDeltaEstimator {
    fun estimate(
        energyAddedKwh: Float,
        socStartPercent: Float,
        socEndPercent: Float,
    ): CapacityEstimate? {
        if (!isValidInputs(energyAddedKwh, socStartPercent, socEndPercent)) return null
        val delta = socEndPercent - socStartPercent
        if (delta < MIN_SWING_PERCENT) return null
        val usableCapacity = energyAddedKwh / (delta / 100f)
        return CapacityEstimate(usableCapacity, delta)
    }

    private fun isValidInputs(
        energyAddedKwh: Float,
        socStartPercent: Float,
        socEndPercent: Float,
    ): Boolean =
        energyAddedKwh.isFinite() &&
            energyAddedKwh > 0f &&
            socStartPercent.isFinite() &&
            socStartPercent >= 0f &&
            socEndPercent.isFinite() &&
            socEndPercent <= 100f &&
            socStartPercent < socEndPercent
}
