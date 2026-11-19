// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.ClosureState_E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // A limit the car reports off the 5% grid snaps to the next boundary.
        assertEquals(90, nextChargeLimit(88, 1, 50..100))
        assertEquals(85, nextChargeLimit(88, -1, 50..100))
        assertEquals(90, nextChargeLimit(92, -1, 50..100))
    }

    @Test
    fun `the current steps by one amp and clamps to the car's maximum`() {
        assertEquals(17, nextChargingAmps(16, 1, 32))
        assertEquals(15, nextChargingAmps(16, -1, 32))
        assertEquals(4, nextChargingAmps(5, -1, 32))
        assertEquals(1, nextChargingAmps(1, -1, 32))
        assertEquals(32, nextChargingAmps(32, 1, 32))
        // A car that reports a current below the usual five still steps from where it is.
        assertEquals(4, nextChargingAmps(3, 1, 32))
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
    fun `the controls show unplugged and on a fast charger`() {
        val unplugged =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Disconnected = Void())),
                actions,
                portOpen = false,
                enabled = true,
                asleep = false,
            )
        assertNotNull(unplugged)
        assertEquals(true, unplugged?.limit?.enabled)
        val fast =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Charging = Void()), fast = true),
                actions,
                portOpen = false,
                enabled = true,
                asleep = false,
            )
        assertNotNull(fast)
        assertEquals(true, fast?.limit?.enabled)
        assertEquals(false, fast?.amps?.enabled)
        assertNull(chargeControlsModel(null, actions, portOpen = false, enabled = true, asleep = false))
    }

    @Test
    fun `a car that reports a current below five amps keeps it in range`() {
        val slow =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Stopped = Void()), amps = 3, maxAmps = 32),
                actions,
                portOpen = false,
                enabled = true,
                asleep = false,
            )
        val amps = slow?.amps
        assertNotNull(amps)
        assertEquals(3, amps?.current)
        assertEquals(MIN_CHARGING_AMPS, amps?.range?.first)
        assertEquals("minus steps below the usual five", 2, amps?.step?.invoke(3, -1))
        assertEquals(4, amps?.step?.invoke(3, 1))
    }

    @Test
    fun `a held card disables its controls while the link is down`() {
        val model = chargeControlsModel(charge(), actions, portOpen = false, enabled = false, asleep = false)
        assertNotNull(model)
        assertEquals(false, model?.enabled)
        assertEquals(false, model?.limit?.enabled)
        assertEquals(false, model?.amps?.enabled)
    }

    @Test
    fun `an awake car with an open port waits for its first reading`() {
        assertNull(chargeControlsModel(null, actions, portOpen = false, enabled = true, asleep = false))
        val noState = chargeControlsModel(null, actions, portOpen = true, enabled = true, asleep = false)
        assertNotNull(noState)
        assertEquals(ChargeButton.NONE, noState?.button)
        assertEquals(true, noState?.reading)
        // The rows keep their place while the first reading is on its way; only the values are missing.
        assertNotNull(noState?.limit)
        assertNull(noState?.limit?.current)
        assertEquals(false, noState?.limit?.enabled)
        assertNotNull(noState?.amps)
        assertNull(noState?.amps?.current)
        assertEquals(false, noState?.amps?.enabled)
        val unplugged =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Disconnected = Void())),
                actions,
                portOpen = true,
                enabled = true,
                asleep = false,
            )
        assertEquals(ChargeButton.NONE, unplugged?.button)
        assertEquals(false, unplugged?.reading)
        val complete =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Complete = Void())),
                actions,
                portOpen = true,
                enabled = true,
                asleep = false,
            )
        assertEquals(ChargeButton.NONE, complete?.button)
        val charging =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Charging = Void())),
                actions,
                portOpen = true,
                enabled = true,
                asleep = false,
            )
        assertEquals(ChargeButton.STOP, charging?.button)
        assertEquals(false, charging?.reading)
    }

    @Test
    fun `a sleeping car with an open port assumes charging stopped`() {
        val noState = chargeControlsModel(null, actions, portOpen = true, enabled = true, asleep = true)
        assertNotNull(noState)
        assertEquals(ChargeButton.START, noState?.button)
        assertEquals(false, noState?.reading)
        assertNotNull(noState?.limit)
        assertNull(noState?.limit?.current)
        val charging =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Charging = Void())),
                actions,
                portOpen = true,
                enabled = true,
                asleep = true,
            )
        assertEquals(ChargeButton.START, charging?.button)
        // A closed port keeps the reported state: sleep alone does not invent a start.
        val closed =
            chargeControlsModel(
                charge(state = ChargeState.ChargingState(Charging = Void())),
                actions,
                portOpen = false,
                enabled = true,
                asleep = true,
            )
        assertEquals(ChargeButton.STOP, closed?.button)
    }

    @Test
    fun `the port signal comes from the closure or the charge state`() {
        assertTrue(isPortOpen(ClosureState_E.CLOSURESTATE_OPEN, doorOpen = null))
        assertTrue(isPortOpen(closure = null, doorOpen = true))
        assertTrue(isPortOpen(ClosureState_E.CLOSURESTATE_CLOSED, doorOpen = true))
        assertFalse(isPortOpen(ClosureState_E.CLOSURESTATE_CLOSED, doorOpen = false))
        assertFalse(isPortOpen(ClosureState_E.CLOSURESTATE_CLOSED, doorOpen = null))
        assertFalse("only a fully open door counts", isPortOpen(ClosureState_E.CLOSURESTATE_AJAR, doorOpen = null))
        assertFalse(isPortOpen(closure = null, doorOpen = null))
    }

    private fun charge(
        state: ChargeState.ChargingState = ChargeState.ChargingState(Charging = Void()),
        fast: Boolean = false,
        amps: Int = 16,
        maxAmps: Int? = null,
    ) = ChargeState(
        charging_state = state,
        charge_limit_soc = 80,
        charge_current_request = amps,
        charge_current_request_max = maxAmps,
        fast_charger_present = fast,
    )

    private companion object {
        val actions = ChargeControlActions(setLimit = {}, setAmps = {}, start = {}, stop = {})
    }
}
