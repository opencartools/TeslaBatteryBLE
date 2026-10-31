// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.ble.BleUiState
import org.junit.Rule
import org.junit.Test

/** Paparazzi snapshots of the setup banners above the cars list that the onboarding does not cover. */
class SetupBannerSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    // A scan was needed (a pull, or a reconnect that has to rediscover the car) and location is missing.
    @Test
    fun locationNeeded() =
        paparazzi.snapCard("setup-banner_location-needed") {
            SetupBanners(
                state = BleUiState(locationNeeded = true),
                permissionsGranted = true,
                locationServicesEnabled = true,
                onRequestPermissions = {},
                onRequestLocation = {},
            )
        }
}
