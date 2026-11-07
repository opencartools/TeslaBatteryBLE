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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import com.dzid26.teslable.core.health.chargeSessionCost
import com.dzid26.teslable.core.health.pricesForSessions
import com.dzid26.teslable.core.history.compactDuration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The charge sessions, one at a time: a selector by start time and cost (or energy without a price), and for
 * the chosen session its average power, duration and cost in big type (the cost last, like the trip card), the
 * level it went from and to, and the rated range it added. The cost is the energy the car added times the home
 * price in effect for that session (`pricesForSessions`); without a price the energy shows instead. A session the
 * car reported no energy for shows what it has. Shows nothing until there is a session. [sessions] are oldest
 * first; the newest is open by default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChargeSessionsCard(sessions: List<ChargeSession>) {
    if (sessions.isEmpty()) return
    var selectedStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedIndex = sessions.indexOfFirst { it.startMillis == selectedStart }.takeIf { it >= 0 } ?: sessions.lastIndex
    val session = sessions[selectedIndex]
    val unit = LocalDistanceUnit.current
    val currencyCode = LocalCurrencyCode.current
    val priceChanges = LocalPriceChanges.current
    val costs =
        remember(sessions, priceChanges) {
            sessions.zip(pricesForSessions(priceChanges, sessions)) { charge, price -> chargeSessionCost(charge.energyAddedKwh, price) }
        }
    val cost = costs[selectedIndex]
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.charge_sessions_title_count, sessions.size), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ChargeSessionSelector(sessions.zip(costs).asReversed(), session, cost, currencyCode) { selectedStart = it.startMillis }
            Spacer(Modifier.height(8.dp))
            // The stats wrap whole on a narrow phone instead of squeezing the digits.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                session.averageKw?.let { TripStat("${formatKw(it)}kW", stringResource(R.string.charge_session_average_label)) }
                compactDuration(session.durationMillis)?.let { TripStat(it, stringResource(R.string.charge_session_duration_label)) }
                // Last, like the trip card: its cost (or energy) closes the row.
                val kwh = session.energyAddedKwh?.let(::formatKwh)
                when {
                    cost != null && kwh != null ->
                        TripStat(
                            formatMoney(cost, currencyCode),
                            stringResource(R.string.charge_session_cost_label, kwh),
                        )
                    kwh != null -> TripStat("${kwh}kWh", stringResource(R.string.charge_session_energy_label))
                }
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

/** The chosen session as a button; a tap lists every session (with its cost, or null) by start time and cost, newest first. */
@Composable
private fun ChargeSessionSelector(
    options: List<Pair<ChargeSession, Float?>>,
    selected: ChargeSession,
    selectedCost: Float?,
    currencyCode: String?,
    onSelect: (ChargeSession) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.charge_session_selector_description)
    val is24Hour = LocalIs24Hour.current ?: DateFormat.is24HourFormat(LocalContext.current)
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }) {
            Text(
                sessionLabel(selected, selectedCost, currencyCode, is24Hour),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (session, cost) ->
                DropdownMenuItem(
                    text = { Text(sessionLabel(session, cost, currencyCode, is24Hour), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(session)
                    },
                )
            }
        }
    }
}

/**
 * "Oct 19, 2:05 PM · $6.92", or "· 22.5kWh" without a price, with "Charging" for the live session and just the
 * time when the car reported no energy.
 */
@Composable
private fun sessionLabel(
    session: ChargeSession,
    cost: Float?,
    currencyCode: String?,
    is24Hour: Boolean,
): String {
    val time = formatTime(session.startMillis, showDate = true, is24Hour = is24Hour)
    val energy = session.energyAddedKwh?.let(::formatKwh)
    return when {
        session.inProgress -> stringResource(R.string.charge_session_option_live, time)
        cost != null -> stringResource(R.string.charge_session_option_cost, time, formatMoney(cost, currencyCode))
        energy != null -> stringResource(R.string.charge_session_option, time, energy)
        else -> time
    }
}

private fun formatKw(kw: Float): String = String.format(Locale.getDefault(), "%.1f", kw)
