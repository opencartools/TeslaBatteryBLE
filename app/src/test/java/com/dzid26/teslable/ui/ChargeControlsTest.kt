// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The charge card's steps, button and visibility, without a composition. */
class ChargeControlsTest {
    @Test
    fun `the limit steps by five and clamps to the car's range`() {
        assertEquals(85, nextChargeLimit(80, 1, 50..100))
        assertEquals(75, nextChargeLimit(80, -1, 50..100))
        assertEquals(50, nextChargeLimit(50, -1, 50..100))
        assertEquals(100, nextChargeLimit(98, 1, 50..100))
    }

    @Test
    fun `the current steps by one amp and clamps to the car's maximum`() {
        assertEquals(17, nextChargingAmps(16, 1, 32))
        assertEquals(15, nextChargingAmps(16, -1, 32))
        assertEquals(5, nextChargingAmps(5, -1, 32))
        assertEquals(32, nextChargingAmps(32, 1, 32))
        // A car that only offers less than the usual minimum can still go up to its own maximum.
        assertEquals(3, nextChargingAmps(3, 1, 3))
    }

    @Test
    fun `the button follows the car's charging state`() {
        assertEquals(ChargeButton.STOP, chargeButton(charge()))
        assertEquals(ChargeButton.START, chargeButton(charge(state = ChargeState.ChargingState(Stopped = Void()))))
        assertEquals(
            ChargeButton.NONE,
            chargeButton(charge(state = ChargeState.ChargingState(Complete = Void()))),
        )
        assertEquals(
            ChargeButton.NONE,
            chargeButton(charge(state = ChargeState.ChargingState(Disconnected = Void()))),
        )
    }

    @Test
    fun `the controls show unplugged and stay away on a fast charger`() {
        assertTrue(showsChargeControls(charge(state = ChargeState.ChargingState(Disconnected = Void()))))
        assertFalse(showsChargeControls(charge(state = ChargeState.ChargingState(Disconnected = Void()), fast = true)))
        assertFalse(showsChargeControls(null))
    }

    private fun charge(
        state: ChargeState.ChargingState = ChargeState.ChargingState(Charging = Void()),
        fast: Boolean = false,
    ) = ChargeState(
        charging_state = state,
        charge_limit_soc = 80,
        charge_current_request = 16,
        fast_charger_present = fast,
    )
}
