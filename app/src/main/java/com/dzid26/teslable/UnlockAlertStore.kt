// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable

import android.content.Context
import android.content.SharedPreferences

/** The preference operations an [UnlockAlertStore] needs, so the default and persistence stay JVM-testable. */
internal interface BooleanPreference {
    fun getBoolean(
        key: String,
        fallback: Boolean,
    ): Boolean

    fun putBoolean(
        key: String,
        value: Boolean,
    )
}

/** [SharedPreferences] behind [BooleanPreference], written asynchronously like the app's other stores. */
private class SharedPreferencesPreference(
    private val prefs: SharedPreferences,
) : BooleanPreference {
    override fun getBoolean(
        key: String,
        fallback: Boolean,
    ): Boolean = prefs.getBoolean(key, fallback)

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * The owner's switch for the "car left unlocked" alert. It starts on: the alert exists to catch a car
 * that was left open, so a fresh install should not have to discover the setting first.
 */
internal class UnlockAlertStore(
    private val preference: BooleanPreference,
) {
    constructor(context: Context) : this(
        SharedPreferencesPreference(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
    )

    fun enabled(): Boolean = preference.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) = preference.putBoolean(KEY_ENABLED, enabled)

    companion object {
        const val DEFAULT_ENABLED = true
        private const val PREFS = "teslable_alerts"
        private const val KEY_ENABLED = "unlock_alert_enabled"
    }
}
