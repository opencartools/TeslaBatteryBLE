// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import android.Manifest
import androidx.compose.runtime.Composable
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi snapshots of the permission onboarding: the greeting and each wizard page, light and dark on the
 * full-screen phone of [CardSnapshots] and light at Jelly Star width, plus the denied states of the first step.
 */
class OnboardingSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val steps =
        permissionSteps(
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )

    // Each Jelly Star render is as tall as its page needs, in px at that width: the text wraps a lot at 240dp, so
    // a page is far taller than the 854px screen, and one shared height would leave a void under the short ones.
    @Test
    fun onboardingGreeting() =
        snapOnboarding("onboarding_greeting", jellyStarHeight = 1150) {
            PermissionWizardGreeting(onContinue = {}, onDismiss = {})
        }

    @Test
    fun onboardingNearby() = snapOnboarding("onboarding_nearby", jellyStarHeight = 1500) { page(0) }

    @Test
    fun onboardingLocation() = snapOnboarding("onboarding_location", jellyStarHeight = 1450) { page(1) }

    @Test
    fun onboardingNotifications() = snapOnboarding("onboarding_notifications", jellyStarHeight = 1700) { page(2) }

    @Test
    fun onboardingNearbyDenied() = snapOnboarding("onboarding_nearby-denied", jellyStarHeight = 1600) { page(0, denied = true) }

    @Test
    fun onboardingNearbyDeniedForever() =
        snapOnboarding("onboarding_nearby-denied-forever", jellyStarHeight = 1700) {
            page(0, denied = true, deniedForever = true)
        }

    @Composable
    private fun page(
        index: Int,
        denied: Boolean = false,
        deniedForever: Boolean = false,
    ) = PermissionWizardPage(
        step = steps[index],
        index = index,
        total = steps.size,
        denied = denied,
        deniedForever = deniedForever,
        onAllow = {},
        onDismiss = {},
    )

    private fun snapOnboarding(
        name: String,
        jellyStarHeight: Int,
        content: @Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = screenPhone,
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_light") { ScreenFrame(darkTheme = false, content = content) }
        paparazzi.snapshot(name + "_dark") { ScreenFrame(darkTheme = true, content = content) }
        // The narrow Jelly Star width on a tall canvas, so the whole page and its buttons show.
        paparazzi.unsafeUpdateConfig(
            deviceConfig = jellyStar.copy(screenHeight = jellyStarHeight),
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_jelly_light") { ScreenFrame(darkTheme = false, content = content) }
    }
}
