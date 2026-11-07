// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.ChargeSessionPrice
import com.dzid26.teslable.core.health.ChargerKind
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.cost
import com.dzid26.teslable.core.health.sessionPrices
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.resolvedCurrencyCode
import java.math.BigDecimal
import java.math.RoundingMode
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
@Composable
internal fun ChargeSessionsCard(sessions: List<ChargeSession>) {
    if (sessions.isEmpty()) return
    var selectedStart by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedIndex = sessions.indexOfFirst { it.startMillis == selectedStart }.takeIf { it >= 0 } ?: sessions.lastIndex
    val session = sessions[selectedIndex]
    val settingsCurrency = LocalCurrencyCode.current
    val priceChanges = LocalPriceChanges.current
    val pricing = LocalChargePricing.current
    val stored = pricing?.stored.orEmpty()
    // Each session's price, owner's edit first; an edited one stays in the currency it was set in.
    val priced =
        remember(sessions, priceChanges, stored) {
            sessions.zip(sessionPrices(sessions, stored, priceChanges)) { session, price -> PricedSession(session, price) }
        }
    val currencyCode = priced[selectedIndex].currencyCode ?: settingsCurrency
    // Saved, so the dialog stays open when the phone turns.
    var editing by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.charge_sessions_title_count, sessions.size), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ChargeSessionSelector(priced.asReversed(), settingsCurrency, selectedIndex) { selectedStart = it.startMillis }
            Spacer(Modifier.height(8.dp))
            ChargeSessionStats(priced[selectedIndex], currencyCode)
            if (pricing != null && !session.inProgress) {
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.charge_session_edit_price)) }
            }
        }
    }
    if (editing && pricing != null) {
        val picked = priced[selectedIndex]
        EditPriceDialog(
            picked = picked,
            currencyCode = picked.currencyCode ?: resolvedCurrencyCode(settingsCurrency),
            // A fast charge is never at home: no home price to offer there.
            homePerKwh = priceChanges.lastOrNull()?.pricePerKwh?.takeIf { session.chargerKind != ChargerKind.DC },
            onSave = { price ->
                editing = false
                pricing.onSet(session, price, picked.currencyCode ?: resolvedCurrencyCode(settingsCurrency), null)
            },
            onDismiss = { editing = false },
        )
    }
}

/** The chosen session's average power, duration and cost (or energy) in big type, then its level range and rated range added. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargeSessionStats(
    priced: PricedSession,
    currencyCode: String?,
) {
    val session = priced.session
    val cost = priced.cost
    val unit = LocalDistanceUnit.current
    Column {
        // The stats wrap whole on a narrow phone instead of squeezing the digits.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            session.averageKw?.let { TripStat("${formatKw(it)}kW", stringResource(R.string.charge_session_average_label)) }
            compactDuration(session.durationMillis)?.let { TripStat(it, stringResource(R.string.charge_session_duration_label)) }
            // Last, like the trip card: its cost (or energy) closes the row.
            val kwh = session.energyAddedKwh?.let(::formatKwh)
            when {
                cost != null && priced.price is SessionPrice.Total ->
                    TripStat(
                        formatMoney(cost, currencyCode),
                        kwh?.let { stringResource(R.string.charge_session_total_kwh_label, it) }
                            ?: stringResource(R.string.charge_session_total_label),
                    )
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

/** A session with the price it is billed at (and the currency of an edit or stored price; null is the settings one). */
private class PricedSession(
    val session: ChargeSession,
    private val billed: ChargeSessionPrice,
) {
    val price get() = billed.price
    val currencyCode get() = billed.currencyCode
    val place get() = billed.place
    val edited get() = billed.byOwner
    val cost get() = price?.cost(session.energyAddedKwh)
}

