// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.protocol.ChargingStateKind

/** The runs of charging samples in [samples] (oldest first) that [chargeSessions] makes sessions of. */
internal fun chargeRuns(samples: List<BatterySample>): List<List<BatterySample>> {
    val runs = mutableListOf<MutableList<BatterySample>>()
    // Whether the newest of [runs] may still take samples.
    var open = false
    var pause: ChargingStateKind? = null
    for (sample in samples) {
        val state = sample.chargingState
        when {
            state == ChargingStateKind.Charging || state == ChargingStateKind.Starting -> {
                val ended =
                    !open ||
                        pause?.let { chargeRunEndedBy(it, sample.timestampMillis - runs.last().last().timestampMillis) } == true
                if (ended) runs.add(mutableListOf())
                runs.last().add(sample)
                open = true
                pause = null
            }

            chargeRunEndedBy(state, 0) -> {
                open = false
                pause = null
            }

            else -> pause = state
        }
    }
    return runs
}

/**
 * True when a sample in [state], read after a run's last charging sample, ends that run for a charge that resumes
 * [sinceLastChargingMillis] after the run's last charging sample: any state but charging or a pause does; a pause
 * (`Stopped`, `NoPower`) does once that resume is [MAX_PAUSE_MILLIS] or more after the run, and the resuming sample
 * then opens the next run. [chargeRuns] cuts by it, and a session re-cut asks it whether a stored charge can still
 * grow.
 */
internal fun chargeRunEndedBy(
    state: ChargingStateKind?,
    sinceLastChargingMillis: Long,
): Boolean =
    when (state) {
        ChargingStateKind.Charging, ChargingStateKind.Starting -> false
        ChargingStateKind.Stopped, ChargingStateKind.NoPower -> sinceLastChargingMillis >= MAX_PAUSE_MILLIS
        else -> true
    }
