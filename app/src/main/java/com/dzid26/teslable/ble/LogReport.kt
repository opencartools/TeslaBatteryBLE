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
import java.text.DateFormat
import java.util.Date

/**
 * The shared log text: the phone and permission state first, then the app log, oldest line first,
 * each line led by its time ([formatTime]) when it has one. Some lines already name their car.
 */
fun formatLogReport(
    header: List<String>,
    log: List<LogEntry>,
    formatTime: (Long) -> String,
): String =
    buildString {
        header.forEach { appendLine(it) }
        appendLine()
        log.forEach { entry ->
            if (entry.timeMillis > 0) append(formatTime(entry.timeMillis)).append(' ')
            val prefix = entry.vehicleId?.let { "$it: " }
            if (prefix != null && !entry.message.startsWith(prefix)) append(prefix)
            appendLine(entry.message)
        }
    }

/** Time of day with seconds in the phone's locale, for the lines of the shared log. */
fun logTimeFormatter(): (Long) -> String {
    val format = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    return { format.format(Date(it)) }
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
        "System chooser: ${if (hasCompanionChooser(context)) "available" else "unavailable"}",
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
