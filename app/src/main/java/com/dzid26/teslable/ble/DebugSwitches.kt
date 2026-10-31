// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import com.dzid26.teslable.BuildConfig

/** Experiments that only exist in debug builds; a release build ignores whatever is stored. */
class DebugSwitches(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Connect with `connectGatt(autoConnect = true)`: Android connects in the background when the car comes
     * into range instead of the app polling for it. Whether it pays off depends on Tesla's BLE addresses
     * staying stable between visits, which this switch is for finding out.
     */
    var autoConnect: Boolean
        get() = BuildConfig.DEBUG && prefs.getBoolean(KEY_AUTO_CONNECT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    /**
     * Add the simulated car from the debug source set next to the real ones. A build made with
     * `-PdemoCar=true` starts it on; the switch keeps the choice for the next launch.
     */
    var demoCar: Boolean
        get() = if (BuildConfig.DEBUG) prefs.getBoolean(KEY_DEMO_CAR, BuildConfig.DEMO_CAR) else BuildConfig.DEMO_CAR
        set(value) = prefs.edit().putBoolean(KEY_DEMO_CAR, value).apply()

    /** True once the debug switch stored a choice; a build-time `-PdemoCar=true` default is not one. */
    val hasDemoCarChoice: Boolean
        get() = BuildConfig.DEBUG && prefs.contains(KEY_DEMO_CAR)

    private companion object {
        const val PREFS = "teslable_debug"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_DEMO_CAR = "demo_car"
    }
}
