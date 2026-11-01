// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.Vehicle
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The car screen with the quick-action stack open: the fab and its labelled actions over the demo car,
 * light and dark, on the same 1080x2400 phone as the other screen snapshots. The no-VIN, folded,
 * asleep and large-font states cover the ones the layout has to survive.
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
    fun screenCarDetailActions() = both("screen-car-detail-actions", screenPhone) { carScreen() }

    /** A car with no VIN shows the VIN prompt card and the stack's button together. */
    @Test
    fun screenCarDetailActionsWithoutVin() =
        both("screen-car-detail-actions-no-vin", screenPhone) {
            carScreen(vehicle = DemoScreenFixtures.vehicle.copy(vin = null))
        }

    /** The stack folded: only the button is on screen, over the VIN card. */
    @Test
    fun screenCarDetailActionsFolded() =
        both("screen-car-detail-actions-folded", screenPhone) {
            carScreen(vehicle = DemoScreenFixtures.vehicle.copy(vin = null), expanded = false)
        }

    /** A sleeping car, where Wake car is no longer the disabled row. */
    @Test
    fun screenCarDetailActionsAsleep() = both("screen-car-detail-actions-asleep", screenPhone) { carScreen(state = asleepState()) }

    /** The stack at a 1.5x font scale: the labels must stay on one line at this width. */
    @Test
    fun screenCarDetailActionsLargeFont() = both("screen-car-detail-actions-large-font", screenPhone.copy(fontScale = 1.5f)) { carScreen() }

    private fun both(
        name: String,
        device: DeviceConfig,
        content: @Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = device,
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_light") { ScreenFrame(darkTheme = false, content = content) }
        paparazzi.snapshot(name + "_dark") { ScreenFrame(darkTheme = true, content = content) }
    }

    @Composable
    private fun carScreen(
        vehicle: Vehicle = DemoScreenFixtures.vehicle,
        state: BleUiState = DemoScreenFixtures.carState(NOW),
        expanded: Boolean = true,
    ) {
        CarScreen(
            state = state,
            vehicle = vehicle,
            advert = DemoScreenFixtures.carAdvert,
            address = DemoScreenFixtures.DEMO_ADDRESS,
            history = DemoScreenFixtures.history(NOW),
            driveHistory = DemoScreenFixtures.drives(NOW),
            statusHistory = emptyList(),
            onBack = {},
            onEdit = {},
            onAddVin = {},
            onPair = {},
            onRefresh = {},
            onOpenSettings = {},
            onToggleTracking = {},
            onQuickAction = {},
            onActionFeedbackShown = {},
            initialQuickActionsExpanded = expanded,
            nowMillis = NOW,
        )
    }

    /** The demo car asleep, so the stack's Wake car row is the enabled one. */
    private fun asleepState(): BleUiState {
        val asleep =
            VehicleStatus(
                vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
                userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
            )
        val state = DemoScreenFixtures.carState(NOW)
        return state.copy(connections = state.connections.mapValues { it.value.copy(status = asleep) })
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
