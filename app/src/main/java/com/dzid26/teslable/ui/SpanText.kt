// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.core.units.DistanceUnit
import java.util.Locale
import kotlin.math.abs

/*
 * The hint while a held press selects a span on a history chart: the change in level across it, its length, and the
 * rate, read left to right whichever way the finger went.
 */

/** "−12.4% · 3h5m · −4.0%/h" for a span on the time axis, levels in percent. */
internal fun timeSpanText(
    from: Pair<Long, Float>,
    to: Pair<Long, Float>,
): String {
    val (start, end) = if (from.first <= to.first) from to to else to to from
    val millis = end.first - start.first
    val change = end.second - start.second
    val length = compactDuration(millis) ?: "<1min"
    val hours = millis / MILLIS_PER_HOUR
    val rate = if (hours > 0.0) " · ${signed(change / hours, 1)}%/h" else ""
    return "${signed(change, 1)}% · $length$rate"
}

/** "−6.1% · 12.4mi · −0.49%/mi" for a span on the distance axis: distances in the unit shown, [distanceText] formatted. */
internal fun distanceSpanText(
    from: Pair<Double, Float>,
    to: Pair<Double, Float>,
    distanceText: (Double) -> String,
    unitSymbol: String,
): String {
    val (start, end) = if (from.first <= to.first) from to to else to to from
    val distance = end.first - start.first
    val change = end.second - start.second
    val rate = if (distance > 0.0) " · ${signed(change / distance, 2)}%/$unitSymbol" else ""
    return "${signed(change, 1)}% · ${distanceText(distance)}$rate"
}

/** [value] to [decimals] places with its sign: "+4.0", "−12.4" (a true minus), "0.0". */
private fun signed(
    value: Double,
    decimals: Int,
): String {
    val digits = String.format(Locale.getDefault(), "%.${decimals}f", abs(value))
    val zero = String.format(Locale.getDefault(), "%.${decimals}f", 0.0)
    return when {
        digits == zero -> digits
        value < 0 -> "−$digits"
        else -> "+$digits"
    }
}

private fun signed(
    value: Float,
    decimals: Int,
): String = signed(value.toDouble(), decimals)

/** The hint for a span selected on the distance axis: the change, the distance and the rate per [unit]. */
@Composable
internal fun rememberDriveSpan(unit: DistanceUnit): SpanDescriber =
    remember(unit) {
        SpanDescriber { from, to ->
            distanceSpanText(
                from.first to from.second.toFloat(),
                to.first to to.second.toFloat(),
                { formatDistance(it, unit, DISTANCE_DECIMALS) },
                unit.symbol,
            )
        }
    }

private const val MILLIS_PER_HOUR = 3_600_000.0
