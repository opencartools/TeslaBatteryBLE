// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.protocol.TeslaNames

/**
 * Switch for the simulated car: a build can start it with `./gradlew assembleDebug -PdemoCar=true`, and a debug
 * build can turn it on at run time from Settings. The fake itself lives in the debug source set; release builds
 * leave [car] null and always use the real GATT transport. The demo car is discovered by a scan, like a real one.
 */
object DemoMode {
    const val DEMO_VIN = "5YJ3DEMO0P0000001"
    const val DEMO_ADDRESS = "AA:BB:CC:DD:EE:01"
    const val DEMO_TITLE = "Demo Tesla"
    const val OTHER_VIN = "5YJ3DEMO0P0000002"
    const val OTHER_NAME = "S1a2b3c4d5e6f7080C"
    const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:02"
    const val OTHER_TITLE = "Demo Tesla 2"

    /** Set by the debug application; null in release builds. */
    @Volatile
    var car: Car? = null

    @Volatile
    private var enabled = BuildConfig.DEMO_CAR

    /** The controller listens here to add the fake adverts to a scan, or drop the demo car. */
    @Volatile
    var onEnabledChanged: ((Boolean) -> Unit)? = null

    interface Car {
        fun createTransport(
            address: String,
            listener: TeslaTransport.Listener,
        ): TeslaTransport

        fun adverts(): List<TeslaAdvert>
    }

    fun isEnabled(): Boolean = car != null && enabled

    /** The simulated car's VIN, or null for a real car or a demo car with a different advertised name. */
    fun vinFor(
        name: String,
        address: String,
    ): String? {
        if (!isEnabled()) return null
        val mainName = runCatching { TeslaNames.bleName(DEMO_VIN) }.getOrNull()
        return when {
            name == mainName -> DEMO_VIN
            name == OTHER_NAME && address.equals(OTHER_ADDRESS, true) -> OTHER_VIN
            else -> null
        }
    }

    /** The fixed addresses of the fake cars, so their links never try the real GATT transport. */
    fun isDemoAddress(address: String): Boolean = address.equals(DEMO_ADDRESS, true) || address.equals(OTHER_ADDRESS, true)

    /** Restores the persisted debug switch; only the debug application calls this at launch. */
    fun restore(context: Context) {
        enabled = DebugSwitches(context).demoCar
    }

    /** The debug switch: persists the choice and lets the controller start or stop the fake adverts. */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        DebugSwitches(context).demoCar = enabled
        onEnabledChanged?.invoke(enabled)
    }
}
