// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
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
import com.dzid26.teslable.core.history.Session
import com.dzid26.teslable.protocol.LastPaidCharge
import com.dzid26.teslable.resolvedCurrencyCode
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * A charge's price in the Sessions card: what each charge is billed at, and the Edit price button with its dialog,
 * which stores the owner's price per kWh or total for one session (owner.db).
 */

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

/**
 * Each charge among [sessions] with the price it is billed at, by session id: the owner's edit or the stored price
 * first, else the home price in effect for it, or the car's Supercharger cost (`sessionPrices`).
 */
@Composable
internal fun rememberPricedCharges(sessions: List<Session>): Map<String, PricedSession> {
    val priceChanges = LocalPriceChanges.current
    val pricing = LocalChargePricing.current
    val stored = pricing?.stored.orEmpty()
    val paidByCar = pricing?.paid.orEmpty()
    return remember(sessions, priceChanges, stored, paidByCar) {
        val charges = sessions.filterIsInstance<ChargeSession>()
        val paid = charges.firstOrNull()?.let { paidByCar[it.vehicleId] }.orEmpty()
        charges
            .zip(sessionPrices(charges, stored, priceChanges, paid)) { session, price -> PricedSession(session, price) }
            .associateBy { it.session.id }
    }
}

/**
 * The Edit price button for a finished charge, and its dialog; nothing while the charge is still going or the
 * prices cannot be edited (no owner store).
 */
@Composable
internal fun EditPriceButton(picked: PricedSession) {
    val pricing = LocalChargePricing.current ?: return
    val session = picked.session
    if (session.inProgress) return
    val settingsCurrency = LocalCurrencyCode.current
    val priceChanges = LocalPriceChanges.current
    // Saved, so the dialog stays open when the phone turns.
    var editing by rememberSaveable(session.id) { mutableStateOf(false) }
    // A small pencil link under the price rather than a full button: it belongs to the figure above it.
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { editing = true }
                .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.charge_session_edit_price),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (editing) {
        val paid = pricing.paid[session.vehicleId].orEmpty()
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
