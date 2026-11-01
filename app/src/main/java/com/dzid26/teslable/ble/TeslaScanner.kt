// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.protocol.TeslaNames

class TeslaScanner(
    context: Context,
    private val onDevices: (List<TeslaAdvert>) -> Unit,
    private val onLog: (String) -> Unit,
    /** The system refused a start: no scan is running, so the owner drops its scanning state. */
    private val onRefused: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = LinkedHashMap<String, TeslaAdvert>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var filtered = false
    private var advertsSeen = 0
    private var advertsNamed = 0

    /** What the last [start] asked for, so a refused start can be repeated as it was asked. */
    private var names: List<String> = emptyList()
    private var lowPower = false

    /** Starts the system refused, reset by an explicit [start]. */
    private var refusals = 0

    private val retry = Runnable { startScan(retried = true) }
    private val stopRetried = Runnable { stop() }

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
                refused(errorCode)
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
        names = deviceNames.toList()
        this.lowPower = lowPower
        refusals = 0
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stopRetried)
        startScan(retried = false)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(retried: Boolean) {
        val scanner = bleScanner() ?: return
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
            names
                .map { ScanFilter.Builder().setDeviceName(it).build() }
                .ifEmpty { null }
        scanner.startScan(filters, settings, callback)
        scanning = true
        filtered = filters != null
        // A retried scan is a recovery path: nobody else times it out, so it ends on its own.
        if (retried) {
            handler.postDelayed(stopRetried, RETRY_SCAN_MS)
        }
        onLog(
            if (filters == null) {
                "Scanning for Tesla advertisements (name pattern S<hex>C)"
            } else {
                "Scanning for ${filters.size} known car(s) in the background"
            },
        )
    }

    /** The scanner to start, or null when this phone cannot scan right now; the reason is logged. */
    @SuppressLint("MissingPermission")
    private fun bleScanner(): BluetoothLeScanner? {
        val bleAdapter = adapter
        return when {
            !hasBlePermissions(appContext) -> {
                onLog("Bluetooth permissions are missing; grant them to scan")
                null
            }

            bleAdapter == null || !bleAdapter.isEnabled -> {
                onLog("Bluetooth is unavailable or turned off")
                null
            }

            else ->
                bleAdapter.bluetoothLeScanner ?: run {
                    onLog("BLE scanner unavailable")
                    null
                }
        }
    }

    /**
     * The system refused the start: throttled starts (five in a thirty-second window), another app
     * holding the scanner, or a stack error. It reports only this code, so a start that never ran
     * would otherwise look exactly like a scan that found nothing.
     */
    private fun refused(errorCode: Int) {
        val message = ScanFailures.message(errorCode)
        val retryIn =
            when {
                !ScanFailures.isRetryable(errorCode) -> ScanFailures.NO_RETRY
                refusals >= ScanFailures.MAX_ATTEMPTS -> ScanFailures.NO_RETRY
                else -> {
                    refusals++
                    ScanFailures.delayMs(errorCode, refusals)
                }
            }
        val gaveUp = retryIn == ScanFailures.NO_RETRY && ScanFailures.isRetryable(errorCode)
        onLog(
            when {
                gaveUp -> "$message; gave up after $refusals retries"
                retryIn == ScanFailures.NO_RETRY -> message
                else -> "$message; retrying in ${retryIn / ScanFailures.MILLIS_PER_SECOND}s"
            },
        )
        onRefused()
        if (retryIn != ScanFailures.NO_RETRY) {
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, retryIn)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stopRetried)
        if (!hasBlePermissions(appContext)) return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        if (scanning) {
            scanning = false
            // Zero here means the phone delivered nothing; named but unmatched means we dropped them.
            // A filtered scan only reports the paired cars, so zero there says little about the radio.
            val scope = if (filtered) " (filtered to the paired cars)" else ""
            onLog("Scan ended$scope: $advertsSeen advertisements seen, $advertsNamed with a name, ${found.size} Tesla")
        }
    }

    private companion object {
        /** A retried scan gets the same thirty seconds as the quiet scan it replaces. */
        const val RETRY_SCAN_MS = 30_000L
    }
}
