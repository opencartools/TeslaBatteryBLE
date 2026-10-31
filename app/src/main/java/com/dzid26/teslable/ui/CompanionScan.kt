// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dzid26.teslable.ble.TeslaBleController
import com.dzid26.teslable.ble.companionCar
import com.dzid26.teslable.ble.requestCompanionCar

/**
 * The fallback when Android will not show the location dialog again: the system's companion-device
 * chooser finds a Tesla without the location permission. Returns the starter for that chooser.
 */
@Composable
internal fun rememberCompanionScan(
    controller: TeslaBleController,
    onDismissed: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val car = if (result.resultCode == Activity.RESULT_OK) companionCar(context, result.data) else null
            val name = car?.name
            if (car != null && name != null) {
                controller.rememberVehicle(name, car.address, null)
                controller.openVehicle(car.address)
            } else {
                controller.log("System chooser: no Tesla found")
                onDismissed()
            }
        }
    return {
        requestCompanionCar(
            context,
            onChooser = { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) },
            onFailure = { message ->
                controller.log("System chooser: ${message.ifBlank { "no car found" }}")
                onDismissed()
            },
        )
    }
}
