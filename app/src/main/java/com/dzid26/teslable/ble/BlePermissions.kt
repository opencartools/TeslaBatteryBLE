// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Android 12+ scans with BLUETOOTH_SCAN (declared neverForLocation), so only
 * Android 11 and older still need location, and there it must be fine location.
 */
fun requiredBlePermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> =
    if (sdk >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * What the onboarding wizard walks through: the required permissions plus,
 * on Android 13+, notifications. Notifications are optional, so they are not
 * part of [requiredBlePermissions] and never block scanning.
 */
fun onboardingPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> =
    if (sdk >= Build.VERSION_CODES.TIRAMISU) {
        requiredBlePermissions(sdk) + Manifest.permission.POST_NOTIFICATIONS
    } else {
        requiredBlePermissions(sdk)
    }

fun missingOnboardingPermissions(context: Context): List<String> =
    onboardingPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

fun missingBlePermissions(context: Context): List<String> =
    requiredBlePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

fun hasBlePermissions(context: Context): Boolean = missingBlePermissions(context).isEmpty()

fun isLocationEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(LocationManager::class.java)
    return when {
        // Android 12+ scans without location, so the location switch is irrelevant.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> true
        locationManager == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> locationManager.isLocationEnabled
        // LocationManager.isLocationEnabled requires API 28; on older versions
        // "location is on" is approximated by any provider being enabled.
        else ->
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

/**
 * True when Android will no longer show the permission dialog: every required
 * permission is denied with "don't ask again", so the app must open settings.
 */
fun shouldOpenAppSettings(context: Context): Boolean {
    val activity = context as? Activity ?: return false
    return requiredBlePermissions().none {
        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }
}
