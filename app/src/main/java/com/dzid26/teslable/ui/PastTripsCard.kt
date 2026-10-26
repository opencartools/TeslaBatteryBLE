// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.health.goodEnoughForTrips
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.Trip
import com.dzid26.teslable.core.history.TripSummary
import com.dzid26.teslable.core.history.summary
import com.dzid26.teslable.core.units.DistanceUnit
import java.text.DecimalFormatSymbols
import kotlin.math.roundToInt

/**
 * The finished navigations (#207), one at a time: a selector by name and start time,
 * and for the chosen trip the same figures as the live trip card, its distance,
 * duration and price in big type and the level the car predicted for the arrival.
 * The route being navigated now is the newest entry, marked in progress, so its totals
 * show too. Shows nothing until there is a trip. [trips] are oldest first; the newest
 * is open by default.
 */
@Composable
internal fun PastTripsCard(
    trips: List<Trip>,
    samples: List<BatterySample>,
    scale: FullRangeScale?,
    ratedKwhPerMile: Float?,
    pricePerKwh: Float?,
) {
    if (trips.isEmpty()) return
    var selectedStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val trip = trips.firstOrNull { it.startMillis == selectedStart } ?: trips.last()
    // Matches the trip against the whole log, so off the main thread; the card shows once the first summary is in.
    val summary =
        rememberInBackground(null, trip, samples, scale, ratedKwhPerMile, pricePerKwh) {
            trip.summary(samples, scale, ratedKwhPerMile, pricePerKwh)
        } ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.trip_past_title_count, trips.size), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TripSelector(trips.asReversed(), trip) { selectedStart = it.startMillis }
            Spacer(Modifier.height(8.dp))
            TripStats(
                distance = tripDistanceText(summary.distanceMiles),
                time = tripTimeText(trip.durationMillis),
                estimate = summary.estimate,
                currencyCode = LocalCurrencyCode.current,
                labels = pastTripLabels,
            )
            // The live trip card already says where it will arrive; the arrival line is for trips that ended.
            if (!trip.inProgress) {
                arrivalLine(trip, summary, scale)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            }
            rateLine(
                summary,
            )?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.cyclesEfficiencyUnit(),
                )
            }
        }
    }
}

/** "Arrived with 44% (about 132 mi left)", or "Ended with ..." when the trip stopped short. */
@Composable
private fun arrivalLine(
    trip: Trip,
    summary: TripSummary,
    scale: FullRangeScale?,
): String? {
    val level = summary.arrivalPercent ?: return null
    val percent = level.roundToInt()
    val unit = LocalDistanceUnit.current
    val ratedMiles = scale?.takeIf { it.goodEnoughForTrips() }?.let { it.ratedMilesAt(level) }
    // "Arrived" at the destination, "Ended" when the trip stopped short of it.
    val ids =
        when {
            trip.arrived -> R.string.trip_arrived to R.string.trip_arrived_range
            else -> R.string.trip_ended to R.string.trip_ended_range
        }
    return if (ratedMiles == null) stringResource(ids.first, percent) else stringResource(ids.second, percent, unit.format(ratedMiles))
}

/** "312 Wh/mi · 34 mi/h average", whichever of the two the trip supports. */
@Composable
private fun rateLine(summary: TripSummary): String? {
    val unit = LocalDistanceUnit.current
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val efficiency = summary.kwhPerMile?.let { LocalEfficiencyUnit.current.format(it, unit, separator) }
    val speed =
        summary.averageMilesPerHour?.let { mph ->
            val id = if (unit == DistanceUnit.MILES) R.string.trip_speed_mph else R.string.trip_speed_kmh
            stringResource(id, unit.fromMiles(mph).roundToInt())
        }
    return when {
        efficiency != null && speed != null -> stringResource(R.string.trip_rate_speed, efficiency, speed)
        else -> efficiency ?: speed?.let { stringResource(R.string.trip_average_alone, it) }
    }
}

/** The chosen trip as a button; a tap lists every trip by name and start time, newest first. */
@Composable
private fun TripSelector(
    options: List<Trip>,
    selected: Trip,
    onSelect: (Trip) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.trip_past_selector_description)
    val is24Hour = LocalIs24Hour.current ?: DateFormat.is24HourFormat(LocalContext.current)
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
            Text(tripLabel(selected, is24Hour), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { trip ->
                DropdownMenuItem(
                    text = { Text(tripLabel(trip, is24Hour), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(trip)
                    },
                )
            }
        }
    }
}

/** "Home · Oct 19, 2:05 PM": the destination, or "Trip" without one, and when it began; "in progress" for the live trip. */
@Composable
private fun tripLabel(
    trip: Trip,
    is24Hour: Boolean,
): String {
    val name = trip.destination ?: stringResource(R.string.trip_title)
    return if (trip.inProgress) {
        stringResource(R.string.trip_past_option_live, name)
    } else {
        stringResource(R.string.trip_past_option, name, formatTime(trip.startMillis, showDate = true, is24Hour = is24Hour))
    }
}
