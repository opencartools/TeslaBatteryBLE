// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.goodEnoughForTrips
import com.dzid26.teslable.core.history.DriveSession
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.Trip
import com.dzid26.teslable.core.history.TripSummary
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.core.history.summary
import com.dzid26.teslable.core.units.DistanceUnit
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The figures a picked row opens in place, under it in the Sessions card. They are today's past-trip and charge
 * figures; the redesigned stats panel (level bar and fixed grid) replaces them in a later step of
 * `proposals/sessions-and-chart.md`.
 */

/** The open row's figures: a navigated drive's trip figures, a plain drive's distance and time, or a charge's. */
@Composable
internal fun SessionPanel(
    row: SessionRowData,
    inputs: SessionInputs,
) {
    Column(Modifier.padding(start = 28.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val session = row.session) {
            is ChargeSession -> ChargePanel(row.priced ?: return@Column) { EditPriceButton(it) }
            is DriveSession -> row.trip?.let { TripPanel(it, inputs) } ?: DrivePanel(session)
        }
    }
}

/**
 * A charge's average power and duration in big type with the rated range it added under them, and at the right its
 * cost (or energy) with [priceEditor] under it. On the narrowest phones everything stacks instead, the editor still
 * under the price. The row above already shows the levels it went between.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChargePanel(
    priced: PricedSession,
    priceEditor: @Composable (PricedSession) -> Unit = {},
) {
    val session = priced.session
    val rated: @Composable () -> Unit = {
        session.milesAddedRated?.let {
            Text(
                stringResource(R.string.charge_session_miles_added, LocalDistanceUnit.current.format(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val stats: @Composable () -> Unit = {
        session.averageKw?.let { TripStat("${formatKw(it)}kW", stringResource(R.string.charge_session_average_label)) }
        compactDuration(session.durationMillis)?.let { TripStat(it, stringResource(R.string.charge_session_duration_label)) }
    }
    BoxWithConstraints {
        if (maxWidth >= WIDE_PANEL) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { stats() }
                    rated()
                }
                Column(horizontalAlignment = Alignment.End) {
                    ChargeAmount(priced)
                    priceEditor(priced)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The figures wrap whole instead of squeezing the digits.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { stats() }
                ChargeAmount(priced)
                priceEditor(priced)
                rated()
            }
        }
    }
}

/** The width from which a charge's power and duration sit beside its cost rather than wrapping. */
private val WIDE_PANEL = 240.dp

/** The charge's cost over the energy it bought ("total" for an entered total), or its energy without a price. */
@Composable
private fun ChargeAmount(priced: PricedSession) {
    val cost = priced.cost
    val currencyCode = priced.currencyCode ?: LocalCurrencyCode.current
    val kwh = priced.session.energyAddedKwh?.let(::formatKwh)
    when {
        cost != null && priced.price is SessionPrice.Total ->
            TripStat(
                formatMoney(cost, currencyCode),
                kwh?.let { stringResource(R.string.charge_session_total_kwh_label, it) }
                    ?: stringResource(R.string.charge_session_total_label),
            )
        cost != null && kwh != null -> TripStat(formatMoney(cost, currencyCode), stringResource(R.string.charge_session_cost_label, kwh))
        kwh != null -> TripStat("${kwh}kWh", stringResource(R.string.charge_session_energy_label))
    }
}

/** A drive the car navigated: the trip's distance, time and price, the arrival level and the efficiency and speed. */
@Composable
private fun TripPanel(
    trip: Trip,
    inputs: SessionInputs,
) {
    // Matches the trip against the whole log, so off the main thread; the figures show once the summary is in.
    val summary =
        rememberInBackground(null, trip, inputs.samples, inputs.scale, inputs.ratedKwhPerMile, inputs.pricePerKwh) {
            trip.summary(inputs.samples, inputs.scale, inputs.ratedKwhPerMile, inputs.pricePerKwh)
        } ?: return
    TripStats(
        distance = tripDistanceText(summary.distanceMiles),
        time = tripTimeText(trip.durationMillis),
        estimate = summary.estimate,
        currencyCode = LocalCurrencyCode.current,
        labels = pastTripLabels,
    )
    // The live trip card already says where it will arrive; the arrival line is for trips that ended.
    if (!trip.inProgress) {
        arrivalLine(trip, summary, inputs.scale)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
    }
    rateLine(summary)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.cyclesEfficiencyUnit(),
        )
    }
}

/** A drive without navigation: its distance and time in big type, and the levels it went between. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrivePanel(drive: DriveSession) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tripDistanceText(drive.distanceMiles.toFloat())?.let { TripStat(it, stringResource(pastTripLabels.distance)) }
        if (!drive.unseen) {
            tripTimeText(drive.endMillis - drive.startMillis)?.let { TripStat(it, stringResource(pastTripLabels.time)) }
        }
    }
    levelsText(drive)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
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

private fun formatKw(kw: Float): String = String.format(Locale.getDefault(), "%.1f", kw)