/** A number for an edit box: up to four decimals, no trailing zeros, "" for none. */
internal fun editText(value: Float?): String =
    value
        ?.takeIf { it.isFinite() }
        ?.let { BigDecimal(it.toDouble()).setScale(EDIT_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() }
        .orEmpty()

internal fun parseEdit(text: String): Float? =
    text
        .trim()
        .replace(',', '.')
        .toFloatOrNull()
        ?.takeIf { it.isFinite() && it >= 0f }

/**
 * Sets a session's price per kWh or its total. Each has its own box, filled from the session's current price: the
 * total box holds its cost. [homePerKwh] (the home price set now) fills the per-kWh box from a button that names it ("Home $0.30").
 */
@Composable
private fun EditPriceDialog(
    picked: PricedSession,
    currencyCode: String,
    homePerKwh: Float?,
    onSave: (SessionPrice) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = picked.price
    val kwh = picked.session.energyAddedKwh?.takeIf { it > 0f }
    var total by rememberSaveable { mutableStateOf(current is SessionPrice.Total) }
    var perKwhText by rememberSaveable {
        mutableStateOf(editText((current as? SessionPrice.PerKwh)?.pricePerKwh ?: kwh?.let { picked.cost?.div(it) }))
    }
    var totalText by rememberSaveable { mutableStateOf(editText(picked.cost)) }
    val value = parseEdit(if (total) totalText else perKwhText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.charge_session_edit_price)) },
        text = {
            Column {
                Row {
                    FilterChip(
                        selected = !total,
                        onClick = { total = false },
                        label = { Text(stringResource(R.string.charge_session_price_per_kwh)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = total,
                        onClick = { total = true },
                        label = { Text(stringResource(R.string.charge_session_price_total)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Two boxes, one shown: switching keeps what was typed in each.
                OutlinedTextField(
                    value = if (total) totalText else perKwhText,
                    onValueChange = { if (total) totalText = it else perKwhText = it },
                    singleLine = true,
                    isError = value == null && (if (total) totalText else perKwhText).isNotBlank(),
                    suffix = { Text(if (total) currencyCode else "$currencyCode/kWh") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null,
                onClick = { value?.let { onSave(if (total) SessionPrice.Total(it) else SessionPrice.PerKwh(it)) } },
            ) { Text(stringResource(R.string.charge_session_price_save)) }
        },
        dismissButton = {
            Row {
                homePerKwh?.let { latest ->
                    TextButton(onClick = {
                        total = false
                        perKwhText = editText(latest)
                    }) { Text(stringResource(R.string.charge_session_price_use_home, formatMoney(latest, currencyCode))) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

private const val EDIT_DECIMALS = 4

/** The chosen session as a button; a tap lists every session (with its cost, or null) by start time and cost, newest first. */
@Composable
private fun ChargeSessionSelector(
    options: List<PricedSession>,
    settingsCurrency: String?,
    selectedIndex: Int,
    onSelect: (ChargeSession) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = options[options.lastIndex - selectedIndex]
    val description = stringResource(R.string.charge_session_selector_description)
    val is24Hour = LocalIs24Hour.current ?: DateFormat.is24HourFormat(LocalContext.current)
    Column {
        // Slim side padding: at the Jelly Star's width the default leaves no room for the time beside the cost.
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
        ) {
            SessionLabel(selected, settingsCurrency, is24Hour, Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { SessionLabel(option, settingsCurrency, is24Hour) },
                    onClick = {
                        open = false
                        onSelect(option.session)
                    },
                )
            }
        }
    }
}

/**
 * "Oct 19, 2:05 PM · $6.92", or "· 22.5kWh" without a price, with "Charging" for the live session and just the
 * time when the car reported no energy. On a narrow phone the time gives way; the cost never does.
 */
@Composable
private fun SessionLabel(
    priced: PricedSession,
    settingsCurrency: String?,
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
) {
    val session = priced.session
    val cost = priced.cost
    val time = formatTime(session.startMillis, showDate = true, is24Hour = is24Hour)
    val energy = session.energyAddedKwh?.let(::formatKwh)
    val tail =
        when {
            session.inProgress -> null
            cost != null -> stringResource(R.string.charge_session_option_cost, formatMoney(cost, priced.currencyCode ?: settingsCurrency))
            energy != null -> stringResource(R.string.charge_session_option, energy)
            else -> null
        }
    Row(modifier) {
        Text(
            if (session.inProgress) stringResource(R.string.charge_session_option_live, time) else time,
            maxLines = 1,
            // No wrap: a wrapped line would cut at the space and drop the whole time.
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        tail?.let { Text(" $it", maxLines = 1, softWrap = false) }
    }
}

private fun formatKw(kw: Float): String = String.format(Locale.getDefault(), "%.1f", kw)
