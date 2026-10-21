// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.history.compactDuration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The charge sessions, one at a time: a selector by start time and energy, and for the chosen
 * session its energy, duration and average power in big type, the level it went from and to, and
 * the rated range it added. Figures come from the history as it is, with no price: a session the car
 * reported no energy for shows what it has. Shows nothing until there is a session. [sessions] are
 * oldest first; the newest is open by default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChargeSessionsCard(sessions: List<ChargeSession>) {
    if (sessions.isEmpty()) return
    var selectedStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val session = sessions.firstOrNull { it.startMillis == selectedStart } ?: sessions.last()
    val unit = LocalDistanceUnit.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.charge_sessions_title_count, sessions.size), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ChargeSessionSelector(sessions.asReversed(), session) { selectedStart = it.startMillis }
            Spacer(Modifier.height(8.dp))
            // The stats wrap whole on a narrow phone instead of squeezing the digits.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                session.energyAddedKwh?.let { TripStat("${formatKwh(it)}kWh", stringResource(R.string.charge_session_energy_label)) }
                compactDuration(session.durationMillis)?.let { TripStat(it, stringResource(R.string.charge_session_duration_label)) }
                session.averageKw?.let { TripStat("${formatKw(it)}kW", stringResource(R.string.charge_session_average_label)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.charge_session_percent_range, session.startPercent.roundToInt(), session.endPercent.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
            )
            session.milesAddedRated?.let {
                Text(
                    stringResource(R.string.charge_session_miles_added, unit.format(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The chosen session as a button; a tap lists every session by start time and energy, newest first. */
@Composable
private fun ChargeSessionSelector(
    options: List<ChargeSession>,
    selected: ChargeSession,
    onSelect: (ChargeSession) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.charge_session_selector_description)
    val is24Hour = LocalIs24Hour.current ?: DateFormat.is24HourFormat(LocalContext.current)
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
            Text(sessionLabel(selected, is24Hour), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { session ->
                DropdownMenuItem(
                    text = { Text(sessionLabel(session, is24Hour), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(session)
                    },
                )
            }
        }
    }
}

/** "Oct 19, 2:05 PM · 22.5kWh", with "charging" for the live session and just the time when the car reported no energy. */
@Composable
private fun sessionLabel(
    session: ChargeSession,
    is24Hour: Boolean,
): String {
    val time = formatTime(session.startMillis, showDate = true, is24Hour = is24Hour)
    val energy = session.energyAddedKwh?.let(::formatKwh)
    return when {
        session.inProgress -> stringResource(R.string.charge_session_option_live, time)
        energy != null -> stringResource(R.string.charge_session_option, time, energy)
        else -> time
    }
}

private fun formatKw(kw: Float): String = String.format(Locale.getDefault(), "%.1f", kw)
