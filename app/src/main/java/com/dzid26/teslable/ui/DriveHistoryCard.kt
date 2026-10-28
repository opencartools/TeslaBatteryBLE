// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.health.DriveStats
import com.dzid26.teslable.core.health.driveStats
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.DriveWindow
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.drivePoints
import com.dzid26.teslable.core.history.totalDrop
import com.dzid26.teslable.core.units.DistanceUnit
import com.dzid26.teslable.history.FullRangeScales
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Battery percent over distance driven: the driving counterpart of the battery
 * history card, which plots percent over time. Percent is the sub-percent SOC
 * (the rated range over the fitted full range); each regenerating reading is a green
 * dot, and a rise of the percent while regenerating is a green gradient. The window is the last 15,
 * 50 or 150 km (10, 30 or 100 mi) or everything since the last charge. Each trip
 * is its own line, so a charge or a long parked gap never gets a connecting
 * stroke.
 */
@Composable
internal fun DriveHistoryCard(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    // Injectable for the Paparazzi snapshots; production opens on the middle window.
    initialWindow: DriveWindow = DriveWindow.MEDIUM,
    // The fitted full-range scale; the car screen fits it once for all its cards.
    scale: FullRangeScale? = remember(samples) { FullRangeScales.of(samples) },
    // The learned rated constant (kWh per rated mile); null hides the energy and efficiency stats.
    ratedKwhPerMile: Float? = null,
) {
    var window by rememberSaveable { mutableStateOf(initialWindow) }
    val unit = LocalDistanceUnit.current
    val pricePerKwh = LocalPricePerKwh.current
    // Up to 20k samples: matched off the main thread, and only when the data or the window changes. The card shows once
    // the first match is in; a later change keeps the previous chart up until the next one is ready.
    val figures =
        rememberInBackground(null, samples, drives, window, unit, scale, ratedKwhPerMile, pricePerKwh) {
            val points = drivePoints(samples, drives, scale, window.miles(unit), window.sinceLastCharge)
            DriveFigures(
                plotted =
                    points.map {
                        Plotted(
                            unit.fromMiles(it.milesDriven.toFloat()).toDouble(),
                            it.socPercent,
                            it.regenerating,
                            it.trip,
                            it.timestampMillis,
                        )
                    },
                used = points.totalDrop { it.socPercent },
                stats = driveStats(points, scale, ratedKwhPerMile, pricePerKwh),
            )
        } ?: return
    val plotted = figures.plotted

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Drive history", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            DriveChips(window, unit) { window = it }
            Spacer(Modifier.height(8.dp))
            if (plotted.size < 2 || plotted.last().distance <= 0.0) {
                Text(
                    text = "No drives since the last charge yet. Battery percent is plotted against distance driven.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RangeChart(points = plotted, modifier = Modifier.fillMaxWidth().height(160.dp))
                Spacer(Modifier.height(6.dp))
                Legend()
                Spacer(Modifier.height(6.dp))
                DriveFooter(
                    plotted.last().distance,
                    unit,
                    figures.used,
                    wholeStepsOnly = scale?.pinned != true,
                    stats = figures.stats,
                )
            }
        }
    }
}

/** What the drive history card plots and sums for one window. */
private class DriveFigures(
    val plotted: List<Plotted>,
    val used: Float,
    val stats: DriveStats?,
)

/** The distance-window chips: 15 / 50 / 150 km (10 / 30 / 100 mi) and since the last charge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveChips(
    window: DriveWindow,
    unit: DistanceUnit,
    onWindow: (DriveWindow) -> Unit,
) {
    // Four chips do not fit a narrow phone on one line; they wrap whole.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DriveWindow.entries.forEach { entry ->
            val label = entry.label(unit)?.let { "$it${unit.symbol}" } ?: "Since charge"
            FilterChip(selected = window == entry, onClick = { onWindow(entry) }, label = { Text(label, maxLines = 1) })
        }
    }
}

/**
 * "Driven: 12.4km · N% used · 0.82% per km": the percent rate joins the first line once known. While the full range is
 * not learned the figures are rough, and a note under the lines says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveFooter(
    driven: Double,
    unit: DistanceUnit,
    used: Float,
    wholeStepsOnly: Boolean,
    stats: DriveStats?,
) {
    val usedText = stringResource(R.string.drive_used, formatPercentTenths(used.coerceAtLeast(0f)))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.drive_driven, formatDistance(driven, unit, DISTANCE_DECIMALS)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(usedText, style = MaterialTheme.typography.bodySmall)
        stats?.let { percentRate(it, unit) }?.let { rate ->
            Text(
                text = stringResource(R.string.drive_rate_joined, stringResource(R.string.drive_percent_per_distance, rate, unit.symbol)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (stats != null) {
        DriveEnergyLine(stats, unit)
    }
    if (wholeStepsOnly) {
        Text(
            text = stringResource(R.string.drive_rough_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The line under the footer: "312 Wh/mi \u00B7 6.4 kWh \u00B7 $7.50/100mi" (these need the learned full range
 * and rated constant). A part the data cannot supply is left out; nothing shows when none is known. A tap on the
 * cost says it is at the home price.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveEnergyLine(
    stats: DriveStats,
    unit: DistanceUnit,
) {
    val efficiency = LocalEfficiencyUnit.current
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val cost = costRate(stats, unit, LocalCurrencyCode.current)?.let { stringResource(R.string.drive_cost_per_distance, it, unit.symbol) }
    val energy = stats.energyKwh?.let { stringResource(R.string.drive_energy, formatKwh(it)) }
    val rate = stats.kwhPerMile?.let { efficiency.format(it, unit, separator) }
    val plain = listOfNotNull(rate, energy)
    val style = MaterialTheme.typography.bodySmall
    val dot = " \u00B7"
    // A tap on the line cycles the efficiency unit; the cost tip keeps its own tap.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.cyclesEfficiencyUnit()) {
        plain.forEachIndexed { index, part ->
            Text(if (index < plain.lastIndex || cost != null) part + dot else part, style = style)
        }
        cost?.let { TipText(it, stringResource(R.string.drive_cost_tip), style) }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem("Battery %", driveChartColor)
        LegendItem("Regen", chargingChartColor)
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: Color,
) {
    Text(
        text = "● $label",
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** A distance already in [unit], shown with the unit's symbol by the shared formatter. */
internal fun formatDistance(
    distance: Double,
    unit: DistanceUnit,
    decimals: Int = 0,
): String = unit.format((distance / unit.fromMiles(1f)).toFloat(), decimals, DecimalFormatSymbols.getInstance().decimalSeparator)

/** Cursor labels and the drive footer show distances and ranges to a tenth. */
internal const val DISTANCE_DECIMALS = 1

internal fun formatWholePercent(percent: Float): String = String.format(Locale.getDefault(), "%.0f%%", percent)

private fun formatPercentTenths(percent: Float): String = String.format(Locale.getDefault(), "%.1f", percent)
