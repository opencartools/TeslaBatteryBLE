// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.dzid26.teslable.core.history.RouteSample
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The two full-screen renders of the driving demo car: the website, README and release screenshots
 * (see `.github/scripts/release-screenshots.sh`). The trip on top, then the view scrolled down to the drive card.
 */
class DrivingScreenSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Chart times read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun screenCarDetailNavigating() {
        // The car is navigating: the trip card sits above the battery history.
        val route =
            RouteSample(
                timestampMillis = NOW,
                vehicleId = DemoScreenFixtures.vehicle.bleName,
                destination = "Home",
                milesToArrival = 4.7f,
                minutesToArrival = 12.2f,
                trafficDelayMinutes = 0f,
                energyAtArrivalPercent = ARRIVAL_PERCENT,
                readAtMillis = NOW,
                tripStartMiles = 11.4f,
                tripStartedAtMillis = NOW - TRIP_ELAPSED_MILLIS,
            )
        snapScreen("screen-car-detail-navigating") {
            CarScreen(
                state = DemoScreenFixtures.drivingState(NOW),
                vehicle = DemoScreenFixtures.vehicle,
                advert = DemoScreenFixtures.carAdvert,
                address = DemoScreenFixtures.DEMO_ADDRESS,
                history = DemoScreenFixtures.drivingHistory(NOW),
                driveHistory = DemoScreenFixtures.drives(NOW),
                statusHistory = emptyList(),
                routes = mapOf(DemoScreenFixtures.vehicle.bleName to route),
                trips = mapOf(DemoScreenFixtures.vehicle.bleName to demoPastTrips(NOW)),
                onBack = {},
                onEdit = {},
                onPair = {},
                onRefresh = {},
                onOpenSettings = {},
                onToggleTracking = {},
                nowMillis = NOW,
                homePrice = HOME_PRICE,
            )
        }
    }

    @Test
    fun screenCarDetailDriving() {
        // The same driving car scrolled down to the drive card and its chart: the second release screenshot.
        snapScreen("screen-car-detail-driving") {
            CarScreen(
                state = DemoScreenFixtures.drivingState(NOW),
                vehicle = DemoScreenFixtures.vehicle,
                advert = DemoScreenFixtures.carAdvert,
                address = DemoScreenFixtures.DEMO_ADDRESS,
                history = DemoScreenFixtures.drivingHistory(NOW),
                driveHistory = DemoScreenFixtures.drives(NOW),
                statusHistory = DemoScreenFixtures.statuses(NOW),
                onBack = {},
                onEdit = {},
                onPair = {},
                onRefresh = {},
                onOpenSettings = {},
                onToggleTracking = {},
                nowMillis = NOW,
                homePrice = HOME_PRICE,
                scrollState = rememberScrollState(DRIVE_CARD_SCROLL_PX),
            )
        }
    }

    /** The full screen on the 1080x2400 phone of [CardSnapshots], light and dark. */
    private fun snapScreen(
        name: String,
        content: @Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = screenPhone,
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_light") { ScreenFrame(darkTheme = false, content = content) }
        paparazzi.snapshot(name + "_dark") { ScreenFrame(darkTheme = true, content = content) }
    }

    private companion object {
        const val HOME_PRICE = 0.30f

        /** Scrolls the car view to its end, so the drive card starts just under the top bar and the drain stats show in full. */
        const val DRIVE_CARD_SCROLL_PX = Int.MAX_VALUE

        /** The live trip began fourteen minutes before the render. */
        const val TRIP_ELAPSED_MILLIS = 14 * 60_000L

        /** A little under the demo car's 78%: a short trip leaves a plausible level. */
        const val ARRIVAL_PERCENT = 76f

        const val NOW = 1_760_000_000_000L
    }
}
