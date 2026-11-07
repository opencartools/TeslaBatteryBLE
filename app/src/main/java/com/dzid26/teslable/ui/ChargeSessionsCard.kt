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
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.dzid26.teslable.core.health.ChargePlace
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.ChargeSessionPrice
import com.dzid26.teslable.core.health.ChargerKind
import com.dzid26.teslable.core.health.PriceChange
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.cost
import com.dzid26.teslable.core.health.sessionPrices
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.protocol.LastPaidCharge
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
    val paid = pricing?.paid?.get(sessions.first().vehicleId).orEmpty()
    // Each session's price, owner's edit first; an edited one stays in the currency it was set in.
    val priced =
        remember(sessions, priceChanges, stored, paid) {
            sessions.zip(sessionPrices(sessions, stored, priceChanges, paid)) { session, price -> PricedSession(session, price) }
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
            ChargeSessionStats(
                priced[selectedIndex],
                currencyCode,
                onEdit =
                    { editing = true }.takeIf {
                        pricing != null &&
                            !session.inProgress
                    },
            )
        }
    }
    if (editing && pricing != null) {
        val picked = priced[selectedIndex]
        EditPriceDialog(
            picked = picked,
            currencyCode = picked.currencyCode ?: resolvedCurrencyCode(settingsCurrency),
            suggestion = priceSuggestion(session, priceChanges, paid, settingsCurrency),
            onSave = { price, currency ->
                editing = false
                pricing.onSet(session, price, currency, keptPlace(picked))
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
    onEdit: (() -> Unit)?,
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
            // Edit price sits under the price it changes.
            Column {
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
                onEdit?.let {
                    TextButton(
                        onClick = it,
                        contentPadding = PaddingValues(0.dp),
                    ) { Text(stringResource(R.string.charge_session_edit_price)) }
                }
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
internal class PricedSession(
    val session: ChargeSession,
    private val billed: ChargeSessionPrice,
) {
    val price get() = billed.price
    val currencyCode get() = billed.currencyCode
    val place get() = billed.place
    val edited get() = billed.byOwner
    val fromCar get() = billed.fromCar
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
 * total box holds its cost. [homePerKwh] ([suggestion]) fills the per-kWh box from a button that names it ("Home $0.30").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPriceDialog(
    picked: PricedSession,
    currencyCode: String,
    suggestion: PriceSuggestion?,
    onSave: (SessionPrice, String) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) { EditPricePanel(picked, currencyCode, suggestion, onSave, onDismiss) }
}

/** The dialog's own surface, apart from its window, so a snapshot can render it. */
@Composable
internal fun EditPricePanel(
    picked: PricedSession,
    currencyCode: String,
    suggestion: PriceSuggestion?,
    onSave: (SessionPrice, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = picked.price
    val kwh = picked.session.energyAddedKwh?.takeIf { it > 0f }
    var total by rememberSaveable { mutableStateOf(current is SessionPrice.Total) }
    var perKwhText by rememberSaveable {
        mutableStateOf(editText((current as? SessionPrice.PerKwh)?.pricePerKwh ?: kwh?.let { picked.cost?.div(it) }))
    }
    // A suggestion in another currency (the car bills in its own) switches the edit to it.
    var currency by rememberSaveable { mutableStateOf(currencyCode) }
    var totalText by rememberSaveable { mutableStateOf(editText(picked.cost)) }
    val value = parseEdit(if (total) totalText else perKwhText)
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.charge_session_edit_price), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
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
                suffix = { Text(if (total) currency else "$currency/kWh") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(24.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                suggestion?.let {
                    TextButton(onClick = {
                        total = false
                        // A total typed in the old currency would be saved as the new one.
                        if (currency != it.currencyCode) totalText = ""
                        currency = it.currencyCode
                        perKwhText = editText(it.pricePerKwh)
                    }) { Text(it.label()) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                TextButton(
                    enabled = value != null,
                    onClick = { value?.let { onSave(if (total) SessionPrice.Total(it) else SessionPrice.PerKwh(it), currency) } },
                ) { Text(stringResource(R.string.charge_session_price_update)) }
            }
        }
    }
}

private const val EDIT_DECIMALS = 4

/** A per-kWh price the dialog offers to fill in, named by where it comes from. */
internal class PriceSuggestion(
    val supercharger: Boolean,
    val pricePerKwh: Float,
    val currencyCode: String,
) {
    @Composable
    fun label(): String =
        stringResource(
            if (supercharger) R.string.charge_session_price_use_supercharger else R.string.charge_session_price_use_home,
            formatMoney(pricePerKwh, currencyCode),
        )
}

/**
 * What to offer for [session]: the price per kWh of the car's last paid charge for a fast charge (a fast charger is
 * never at home), else the home price set now.
 */
private fun priceSuggestion(
    session: ChargeSession,
    homeChanges: List<PriceChange>,
    paid: List<LastPaidCharge>,
    settingsCurrency: String?,
): PriceSuggestion? =
    if (session.chargerKind == ChargerKind.DC) {
        paid.lastOrNull { it.pricePerKwh != null }?.let { charge ->
            charge.pricePerKwh?.let { PriceSuggestion(true, it, charge.currencyCode) }
        }
    } else {
        homeChanges.lastOrNull()?.pricePerKwh?.let { PriceSuggestion(false, it, resolvedCurrencyCode(settingsCurrency)) }
    }

/** The place an edit keeps: the stored one, or Supercharger for a price the car set (it stores none for those). */
private fun keptPlace(picked: PricedSession): String? =
    picked.place
        ?: ChargePlace.SUPERCHARGER.takeIf { picked.fromCar }

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
