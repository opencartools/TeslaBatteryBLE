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
 * Fine and coarse location together: Android 12+ ignores a request for fine
 * location that leaves out coarse, so a lone fine request shows no dialog at
 * all. Fine is what scanning needs; coarse is granted with it.
 *
 * Location is not part of onboarding: connecting to a car at its stored
 * address needs no scan, so it is asked when a scan needs it (a pull to scan,
 * the first run, or a reconnect that has to rediscover the car).
 */
val LOCATION_PERMISSIONS: List<String> =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * What the app cannot run without: Android 12+ delivers a scan or a connection
 * only with the nearby-devices pair. Location is deliberately not here; see
 * [LOCATION_PERMISSIONS].
 */
fun requiredBlePermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> =
    if (sdk >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        emptyList()
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

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** False once Android stopped showing the location dialog (denied with "don't ask again"): only app settings can grant it. */
fun canAskLocationAgain(context: Context): Boolean {
    val activity = context as? Activity ?: return true
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
}

fun isLocationEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(LocationManager::class.java)
    return when {
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
