// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.health.TripEstimate
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.core.history.trafficMinutes
import com.dzid26.teslable.core.history.trafficShare
import java.text.DecimalFormatSymbols

/** The string ids naming the distance and time figures: what is left of a live trip, what a past one took. */
internal class TripLabels(
    @StringRes val distance: Int,
    @StringRes val time: Int,
    // The traffic delay the car counts in the time, which tints the time figure and explains itself in a tip; null for none.
    val traffic: TripTraffic? = null,
)

/** A traffic delay of [minutes], which is [share] (0 to 1) of the whole trip's time. */
internal class TripTraffic(
    val minutes: Int,
    val share: Float,
)

internal fun liveTripLabels(route: RouteSample): TripLabels {
    val minutes = route.trafficMinutes()
    val traffic = minutes?.let { TripTraffic(it, route.trafficShare() ?: 0f) }
    return TripLabels(R.string.trip_distance_label, R.string.trip_time_label, traffic)
}

internal val pastTripLabels = TripLabels(R.string.trip_past_distance_label, R.string.trip_past_time_label)

/** "4.7 mi" from miles, or null when there are none. */
@Composable
internal fun tripDistanceText(miles: Float?): String? {
    val unit = LocalDistanceUnit.current
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    return miles?.let { unit.format(it, decimals = 1, decimalSeparator = separator) }
}

/** "12m" from a duration in millis, "<1min" below one, null for none or a negative one. */
@Composable
internal fun tripTimeText(millis: Long?): String? =
    millis?.takeIf { it >= 0L }?.let { compactDuration(it) ?: stringResource(R.string.trip_under_a_minute) }

/** The three figures worth a glance, big: distance, time and the price (the energy without one). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TripStats(
    distance: String?,
    time: String?,
    estimate: TripEstimate,
    currencyCode: String?,
    labels: TripLabels,
) {
    val kwh = estimate.energyKwh?.let(::formatKwh)
    val cost = estimate.costAtHomePrice?.let { formatMoney(it, currencyCode) }
    // The stats wrap whole on a narrow phone instead of squeezing the digits.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(STAT_GAP), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        distance?.let { TripStat(it, stringResource(labels.distance)) }
        time?.let { TripStat(it, stringResource(labels.time), labels.traffic) }
        when {
            cost != null && kwh != null -> TripStat(cost, stringResource(R.string.trip_cost_label, kwh))
            kwh != null -> TripStat("${kwh}kWh", stringResource(R.string.trip_energy_label))
        }
    }
}

@Composable
internal fun TripStat(
    value: String,
    label: String,
    traffic: TripTraffic? = null,
) {
    val style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold)
    Column {
        if (traffic == null) {
            Text(text = value, style = style, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        } else {
            // The time the car blames partly on traffic: amber, turning red as traffic is more of the trip, with a tip naming the delay.
            val delay = compactDuration(traffic.minutes * MILLIS_PER_MINUTE).orEmpty()
            HintText(value, stringResource(R.string.trip_traffic, delay), trafficColor(traffic.share), style)
        }
        // A long label wraps under its figure instead of widening the column and pushing the next figure down a row.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = STAT_LABEL_MAX_WIDTH),
        )
    }
}

/** Amber for a little traffic, shading to red as the delay nears [RED_TRAFFIC_SHARE] of the whole trip's time. */
private fun trafficColor(share: Float): Color = lerp(AMBER, RED, (share / RED_TRAFFIC_SHARE).coerceIn(0f, 1f))

private val AMBER = Color(0xFFFFB300)
private val RED = Color(0xFFE53935)
private const val RED_TRAFFIC_SHARE = 0.3f
private const val MILLIS_PER_MINUTE = 60_000L
private val STAT_GAP = 16.dp
private val STAT_LABEL_MAX_WIDTH = 112.dp
