// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.Manifest
import androidx.annotation.StringRes
import com.dzid26.teslable.R

/**
 * One wizard page: the permissions requested together, with the explanation
 * shown before asking. Android 12+ groups scan and connect as "Nearby devices",
 * so they share a single page and a single system dialog.
 */
data class PermissionStep(
    val permissions: List<String>,
    @StringRes val title: Int,
    @StringRes val why: Int,
    val optional: Boolean = false,
)

private val NEARBY_DEVICES =
    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

/** Builds the wizard steps for the permissions this Android version needs. */
fun permissionSteps(permissions: List<String>): List<PermissionStep> =
    buildList {
        val nearby = permissions.filter { it in NEARBY_DEVICES }
        if (nearby.isNotEmpty()) {
            add(PermissionStep(nearby, R.string.perm_nearby_title, R.string.perm_nearby_why))
        }
        if (Manifest.permission.ACCESS_FINE_LOCATION in permissions) {
            add(
                PermissionStep(
                    permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    title = R.string.perm_location_title,
                    why = R.string.perm_location_why,
                ),
            )
        }
        if (Manifest.permission.POST_NOTIFICATIONS in permissions) {
            add(
                PermissionStep(
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    title = R.string.perm_notifications_title,
                    why = R.string.perm_notifications_why,
                    optional = true,
                ),
            )
        }
    }

/** The first step that still has a missing permission, or null when none is left. */
fun nextPermissionStep(
    steps: List<PermissionStep>,
    missing: List<String>,
): PermissionStep? = steps.firstOrNull { step -> step.permissions.any { it in missing } }
