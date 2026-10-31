// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.protocol.TeslaNames
import kotlin.random.Random

/**
 * A simulated car behind [TeslaTransport]. This class owns the Android side
 * (scheduling, RSSI jitter) and delegates the protocol to [FakeCarProtocol],
 * which is pure JVM and unit tested against the real client code.
 */
class FakeTeslaTransport(
    context: Context,
    address: String,
    private val listener: TeslaTransport.Listener,
) : TeslaTransport {
    private val handler = Handler(Looper.getMainLooper())
    private val other = address.equals(DemoMode.OTHER_ADDRESS, true)
    private val vin: String = if (other) DemoMode.OTHER_VIN else DemoMode.DEMO_VIN
    private val protocol =
        FakeCarProtocol(vin).apply {
            // Each simulated car has its own key; sharing the first car's key made the second demo fail its
            // VCSEC session, so its notification could not offer Wake.
            setEnrolledKey(PairingKeyStore(context).load(if (other) DemoMode.OTHER_NAME else TeslaNames.bleName(vin))?.publicKeyRaw)
        }

    private var connected = false
    private var rssi = -70

    private val rssiTick =
        object : Runnable {
            override fun run() {
                if (!connected) return
                rssi = (rssi + Random.nextInt(-2, 3)).coerceIn(-95, -45)
                listener.onRssi(rssi)
                handler.postDelayed(this, RSSI_MS)
            }
        }

    private val dischargeTick =
        object : Runnable {
            override fun run() {
                if (!connected) return
                protocol.dischargeOnePercent()
                handler.postDelayed(this, DISCHARGE_MS)
            }
        }

    private val chargeTick =
        object : Runnable {
            override fun run() {
                if (!connected) return
                protocol.chargeOnePercent()
                if (protocol.batteryLevel >= protocol.chargeLimit) {
                    // Full: unplug and let the demo drain again.
                    protocol.setCharging(false)
                }
                handler.postDelayed(this, CHARGE_MS)
            }
        }

    override fun connect(address: String) {
        close()
        connected = true
        listener.onPhase(ConnectionPhase.CONNECTING)
        handler.postDelayed({ listener.onPhase(ConnectionPhase.CONNECTED) }, 200)
        handler.postDelayed({
            listener.onServices(emptyList())
            listener.onMtu(115)
            listener.onGattDeviceName(if (address.equals(DemoMode.OTHER_ADDRESS, true)) DemoMode.OTHER_TITLE else DemoMode.DEMO_TITLE)
        }, 320)
        handler.postDelayed({
            listener.onPhase(ConnectionPhase.READY)
            // The demo starts plugged in, so the chart shows charging samples
            // and the projected limit; it unplugs itself when full.
            protocol.setCharging(true)
            handler.post(rssiTick)
            handler.post(dischargeTick)
            handler.post(chargeTick)
        }, 420)
    }

    override fun close() {
        connected = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun readRssi() {
        if (connected) listener.onRssi(rssi)
    }

    override fun send(payload: ByteArray): Boolean {
        if (!connected) return false
        for (response in protocol.handle(payload.copyOf())) {
            handler.postDelayed(
                {
                    if (connected) {
                        response.onDelivered?.invoke()
                        listener.onMessage(response.bytes)
                    }
                },
                LATENCY_MS + response.delayMs,
            )
        }
        return true
    }

    private companion object {
        const val RSSI_MS = 500L

        /**
         * The live demo drains about a percent an hour, like a parked Tesla, so
         * the seeded history's slope dominates the chart's trailing windows
         * instead of the fake's artificially fast samples.
         */
        const val DISCHARGE_MS = 60 * 60_000L
        const val CHARGE_MS = 180_000L
        const val LATENCY_MS = 80L
    }
}
