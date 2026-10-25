// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.health.TripEstimate
import com.dzid26.teslable.core.health.tripEstimate
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.tripLowFrom
import com.dzid26.teslable.core.history.tripProgress
import com.dzid26.teslable.core.history.tripTotalMillis
import com.dzid26.teslable.core.units.DistanceUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The trip the car is navigating right now (#207): how far along it is, what is
 * left of the route in big type and what the car predicts for the arrival. Shown
 * only while a route is live; a number the car or the app cannot supply hides
 * its figure instead of showing a guess. The destination is read live and never
 * stored.
 */
@Composable
internal fun TripCard(
    route: RouteSample,
    estimate: TripEstimate,
    currentPercent: Float?,
    modifier: Modifier = Modifier,
) {
    val unit = LocalDistanceUnit.current
    val currencyCode = LocalCurrencyCode.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val destination = route.destination?.takeIf { it.isNotBlank() }
            Text(
                text = destination?.let { stringResource(R.string.trip_title_to, it) } ?: stringResource(R.string.trip_title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            TripStats(
                distance = tripDistanceText(estimate.milesRemaining),
                time = tripTimeText(route.minutesToArrival?.takeIf { it.isFinite() }?.let { (it * MILLIS_PER_MINUTE).toLong() }),
                estimate = estimate,
                currencyCode = currencyCode,
                labels = liveTripLabels(route),
            )
            route.tripProgress()?.let { progress ->
                Spacer(Modifier.height(8.dp))
                TripBar(progress, route.tripLowFrom(currentPercent))
            }
            arrivalText(estimate, unit)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
            totalText(route)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Trip total: 11.4 mi · 26m": the whole route's length and time, of which the big figures are what is left. */
@Composable
private fun totalText(route: RouteSample): String? {
    val distance = tripDistanceText(route.tripStartMiles)
    val time = tripTimeText(route.tripTotalMillis())
    return listOfNotNull(distance, time).takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { stringResource(R.string.trip_total, it) }
}

/** "Arrive with 44% (about 118 mi)": the car's predicted level, with the rated range it stands for once the full range is known. */
@Composable
internal fun arrivalText(
    estimate: TripEstimate,
    unit: DistanceUnit,
): String? {
    val percent = estimate.arrivalPercent?.roundToInt() ?: return null
    val ratedMiles = estimate.arrivalRatedMiles
    return if (ratedMiles == null) {
        stringResource(R.string.trip_arrival, percent)
    } else {
        stringResource(R.string.trip_arrival_range, percent, unit.format(ratedMiles))
    }
}

internal fun formatKwh(kwh: Float): String = String.format(Locale.getDefault(), "%.1f", kwh)

internal fun formatMoney(
    amount: Float,
    currencyCode: String?,
): String = currencyFormat(currencyCode).format(amount.toDouble())

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * The trip card for a live [route] (see [activeRoute]), nothing for null. The
 * numbers come from the newest battery reading, the fitted full-range scale
 * and, when set, the home energy price.
 */
@Composable
internal fun TripSection(
    route: RouteSample?,
    latest: BatterySample?,
    scale: FullRangeScale?,
    ratedKwhPerMile: Float?,
    pricePerKwh: Float?,
) {
    if (route == null) return
    // The card is for glancing at while driving: keep the screen on and undimmed while it shows.
    KeepScreenOn()
    TripCard(route, tripEstimate(route, latest?.ratedRangeMiles, scale, ratedKwhPerMile, pricePerKwh), latest?.batteryLevel?.toFloat())
}
