// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import com.dzid26.teslable.BuildConfig

/**
 * Switch for the simulated car: a build can start it with `./gradlew assembleDebug -PdemoCar=true`, and a debug
 * build can turn it on at run time from Settings. The fake itself lives in the debug source set; release builds
 * leave [car] null and always use the real GATT transport. Enabling it adds the demo car next to the real ones.
 */
object DemoMode {
    const val DEMO_VIN = "5YJ3DEMO0P0000001"
    const val DEMO_ADDRESS = "AA:BB:CC:DD:EE:01"
    const val DEMO_TITLE = "Demo Tesla"
    const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:02"

    /** Set by the debug application; null in release builds. */
    @Volatile
    var car: Car? = null

    @Volatile
    private var enabled = BuildConfig.DEMO_CAR

    /** Whether the Settings switch (not only the build flag) turned it on. */
    @Volatile
    private var runtimeEnabled = false

    /** The controller listens here to add or drop the demo car without restarting the app. */
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

    /** The fixed addresses of the fake cars, so their links never try the real GATT transport. */
    fun isDemoAddress(address: String): Boolean = address.equals(DEMO_ADDRESS, true) || address.equals(OTHER_ADDRESS, true)

    /** True when the Settings switch, not only the build flag, turned it on; only then is it pre-added at launch. */
    fun isEnabledAtRuntime(): Boolean = car != null && enabled && runtimeEnabled

    /** Restores the persisted debug switch; only the debug application calls this at launch. */
    fun restore(context: Context) {
        val switches = DebugSwitches(context)
        enabled = switches.demoCar
        runtimeEnabled = switches.hasDemoCarChoice && enabled
    }

    /** The debug switch: persists the choice and lets the controller attach or detach the demo car in place. */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        runtimeEnabled = enabled
        DebugSwitches(context).demoCar = enabled
        onEnabledChanged?.invoke(enabled)
    }
}
