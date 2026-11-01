// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.protocol.TeslaGatt
import com.dzid26.teslable.protocol.framing.BleFramer
import java.util.UUID

/**
 * [TeslaTransport] over Android GATT.
 *
 * Every GATT callback runs on the main thread, the thread callers use, so the
 * write queue, the framer and the listener need no locking. Android allows one
 * GATT operation in flight per connection, so setup runs one step at a time:
 * MTU exchange, then the CCCD write that enables indications, then the
 * device-name read, then READY. Frames sent before READY wait in the queue.
 */
class TeslaGattClient(
    private val context: Context,
    private val listener: TeslaTransport.Listener,
    /** Let Android connect in the background when the car comes into range, instead of the app polling for it. */
    private val autoConnect: Boolean,
) : TeslaTransport {
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = DEFAULT_MTU
    private var setupStep = SetupStep.IDLE
    private val framer = BleFramer()
    private val handler = Handler(Looper.getMainLooper())
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private val writeTimeout =
        Runnable {
            writeInProgress = false
            processWriteQueue()
        }

    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        close()
        val device =
            context
                .getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(address)
        if (device == null) {
            listener.onLog("Could not resolve $address")
            listener.onPhase(ConnectionPhase.FAILED)
            return
        }
        listener.onPhase(ConnectionPhase.CONNECTING)
        // From Android 8.1 (our minSdk) BluetoothGatt stores a notification's value inside the posted callback, so the
        // main-looper Handler can't deliver the next notification's bytes to onCharacteristicChanged.
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        if (gatt == null) {
            // Bluetooth is off or turning off: no callback will ever come, so fail now and let the backoff retry.
            listener.onLog("connectGatt() returned null; Bluetooth is off?")
            listener.onPhase(ConnectionPhase.FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        txCharacteristic = null
        rxCharacteristic = null
        negotiatedMtu = DEFAULT_MTU
        setupStep = SetupStep.IDLE
        writeQueue.clear()
        writeInProgress = false
        handler.removeCallbacks(writeTimeout)
    }

    override fun send(payload: ByteArray): Boolean {
        if (gatt == null) return false
        if (txCharacteristic == null) {
            listener.onLog("TX characteristic not ready")
            return false
        }
        val chunkSize = (negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(MIN_CHUNK_SIZE)
        writeQueue.addAll(BleFramer.encode(payload, chunkSize))
        processWriteQueue()
        return true
    }

    @SuppressLint("MissingPermission")
    override fun readRssi() {
        // One GATT operation in flight: an RSSI read during setup or a chunk write would collide with it. The next
        // poll picks the value up.
        if (setupStep != SetupStep.DONE || writeInProgress) return
        gatt?.readRemoteRssi()
    }

    @SuppressLint("MissingPermission")
    private fun processWriteQueue() {
        if (writeInProgress || setupStep != SetupStep.DONE) return
        val gatt = gatt ?: return
        val characteristic = txCharacteristic ?: return
        val chunk = writeQueue.removeFirstOrNull() ?: return

        writeInProgress = true
        // The Go reference writes with response. We write without response: chunks are paced on
        // onCharacteristicWrite, which fires for this type too, and it saves a round trip per chunk.
        val started =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

        if (!started) {
            writeInProgress = false
            writeQueue.addFirst(chunk)
            handler.postDelayed({ processWriteQueue() }, WRITE_RETRY_MS)
            return
        }
        handler.removeCallbacks(writeTimeout)
        handler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
    }

    private val callback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        // A weak or interrupted link, not a fault: the backoff retries, so it reads as disconnected.
                        listener.onLog("GATT error status $status")
                        listener.onPhase(ConnectionPhase.DISCONNECTED)
                    }

                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        listener.onLog("Connected, discovering services")
                        listener.onPhase(ConnectionPhase.CONNECTED)
                        if (!gatt.discoverServices()) {
                            listener.onLog("discoverServices() returned false")
                            listener.onPhase(ConnectionPhase.DISCONNECTED)
                        }
                    }

                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        listener.onLog("Disconnected")
                        listener.onPhase(ConnectionPhase.DISCONNECTED)
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("Service discovery failed with status $status")
                    listener.onPhase(ConnectionPhase.DISCONNECTED)
                    return
                }
                listener.onPhase(ConnectionPhase.DISCOVERING)
                listener.onServices(
                    gatt.services.map { service ->
                        GattServiceInfo(
                            uuid = service.uuid,
                            characteristicUuids = service.characteristics.map { it.uuid },
                        )
                    },
                )

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    listener.onLog("Tesla GATT service not found")
                    // Not a Tesla BLE endpoint: retrying the link will not fix it.
                    listener.onPhase(ConnectionPhase.FAILED)
                    return
                } else {
                    txCharacteristic = service.getCharacteristic(TO_VEHICLE_UUID)
                    rxCharacteristic = service.getCharacteristic(FROM_VEHICLE_UUID)
                    listener.onLog(
                        "TX characteristic: ${txCharacteristic != null}, " +
                            "RX characteristic: ${rxCharacteristic != null}",
                    )
                }
                if (setupStep == SetupStep.IDLE) exchangeMtu(gatt)
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                    listener.onMtu(mtu)
                } else {
                    listener.onLog("MTU negotiation failed with status $status")
                }
                if (setupStep == SetupStep.MTU) subscribe(gatt)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID && setupStep == SetupStep.NOTIFICATIONS) {
                    listener.onLog(
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            "Notifications enabled"
                        } else {
                            "Notification setup failed with status $status"
                        },
                    )
                    readGattDeviceName(gatt)
                }
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                handleDeviceName(characteristic.value, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handleDeviceName(value, status)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                handleNotification(characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleNotification(value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != TO_VEHICLE_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("write failed with status $status")
                }
                handler.removeCallbacks(writeTimeout)
                writeInProgress = false
                processWriteQueue()
            }

            override fun onReadRemoteRssi(
                gatt: BluetoothGatt,
                rssi: Int,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onRssi(rssi)
                }
            }
        }

    /** Setup step 1: MTU exchange, so frames sent after READY use full-size chunks. */
    @SuppressLint("MissingPermission")
    private fun exchangeMtu(gatt: BluetoothGatt) {
        setupStep = SetupStep.MTU
        if (!gatt.requestMtu(MTU_REQUEST)) {
            listener.onLog("requestMtu() returned false")
            subscribe(gatt)
        }
    }

    /** Setup step 2: enable indications on the RX characteristic. */
    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt) {
        setupStep = SetupStep.NOTIFICATIONS
        val characteristic = rxCharacteristic
        if (characteristic == null) {
            readGattDeviceName(gatt)
            return
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            listener.onLog("setCharacteristicNotification() failed")
            readGattDeviceName(gatt)
            return
        }
        val descriptor =
            characteristic
                .getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            listener.onLog("CCCD descriptor missing")
            readGattDeviceName(gatt)
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val started =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        if (!started) {
            listener.onLog("writeDescriptor() failed")
            readGattDeviceName(gatt)
        }
    }

    /** Setup step 3: read the GAP device name; READY follows whether or not it works. */
    @SuppressLint("MissingPermission")
    private fun readGattDeviceName(gatt: BluetoothGatt) {
        setupStep = SetupStep.DEVICE_NAME
        val characteristic =
            gatt
                .getService(GENERIC_ACCESS_SERVICE)
                ?.getCharacteristic(DEVICE_NAME_CHARACTERISTIC)
        if (characteristic == null) {
            listener.onLog("Device name characteristic not exposed")
            finishSetup()
            return
        }
        if (!gatt.readCharacteristic(characteristic)) {
            listener.onLog("readCharacteristic() returned false")
            finishSetup()
        }
    }

    private fun handleDeviceName(
        value: ByteArray?,
        status: Int,
    ) {
        if (setupStep != SetupStep.DEVICE_NAME) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val name = value?.toString(Charsets.UTF_8)?.trim()
            if (!name.isNullOrEmpty()) {
                listener.onLog("Device name: $name")
            }
            listener.onGattDeviceName(name)
        } else {
            listener.onLog("Device name read failed with status $status")
        }
        finishSetup()
    }

    /** Frames queued during setup go out first, then the link reports READY. */
    private fun finishSetup() {
        setupStep = SetupStep.DONE
        processWriteQueue()
        listener.onPhase(ConnectionPhase.READY)
    }

    private fun handleNotification(value: ByteArray?) {
        if (value == null) return
        for (message in framer.feed(value)) {
            listener.onMessage(message)
        }
    }

    /** Setup after service discovery; each step waits for the previous step's callback. */
    private enum class SetupStep { IDLE, MTU, NOTIFICATIONS, DEVICE_NAME, DONE }

    companion object {
        private const val MTU_REQUEST = 256
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val MIN_CHUNK_SIZE = 20
        private const val WRITE_TIMEOUT_MS = 1000L
        private const val WRITE_RETRY_MS = 100L
        private val SERVICE_UUID = UUID.fromString(TeslaGatt.SERVICE_UUID)
        private val TO_VEHICLE_UUID = UUID.fromString(TeslaGatt.TO_VEHICLE_UUID)
        private val FROM_VEHICLE_UUID = UUID.fromString(TeslaGatt.FROM_VEHICLE_UUID)
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString(TeslaGatt.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        private val GENERIC_ACCESS_SERVICE =
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_CHARACTERISTIC =
            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    }
}
