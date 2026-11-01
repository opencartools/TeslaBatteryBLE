// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import com.dzid26.teslable.BuildConfig

/**
 * Experimental overrides. A release build ignores whatever is stored, so a debug session cannot
 * leave a release install in a different state.
 */
class DebugSwitches(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Connect with `connectGatt(autoConnect = true)`: Android waits for the car and connects when it
     * comes into range. Debug build type only, on unless the switch is turned off; release keeps the
     * direct-connect path. Gated on the build type, not [BuildConfig.DEBUG], because the rolling
     * preview is a release build made debuggable.
     */
    var autoConnect: Boolean
        get() = BuildConfig.BUILD_TYPE == "debug" && prefs.getBoolean(KEY_AUTO_CONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    /**
     * Add the simulated car from the debug source set next to the real ones. A build made with
     * `-PdemoCar=true` starts it on; the switch keeps the choice for the next launch.
     */
    var demoCar: Boolean
        get() = if (BuildConfig.DEBUG) prefs.getBoolean(KEY_DEMO_CAR, BuildConfig.DEMO_CAR) else BuildConfig.DEMO_CAR
        set(value) = prefs.edit().putBoolean(KEY_DEMO_CAR, value).apply()

    private companion object {
        const val PREFS = "teslable_debug"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_DEMO_CAR = "demo_car"
    }
}
