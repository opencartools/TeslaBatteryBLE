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

    @Test
    fun onboardingGreeting() = snapOnboarding("onboarding_greeting") { PermissionWizardGreeting(onContinue = {}, onDismiss = {}) }

    @Test
    fun onboardingNearby() = snapOnboarding("onboarding_nearby") { page(0) }

    @Test
    fun onboardingLocation() = snapOnboarding("onboarding_location") { page(1) }

    @Test
    fun onboardingNotifications() = snapOnboarding("onboarding_notifications") { page(2) }

    @Test
    fun onboardingNearbyDenied() = snapOnboarding("onboarding_nearby-denied") { page(0, denied = true) }

    @Test
    fun onboardingNearbyDeniedForever() =
        snapOnboarding("onboarding_nearby-denied-forever") { page(0, denied = true, deniedForever = true) }

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
            deviceConfig = jellyStar.copy(screenHeight = JELLY_STAR_PAGE_HEIGHT),
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_jelly_light") { ScreenFrame(darkTheme = false, content = content) }
    }

    private companion object {
        /** Tall enough for the longest page at 240dp wide, where the text wraps a lot, so its buttons show. */
        const val JELLY_STAR_PAGE_HEIGHT = 1700
    }
}
