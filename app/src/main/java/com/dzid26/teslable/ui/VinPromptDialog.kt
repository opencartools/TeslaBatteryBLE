// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.VinVerdict

/** The Tesla app's package: the VIN is shown inside it, so its launcher intent is the only thing we need. */
internal const val TESLA_APP_PACKAGE = "com.teslamotors.tesla"

/**
 * The blocking prompt a car without a VIN gets when the owner reads or wakes it: the title names the car,
 * the body says where the VIN is and offers the Tesla app shortcut, and Save only unlocks once the typed
 * VIN hashes to the advertised name. [VinPromptContent] holds the same parts without a Dialog window, so
 * the Paparazzi snapshots can render them.
 */
@Composable
internal fun VinPromptDialog(
    vehicle: Vehicle,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var vinInput by remember { mutableStateOf(vehicle.vin.orEmpty()) }
    val verdict = vehicle.vinVerdict(vinInput)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vin_prompt_title, vehicle.title)) },
        text = { PromptBody(vehicle, vinInput) { vinInput = it } },
        confirmButton = {
            TextButton(
                onClick = { onSave(Vehicle.normalizeVin(vinInput)) },
                enabled = verdict == VinVerdict.MATCH,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The prompt's title, body and actions on a plain card instead of a Dialog window. The production dialog
 * renders the same [PromptBody] and Save/Cancel actions; this exists because Paparazzi does not render real
 * dialog windows, and it is what `VinPromptSnapshots` records.
 */
@Composable
internal fun VinPromptContent(
    vehicle: Vehicle,
    vinInput: String,
    onVinChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val verdict = vehicle.vinVerdict(vinInput)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.vin_prompt_title, vehicle.title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            PromptBody(vehicle, vinInput, onVinChange)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = { onSave(Vehicle.normalizeVin(vinInput)) },
                    enabled = verdict == VinVerdict.MATCH,
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/** The prompt's body: the practical copy and the shared VIN field, whose hint links to the Tesla app. */
@Composable
private fun PromptBody(
    vehicle: Vehicle,
    vinInput: String,
    onVinChange: (String) -> Unit,
) {
    // Scrollable like the car editor: the copy and the field fit a small screen.
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.vin_prompt_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        VinField(vehicle, vinInput, onVinChange)
    }
}

/**
 * Opens the Tesla app so the owner can copy the VIN from it. A phone without the app gets its store page,
 * and a phone with neither gets a toast: the prompt never fails or crashes over a missing app.
 */
internal fun openTeslaApp(context: Context) {
    val launched =
        context.packageManager.getLaunchIntentForPackage(TESLA_APP_PACKAGE)?.let { launch ->
            runCatching { context.startActivity(launch) }.isSuccess
        } == true
    val opened =
        launched ||
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TESLA_APP_PACKAGE")))
            }.isSuccess
    if (!opened) Toast.makeText(context, R.string.vin_tesla_app_missing, Toast.LENGTH_SHORT).show()
}
