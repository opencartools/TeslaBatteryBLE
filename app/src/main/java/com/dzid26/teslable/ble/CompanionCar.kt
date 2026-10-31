// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.dzid26.teslable.protocol.TeslaNames
import java.util.regex.Pattern

/** A car the system chooser found; [name] is the advertised Tesla name when it was available. */
data class CompanionCar(
    val name: String?,
    val address: String,
)

/** Whether the phone can run the system's companion-device chooser at all. */
fun hasCompanionChooser(context: Context): Boolean = context.getSystemService(CompanionDeviceManager::class.java) != null

/**
 * Asks the system to scan for Teslas and lets the user pick one. The system scan needs no
 * location permission, although it still needs the system Location switch on, like any BLE scan.
 * [onChooser] receives the IntentSender that opens the chooser; [onFailure] gets a message.
 */
fun requestCompanionCar(
    context: Context,
    onChooser: (IntentSender) -> Unit,
    onFailure: (String) -> Unit,
) {
    val manager = context.getSystemService(CompanionDeviceManager::class.java)
    if (manager == null) {
        onFailure("Companion device chooser is unavailable")
        return
    }
    val filter =
        BluetoothLeDeviceFilter
            .Builder()
            .setNamePattern(Pattern.compile("S[0-9a-fA-F]{16}C"))
            .build()
    val request = AssociationRequest.Builder().addDeviceFilter(filter).build()
    val callback =
        object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onDeviceFound(intentSender: IntentSender) = onChooser(intentSender)

            override fun onFailure(error: CharSequence?) = onFailure(error?.toString().orEmpty())
        }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.associate(request, ContextCompat.getMainExecutor(context), callback)
    } else {
        @Suppress("DEPRECATION")
        manager.associate(request, callback, null)
    }
}

/** The car the user picked in the chooser, or null when the result carries no Tesla address. */
fun companionCar(
    context: Context,
    data: Intent?,
): CompanionCar? =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> modernCar(context, data)
        else -> legacyCar(context, data)
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun modernCar(
    context: Context,
    data: Intent?,
): CompanionCar? {
    val association =
        data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?: return null
    val address = association.deviceMacAddress?.toString()
    return address?.let {
        CompanionCar(advertisedName(association.displayName?.toString()) ?: adapterName(context, it), it)
    }
}

@Suppress("DEPRECATION")
private fun legacyCar(
    context: Context,
    data: Intent?,
): CompanionCar? {
    val result = data?.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE) as? ScanResult ?: return null
    val address = result.device.address
    return CompanionCar(advertisedName(result.scanRecord?.deviceName) ?: adapterName(context, address), address)
}

private fun advertisedName(name: String?): String? = name?.takeIf(TeslaNames::isTeslaBleName)

/** The name the system cached while scanning, when the result itself carried none. */
private fun adapterName(
    context: Context,
    address: String,
): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    return runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)?.name }
        .getOrNull()
        ?.takeIf(TeslaNames::isTeslaBleName)
}
