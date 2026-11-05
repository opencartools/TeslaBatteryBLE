// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable

import android.content.Context

/**
 * The owner's switch for the "closure left open" alert. It starts on: the alert exists to catch a door or
 * trunk left open, so a fresh install should not have to discover the setting first.
 */
internal class ClosureAlertStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    private companion object {
        const val PREFS = "teslable_alerts"
        const val KEY_ENABLED = "closure_alert_enabled"
    }
}
