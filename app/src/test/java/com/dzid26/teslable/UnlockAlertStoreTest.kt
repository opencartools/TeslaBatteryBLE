// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockAlertStoreTest {
    /** What [android.content.SharedPreferences] holds for the two keys the switch uses. */
    private class MapPreference(
        private val values: MutableMap<String, Boolean> = mutableMapOf(),
    ) : BooleanPreference {
        override fun getBoolean(
            key: String,
            fallback: Boolean,
        ): Boolean = values[key] ?: fallback

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            values[key] = value
        }
    }

    @Test
    fun `the alert is on when nothing was saved`() {
        assertTrue(UnlockAlertStore(MapPreference()).enabled())
    }

    @Test
    fun `turning it off survives a new store over the same preferences`() {
        val preferences = MapPreference()
        UnlockAlertStore(preferences).setEnabled(false)
        assertFalse(UnlockAlertStore(preferences).enabled())
    }

    @Test
    fun `turning it back on persists too`() {
        val preferences = MapPreference()
        UnlockAlertStore(preferences).setEnabled(false)
        UnlockAlertStore(preferences).setEnabled(true)
        assertTrue(UnlockAlertStore(preferences).enabled())
    }
}
