// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.CarDetails
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.VinVerdict
import com.dzid26.teslable.core.vehicle.decodeVin
import com.dzid26.teslable.core.vehicle.epaSearchUrl
import java.util.Locale

private const val GREYED_ALPHA = 0.38f

/**
 * What the owner can say about a car: its VIN and its new-car rated range. The VIN is optional here, and
 * a typed one must hash to the car's advertised name (the shared [VinField] checks it). The range is
 * copied from the EPA page the link opens for the VIN's model and year.
 */
@Composable
internal fun CarDialog(
    vehicle: Vehicle,
    onSave: (CarDetails) -> Unit,
    onDismiss: () -> Unit,
) {
    var vinInput by remember { mutableStateOf(vehicle.vin.orEmpty()) }
    var rangeInput by remember { mutableStateOf(vehicle.factoryRangeMiles?.let { "%.0f".format(Locale.US, it) }.orEmpty()) }
    val normalized = Vehicle.normalizeVin(vinInput)
    val vinValid = vehicle.vinVerdict(vinInput) == VinVerdict.MATCH
    val range = rangeInput.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
    val rangeValid = rangeInput.isBlank() || range != null
    val epaUrl = if (vinValid) decodeVin(normalized)?.let(::epaSearchUrl) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.car_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                VinField(vehicle, vinInput) { vinInput = it }
                DecimalField(rangeInput, { rangeInput = it }, R.string.car_range_label, R.string.car_range_hint, rangeValid)
                EpaLookupButton(epaUrl)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(CarDetails(normalized.takeIf { it.isNotEmpty() }, range)) },
                enabled = (normalized.isEmpty() || vinValid) && rangeValid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The EPA link, greyed until the VIN gives a model and year; a tap while grey says what is missing. */
@Composable
private fun EpaLookupButton(epaUrl: String?) {
    val context = LocalContext.current
    val colors =
        if (epaUrl != null) {
            ButtonDefaults.textButtonColors()
        } else {
            ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = GREYED_ALPHA),
            )
        }
    TextButton(
        onClick = {
            if (epaUrl != null) {
                openUrl(context, epaUrl)
            } else {
                Toast.makeText(context, R.string.car_range_needs_vin, Toast.LENGTH_SHORT).show()
            }
        },
        colors = colors,
    ) {
        Text(stringResource(R.string.car_range_lookup))
    }
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    @StringRes hint: Int,
    valid: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        label = { Text(stringResource(label)) },
        isError = !valid,
        supportingText = { Text(stringResource(hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
}
