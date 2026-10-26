// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.units

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * How an energy rate reads. The car and the app keep it as kWh per rated mile;
 * these are the three forms drivers actually use, each following the chosen
 * [DistanceUnit]: Wh per distance (the car's own display, and what TeslaMate and
 * ABRP show), kWh per 100 distance (the EU/WLTP and EPA labels) and distance per
 * kWh (mi/kWh, km/kWh).
 */
enum class EfficiencyUnit {
    // Stored by name in the unit preference: do not rename.
    ENERGY_PER_DISTANCE,
    ENERGY_PER_100_DISTANCE,
    DISTANCE_PER_ENERGY,
    ;

    /**
     * [kwhPerMile] in this form for [distance], such as "274 Wh/mi", "17.0 kWh/100 km" or "3.65 mi/kWh";
     * null when the rate is not a positive finite number. Gaps are no-break spaces and the slash is followed by a
     * word joiner, so a wrapping line never splits the unit.
     */
    fun format(
        kwhPerMile: Float,
        distance: DistanceUnit,
        decimalSeparator: Char = '.',
    ): String? {
        if (!kwhPerMile.isFinite() || kwhPerMile <= 0f) return null
        val kwhPerUnit = distance.energyPerUnit(kwhPerMile)
        return when (this) {
            ENERGY_PER_DISTANCE -> "${(kwhPerUnit * WH_PER_KWH).roundToInt()}Wh/$JOIN${distance.symbol}"
            ENERGY_PER_100_DISTANCE ->
                "${fixed(kwhPerUnit * PER_HUNDRED, 1, decimalSeparator)}kWh/${JOIN}100${distance.symbol}"
            DISTANCE_PER_ENERGY -> "${fixed(1f / kwhPerUnit, 2, decimalSeparator)}${distance.symbol}/${JOIN}kWh"
        }
    }
}

/** U+2060: forbids a line break at the slash it follows. */
private const val JOIN = '\u2060'

/** A positive [value] with exactly [decimals] places and the caller's separator. */
private fun fixed(
    value: Float,
    decimals: Int,
    separator: Char,
): String {
    var scale = 1L
    repeat(decimals) { scale *= DECIMAL_BASE }
    val scaled = (value * scale).roundToLong()
    return "${scaled / scale}$separator${(scaled % scale).toString().padStart(decimals, '0')}"
}

private const val WH_PER_KWH = 1000f
private const val PER_HUNDRED = 100f
private const val DECIMAL_BASE = 10L
