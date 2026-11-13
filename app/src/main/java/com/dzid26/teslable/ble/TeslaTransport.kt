// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

/**
 * A BLE link to one Tesla: real over GATT, fake for demos and tests.
 *
 * Call every method on the main thread.
 */
interface TeslaTransport {
    /**
     * Link events. Every callback arrives on the main thread, so a listener can
     * use main-thread state without locking, and it may call back into the
     * transport from a callback, e.g. [TeslaTransport.close] from [onPhase].
     */
    interface Listener {
        /**
         * The link's phase changed. [gattStatus] is Android's GATT status when a
         * GATT callback ended the connection (DISCONNECTED), a failed service
         * discovery included: 0 when the remote end closed it cleanly, a nonzero
         * error code otherwise; null when no code exists.
         */
        fun onPhase(
            phase: ConnectionPhase,
            gattStatus: Int? = null,
        )

        fun onServices(services: List<GattServiceInfo>)

        fun onGattDeviceName(name: String?)

        fun onMtu(mtu: Int)

        fun onMessage(message: ByteArray)

        fun onLog(message: String)

        fun onRssi(rssi: Int) {}
    }

    fun connect(address: String)

    fun send(payload: ByteArray): Boolean

    fun close()

    fun readRssi()
}
