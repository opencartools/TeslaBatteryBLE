// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.dzid26.teslable.ble.ActuationKey
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import org.junit.Rule
import org.junit.Test

/** Paparazzi snapshots of the charge controls, alone and in the car screen's card flow. */
class ChargeControlsSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun chargeControlsCard() =
        paparazzi.snapCard("charge-controls-card") {
            ChargeControlsCard(model = model(charge(charging)), actuating = emptySet(), failure = null, actions = actions)
        }

    @Test
    fun chargeControlsCardUnplugged() =
        paparazzi.snapCard("charge-controls-card_unplugged") {
            ChargeControlsCard(model = model(charge(unplugged)), actuating = emptySet(), failure = null, actions = actions)
        }

    @Test
    fun chargeControlsCardFailure() =
        paparazzi.snapCard("charge-controls-card_refused") {
            ChargeControlsCard(
                model = model(charge(unplugged)),
                actuating = emptySet(),
                failure = ActuationKey.CHARGE_LIMIT,
                actions = actions,
            )
        }

    @Test
    fun chargeControlsCardPending() =
        paparazzi.snapCard("charge-controls-card_pending") {
            ChargeControlsCard(
                model = model(charge(charging)),
                actuating = setOf(ActuationKey.CHARGE_LIMIT, ActuationKey.CHARGE_STOP),
                failure = null,
                actions = actions,
            )
        }

    @Test
    fun chargeControlsCardStarting() =
        paparazzi.snapCard("charge-controls-card_starting") {
            ChargeControlsCard(
                model = model(charge(stopped)),
                actuating = setOf(ActuationKey.CHARGE_START),
                failure = null,
                actions = actions,
            )
        }

    @Test
    fun chargeControlsCardAtLimit() =
        paparazzi.snapCard("charge-controls-card_at-limit") {
            ChargeControlsCard(
                model = model(charge(charging, limit = 100)),
                actuating = setOf(ActuationKey.CHARGING_AMPS),
                failure = null,
                actions = actions,
            )
        }

    @Test
    fun chargeControlsCardFastCharging() =
        paparazzi.snapCard("charge-controls-card_fast-charging") {
            ChargeControlsCard(
                model = model(charge(charging, fast = true)),
                actuating = emptySet(),
                failure = null,
                actions = actions,
            )
        }

    @Test
    fun chargeControlsCardLargeFont() {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.NEXUS_5.copy(fontScale = 1.5f),
            renderingMode = SessionParams.RenderingMode.SHRINK,
        )
        paparazzi.snapshot("charge-controls-card_large-font") {
            CardFrame(darkTheme = false) {
                ChargeControlsCard(model = model(charge(charging)), actuating = emptySet(), failure = null, actions = actions)
            }
        }
    }

    @Test
    fun screenCarDetailUnplugged() {
        paparazzi.snapFullScreen("screen-car-detail_unplugged") {
            CarScreen(
                state = DemoScreenFixtures.drivingState(NOW),
                vehicle = DemoScreenFixtures.vehicle,
                advert = DemoScreenFixtures.carAdvert,
                address = DemoScreenFixtures.DEMO_ADDRESS,
                history = DemoScreenFixtures.drivingHistory(NOW),
                driveHistory = DemoScreenFixtures.drives(NOW),
                statusHistory = emptyList(),
                onBack = {},
                onEdit = {},
                onAddVin = {},
                onPair = {},
                onRefresh = {},
                onOpenSettings = {},
                onToggleTracking = {},
                onSetChargeLimit = {},
                onSetChargingAmps = {},
                onStartCharging = {},
                onStopCharging = {},
                onActionFeedbackShown = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun screenCarDetailFastCharging() {
        paparazzi.snapFullScreen("screen-car-detail_fast-charging") {
            CarScreen(
                state = DemoScreenFixtures.fastChargingState(NOW),
                vehicle = DemoScreenFixtures.vehicle,
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
                onSetChargeLimit = {},
                onSetChargingAmps = {},
                onStartCharging = {},
                onStopCharging = {},
                onActionFeedbackShown = {},
                nowMillis = NOW,
            )
        }
    }

    private fun charge(
        state: ChargeState.ChargingState,
        limit: Int = 85,
        amps: Int = 16,
        fast: Boolean = false,
    ) = ChargeState(
        charging_state = state,
        charge_limit_soc = limit,
        charge_limit_soc_min = 50,
        charge_limit_soc_max = 100,
        charge_current_request = amps,
        charge_current_request_max = 32,
        fast_charger_present = fast,
        battery_level = 78,
        usable_battery_level = 78,
        battery_range = 234f,
    )

    private fun model(charge: ChargeState): ChargeControlsModel = checkNotNull(chargeControlsModel(charge, actions))

    private companion object {
        const val NOW = 1_760_000_000_000L

        val charging = ChargeState.ChargingState(Charging = Void())
        val unplugged = ChargeState.ChargingState(Disconnected = Void())
        val stopped = ChargeState.ChargingState(Stopped = Void())
        val actions = ChargeControlActions(setLimit = {}, setAmps = {}, start = {}, stop = {}, clearFailure = {})
    }
}
