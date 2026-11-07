// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.DriveSession
import com.dzid26.teslable.core.history.DriveWindow
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.core.history.ProjectionContext
import com.dzid26.teslable.core.history.Session
import com.dzid26.teslable.history.FullRangeScales

/** What the history card's x-axis measures: time, or distance driven. */
internal enum class HistoryAxis { TIME, DISTANCE }

/**
 * The moment the history card measures up to, and where it opens: its axis, the time range and the distance window,
 * and the sessions it is linked with. Production passes the ticking clock and opens on the defaults; the Paparazzi
 * snapshots pin them all.
 */
internal data class HistoryView(
    val nowMillis: Long = System.currentTimeMillis(),
    val axis: HistoryAxis = HistoryAxis.TIME,
    val range: HistoryRange = HistoryRange.DAY,
    val window: DriveWindow = DriveWindow.MEDIUM,
    val link: SessionLink = SessionLink(),
)

/**
 * The chart's half of the link with the Sessions card: the drives and charges it shows as bands (oldest first), the
 * one picked in the list, which the chart zooms to, and [onSelect] for a tap on a band, or null when a range chip
 * takes the chart back to its overview.
 */
internal class SessionLink(
    val sessions: List<Session> = emptyList(),
    val selected: Session? = null,
    val onSelect: (Session?) -> Unit = {},
)

/**
 * Battery history, one chart with two axes: over time (the range chips, projections and the last charge) or over
 * distance driven (the distance windows, regen and the drive figures). A switch in the header picks the axis; each
 * axis keeps its own chips, so switching back finds the range where it was left. A session picked in the list zooms
 * the chart to it, a drive on distance and a charge on time; the switch still moves between the two.
 */
@Composable
internal fun HistoryCard(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    // The fitted full-range scale (the car screen fits it once for all its cards), and the status log and the Sentry and
    // climate readings for the sleep drain rate.
    context: ProjectionContext = ProjectionContext(scale = remember(samples) { FullRangeScales.of(samples) }),
    // The learned rated constant (kWh per rated mile); null hides the energy and efficiency figures.
    ratedKwhPerMile: Float? = null,
    view: HistoryView = HistoryView(),
) {
    var axis by rememberSaveable { mutableStateOf(view.axis) }
    // Held here, not in each axis, so switching to the other axis and back finds the range where it was left and its
    // chart already worked out: the axis not shown leaves the composition, and would otherwise start from nothing.
    val range = rememberSaveable { mutableStateOf(view.range) }
    val window = rememberSaveable { mutableStateOf(view.window) }
    val time = remember { TimeAxisMemory(range) }
    val distance = remember { DistanceAxisMemory(window) }
    val focus = view.link.selected
    LaunchedEffect(focus?.id) {
        focus?.let { axis = if (it is DriveSession) HistoryAxis.DISTANCE else HistoryAxis.TIME }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Battery history", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AxisSwitch(axis) { axis = it }
            }
            when (axis) {
                HistoryAxis.TIME -> TimeHistory(samples, drives, view, time, context)
                HistoryAxis.DISTANCE -> DistanceHistory(samples, drives, distance, view.link, EnergyScale(context.scale, ratedKwhPerMile))
            }
        }
    }
}

/** Time or Distance, as a two-part segmented button. */
@Composable
private fun AxisSwitch(
    axis: HistoryAxis,
    onAxis: (HistoryAxis) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        HistoryAxis.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = axis == entry,
                onClick = { onAxis(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, HistoryAxis.entries.size),
                icon = {},
            ) {
                Text(
                    stringResource(if (entry == HistoryAxis.TIME) R.string.history_axis_time else R.string.history_axis_distance),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
