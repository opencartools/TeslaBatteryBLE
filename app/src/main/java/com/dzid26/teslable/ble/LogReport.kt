// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** The shared log text: the phone and permission state first, then the app log, oldest line first. */
fun formatLogReport(
    header: List<String>,
    log: List<LogEntry>,
): String =
    buildString {
        header.forEach { appendLine(it) }
        appendLine()
        log.forEach { entry ->
            entry.vehicleId?.let { append(it).append(": ") }
            appendLine(entry.message)
        }
    }

/** What a bug report about scanning or connecting needs to know about the phone. */
fun deviceReportHeader(context: Context): List<String> {
    val appVersion =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    val location = context.getSystemService(LocationManager::class.java)
    return listOf(
        "App: ${context.packageName} ${appVersion ?: "unknown"}",
        "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Bluetooth: ${if (bluetooth?.isEnabled == true) "on" else "off or unavailable"}",
        "System location: ${if (location?.let(LocationManagerCompat::isLocationEnabled) == true) "on" else "off"}",
    ) + reportedPermissions().map { "${it.substringAfterLast('.')}: ${grantedLabel(context, it)}" }
}

private fun reportedPermissions(): List<String> =
    listOfNotNull(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
    )

private fun grantedLabel(
    context: Context,
    permission: String,
): String =
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        "granted"
    } else {
        "not granted"
    }
