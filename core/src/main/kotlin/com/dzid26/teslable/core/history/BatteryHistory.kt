// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind

/**
 * One battery reading, recorded whenever a car reports charge state.
 *
 * Holds just the fields the app reads, taken from the car's raw `ChargeState`
 * by [toBatterySample]; the raw record stays in the log (ADR-0008). Display
 * values are derived at runtime.
 */
data class BatterySample(
    val timestampMillis: Long,
    /** Displayed SOC (`battery_level`). */
    val batteryLevel: Int,
    val chargingState: ChargingStateKind?,
    val chargeLimit: Int?,
    /**
     * Which vehicle the reading came from: the advertised BLE name
     * (`S<sha1(VIN)[:8]>C`), which is stable before a VIN is known.
     */
    val vehicleId: String = "",
    /** `battery_range`: rated range in miles at this SOC. */
    val ratedRangeMiles: Float? = null,
    /** `charge_energy_added`: kWh added so far this session; 0 when idle. */
    val chargeEnergyAdded: Float? = null,
    /** `charge_miles_added_rated`: rated miles added so far this session. */
    val chargeMilesAddedRated: Float? = null,
    /** `charger_voltage`: the AC supply voltage in volts while charging; null when absent. */
    val chargerVoltage: Int? = null,
    /** `charge_rate_mph`: the car's own charging speed in rated miles per hour; null when absent or not charging. */
    val chargeRateMph: Float? = null,
    /** `fast_charger_present`: true while a DC fast charger is connected; null when the car sent none. */
    val fastChargerPresent: Boolean? = null,
    /**
     * `usable_battery_level`: the level the rated range stands for. The car holds back part of a cold
     * battery, so it reads up to a point below [batteryLevel]. Defaults to the displayed level when the car sent none.
     */
    val usableLevel: Int = batteryLevel,
    /**
     * The phone's clock when the reply was read (the record's `device_timestamp`),
     * or null when the record has none. This is what "last read" means, and it
     * is the only time the app shows as a reading's age: [timestampMillis] is
     * the car's own clock, which places the sample on the chart but says
     * nothing reliable about when the phone last heard from the car.
     */
    val readAtMillis: Long? = null,
) {
    val isCharging: Boolean get() = chargingState == ChargingStateKind.Charging
}

/** Time window for the battery graph. */
enum class HistoryRange(
    val durationMillis: Long?,
) {
    SIX_HOURS(6 * 60 * 60 * 1000L),
    DAY(24 * 60 * 60 * 1000L),
    WEEK(7 * 24 * 60 * 60 * 1000L),
    MONTH(MONTH_DAYS * DAY_MILLIS),
    ALL(null),
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

private const val MONTH_DAYS = 30L

fun List<BatterySample>.within(
    range: HistoryRange,
    nowMillis: Long,
): List<BatterySample> {
    val duration = range.durationMillis ?: return this
    val cutoff = nowMillis - duration
    return filter { it.timestampMillis >= cutoff }
}

/**
 * Summary since the last charge: the level it reached, and the drain since.
 *
 * Kept in rated miles, the car's own unit: the drain is a difference of two
 * rated-range readings, so it is exact to the reading's resolution, where the
 * whole-percent level steps by up to a point. Percent is derived at the end
 * with [chargePercent], [currentPercent] and [usedPercent] from the fitted
 * [FullRangeScale]. The whole-percent levels stay as the fallback for samples
 * with no rated range, or while the scale is not pinned.
 */
data class ChargeStats(
    val sinceMillis: Long,
    /** The rated range at the end of the charge (the newest charging sample); null when it has none. */
    val chargeMiles: Float?,
    val currentMiles: Float?,
    /** The displayed whole-percent levels, the fallback for the percent figures. */
    val chargeLevel: Int,
    val currentLevel: Int,
    /** The car's `charge_energy_added` on the newest charging sample: the kWh that session added; null when it reported none. */
    val energyKwh: Float? = null,
) {
    /** Rated miles drained since the charge; null when either end has no rated range. */
    val usedMiles: Float?
        get() = if (chargeMiles != null && currentMiles != null) (chargeMiles - currentMiles).coerceAtLeast(0f) else null

    /** The level the charge ended at, in percent: sub-percent with a pinned [scale], else the whole level. */
    fun chargePercent(scale: FullRangeScale?): Float = scale?.socPercent(chargeMiles) ?: chargeLevel.toFloat()

    fun currentPercent(scale: FullRangeScale?): Float = scale?.socPercent(currentMiles) ?: currentLevel.toFloat()

    /** Percent used since the charge: the drained miles over the scale's miles per percent, else the level difference. */
    fun usedPercent(scale: FullRangeScale?): Float {
        val miles = usedMiles
        val milesPerPercent = scale?.takeIf { it.pinned }?.milesPerPercent
        return if (miles != null && milesPerPercent != null && milesPerPercent > 0f) {
            miles / milesPerPercent
        } else {
            (chargeLevel - currentLevel).coerceAtLeast(0).toFloat()
        }
    }
}

/**
 * Stats since the last charge ended, referenced to the charge itself: its
 * newest charging sample supplies both the time and the level, and the
 * samples after it supply the drain. The first sample recorded after the
 * charge is *not* the reference: the app can see nothing for a while (the car
 * asleep, the phone away), so that sample may be days after the charge and at
 * a level the car already fell to. Returns null when nothing was charged yet
 * or while the car is still charging. The samples need not be in time order.
 */
fun chargeStats(samples: List<BatterySample>): ChargeStats? {
    val charged = samples.filter { it.isCharging }.maxByOrNull { it.timestampMillis } ?: return null
    val newest = samples.maxByOrNull { it.timestampMillis } ?: return null
    if (newest.timestampMillis <= charged.timestampMillis) return null
    return ChargeStats(
        sinceMillis = charged.timestampMillis,
        chargeMiles = charged.ratedRangeMiles,
        currentMiles = newest.ratedRangeMiles,
        chargeLevel = charged.batteryLevel,
        currentLevel = newest.batteryLevel,
        energyKwh = charged.chargeEnergyAdded?.takeIf { it > 0f },
    )
}
