// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The car screen with the quick-action stack open: the fab and its labelled actions over the demo car,
 * light and dark, on the same 1080x2400 phone as the other screen snapshots.
 */
class QuickActionSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun screenCarDetailActions() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = screenPhone,
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot("screen-car-detail-actions_light") {
            ScreenFrame(darkTheme = false) { carScreen() }
        }
        paparazzi.snapshot("screen-car-detail-actions_dark") {
            ScreenFrame(darkTheme = true) { carScreen() }
        }
    }

    @Composable
    private fun carScreen() {
        CarScreen(
            state = DemoScreenFixtures.carState(NOW),
            vehicle = DemoScreenFixtures.vehicle,
            advert = DemoScreenFixtures.carAdvert,
            address = DemoScreenFixtures.DEMO_ADDRESS,
            history = DemoScreenFixtures.history(NOW),
            driveHistory = DemoScreenFixtures.drives(NOW),
            statusHistory = emptyList(),
            onBack = {},
            onEdit = {},
            onPair = {},
            onRefresh = {},
            onOpenSettings = {},
            onToggleTracking = {},
            onQuickAction = {},
            onActionFeedbackShown = {},
            initialQuickActionsExpanded = true,
            nowMillis = NOW,
        )
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
