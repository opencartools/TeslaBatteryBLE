// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dzid26.teslable.ble.TrackingChannels
import com.dzid26.teslable.ble.hasBlePermissions
import com.dzid26.teslable.ble.isLocationEnabled
import com.dzid26.teslable.ble.missingOnboardingPermissions
import com.dzid26.teslable.ble.onboardingPermissions
import com.dzid26.teslable.ble.shouldOpenAppSettings

/**
 * Owns the permission wizard and hands the current state to [content], keeping
 * launcher plumbing out of the screens. [refreshKey] must change when the
 * activity resumes from a system settings screen; [onGranted] runs once all
 * permissions are present so the caller can start scanning and connecting.
 */
@Composable
internal fun PermissionWizardHost(
    refreshKey: Int,
    onGranted: () -> Unit,
    content: @Composable (
        permissionsGranted: Boolean,
        locationServicesEnabled: Boolean,
        requestPermissions: () -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(hasBlePermissions(context)) }
    var locationServicesEnabled by remember { mutableStateOf(isLocationEnabled(context)) }
    // Observable so the wizard page advances as soon as a dialog is answered;
    // reading the permissions straight from the context would not recompose.
    var missingPermissions by remember { mutableStateOf(missingOnboardingPermissions(context)) }
    var wizardOpen by remember { mutableStateOf(false) }
    var introDone by remember { mutableStateOf(false) }
    // The step whose dialog was denied; a different step starts without an error.
    var deniedStep by remember { mutableStateOf<List<String>?>(null) }
    var deniedForever by remember { mutableStateOf(false) }

    val appSettingsIntent =
        remember(context) {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            permissionsGranted = hasBlePermissions(context)
            missingPermissions = missingOnboardingPermissions(context)
            deniedStep = results.deniedStep()
            deniedForever = deniedStep != null && shouldOpenAppSettings(context)
            if (results.finishesOnboarding(context)) {
                wizardOpen = false
                locationServicesEnabled = isLocationEnabled(context)
                onGranted()
            }
        }

    LaunchedEffect(refreshKey) {
        permissionsGranted = hasBlePermissions(context)
        missingPermissions = missingOnboardingPermissions(context)
        locationServicesEnabled = isLocationEnabled(context)
        if (wizardOpen && missingPermissions.isEmpty()) {
            wizardOpen = false
            onGranted()
        }
    }

    // Leaving after the required permissions were granted (only notifications
    // skipped) must still start scanning, since the wizard's own finish never ran.
    fun dismissWizard() {
        wizardOpen = false
        if (hasBlePermissions(context)) {
            locationServicesEnabled = isLocationEnabled(context)
            onGranted()
        }
    }

    val requestPermissions: () -> Unit = {
        if (permissionsGranted) {
            onGranted()
        } else {
            // The full-screen wizard is the guided path; the cars-list card
            // remains the fallback entry point whenever a permission is missing.
            wizardOpen = true
            introDone = false
            deniedStep = null
            deniedForever = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content(permissionsGranted, locationServicesEnabled, requestPermissions)

        if (wizardOpen && !introDone) {
            PermissionWizardGreeting(
                onContinue = { introDone = true },
                onDismiss = ::dismissWizard,
            )
        } else if (wizardOpen) {
            PermissionWizardSteps(
                missing = missingPermissions,
                deniedStep = deniedStep,
                deniedForever = deniedForever,
                onAllow = { permissions, openSettings ->
                    if (openSettings) {
                        context.startActivity(appSettingsIntent)
                    } else {
                        launcher.launch(permissions.toTypedArray())
                    }
                },
                onDismiss = ::dismissWizard,
            )
        }
    }
}

/** The page for the first onboarding permission that is still missing, if any. */
@Composable
private fun PermissionWizardSteps(
    missing: List<String>,
    deniedStep: List<String>?,
    deniedForever: Boolean,
    onAllow: (permissions: List<String>, openSettings: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val allSteps = permissionSteps(onboardingPermissions())
    val nextStep = nextPermissionStep(allSteps, missing)
    if (nextStep != null) {
        val denied = deniedStep?.toSet() == nextStep.permissions.toSet()
        PermissionWizardPage(
            step = nextStep,
            index = allSteps.indexOf(nextStep),
            total = allSteps.size,
            denied = denied,
            deniedForever = denied && deniedForever,
            onAllow = { onAllow(nextStep.permissions, denied && deniedForever) },
            onDismiss = onDismiss,
            onHintLink = { TrackingChannels.openTripSettings(context) },
        )
    }
}

// Notifications are optional: answering that page, either way, ends onboarding.
private fun Map<String, Boolean>.isOptionalOnly() = keys == setOf(Manifest.permission.POST_NOTIFICATIONS)

/** The denied step's permissions, or null when nothing the user must fix was denied. */
private fun Map<String, Boolean>.deniedStep(): List<String>? =
    keys.toList().takeIf { !isOptionalOnly() && values.any { granted -> !granted } }

private fun Map<String, Boolean>.finishesOnboarding(context: Context) =
    hasBlePermissions(context) && (isOptionalOnly() || missingOnboardingPermissions(context).isEmpty())
