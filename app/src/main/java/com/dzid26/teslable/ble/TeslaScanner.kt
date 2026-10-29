// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.dzid26.teslable.protocol.TeslaNames

class TeslaScanner(
    context: Context,
    private val onDevices: (List<TeslaAdvert>) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = LinkedHashMap<String, TeslaAdvert>()
    private var scanning = false
    private var advertsSeen = 0
    private var advertsNamed = 0

    private val callback =
        object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                advertsSeen++
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (name != null) advertsNamed++
                if (name == null || !TeslaNames.isTeslaBleName(name)) {
                    return
                }
                found[result.device.address] =
                    TeslaAdvert(
                        name = name,
                        address = result.device.address,
                        rssi = result.rssi,
                    )
                onDevices(found.values.sortedByDescending { it.rssi })
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                onLog("Scan failed with error $errorCode")
            }
        }

    /**
     * Starts scanning. With [deviceNames] only those advertised names are reported, which the OS
     * keeps scanning with the screen off and can match in hardware; an unfiltered scan is stopped
     * while the screen is off. [lowPower] trades latency for battery: background discovery, not a
     * scan the user is watching.
     */
    @SuppressLint("MissingPermission")
    fun start(
        deviceNames: Collection<String> = emptyList(),
        lowPower: Boolean = false,
    ) {
        if (!hasBlePermissions(appContext)) {
            onLog("Bluetooth permissions are missing; grant them to scan")
            return
        }
        val bleAdapter = adapter
        if (bleAdapter == null || !bleAdapter.isEnabled) {
            onLog("Bluetooth is unavailable or turned off")
            return
        }
        val scanner = bleAdapter.bluetoothLeScanner
        if (scanner == null) {
            onLog("BLE scanner unavailable")
            return
        }
        found.clear()
        advertsSeen = 0
        advertsNamed = 0
        onDevices(emptyList())
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        val filters =
            deviceNames
                .map { ScanFilter.Builder().setDeviceName(it).build() }
                .ifEmpty { null }
        scanner.startScan(filters, settings, callback)
        scanning = true
        onLog(
            if (filters == null) {
                "Scanning for Tesla advertisements (name pattern S<hex>C)"
            } else {
                "Scanning for ${filters.size} known car(s) in the background"
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!hasBlePermissions(appContext)) return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        if (scanning) {
            scanning = false
            // Zero here means the phone delivered nothing; named but unmatched means we dropped them.
            onLog("Scan ended: $advertsSeen advertisements seen, $advertsNamed with a name, ${found.size} Tesla")
        }
    }
}
