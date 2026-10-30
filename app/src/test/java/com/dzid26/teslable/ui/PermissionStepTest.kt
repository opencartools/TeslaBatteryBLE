// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.Manifest
import android.os.Build
import com.dzid26.teslable.ble.onboardingPermissions
import com.dzid26.teslable.ble.requiredBlePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionStepTest {
    private val nearby = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    @Test
    fun `android 11 asks for nothing up front`() {
        // Bluetooth is install-time there; location is only asked when a scan needs it.
        assertEquals(emptyList<String>(), requiredBlePermissions(Build.VERSION_CODES.R))
        assertEquals(emptyList<String>(), onboardingPermissions(Build.VERSION_CODES.R))
    }

    @Test
    fun `android 12 asks for nearby devices`() {
        assertEquals(nearby, requiredBlePermissions(Build.VERSION_CODES.S))
        assertEquals(nearby, onboardingPermissions(Build.VERSION_CODES.S))
    }

    @Test
    fun `android 13 adds notifications`() {
        assertEquals(
            nearby + Manifest.permission.POST_NOTIFICATIONS,
            onboardingPermissions(Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun `step advances to notifications once nearby devices are granted`() {
        val steps = permissionSteps(onboardingPermissions(Build.VERSION_CODES.TIRAMISU))
        assertEquals(nearby, nextPermissionStep(steps, nearby)?.permissions)
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            nextPermissionStep(steps, listOf(Manifest.permission.POST_NOTIFICATIONS))?.permissions,
        )
        assertNull(nextPermissionStep(steps, emptyList()))
    }
}
