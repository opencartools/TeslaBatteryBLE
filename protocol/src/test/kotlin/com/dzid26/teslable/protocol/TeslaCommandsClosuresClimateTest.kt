// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import com.squareup.wire.ofEpochSecond
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.GetClimateState
import com.tesla.generated.carserver.server.GetClosuresState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.VehicleData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ClosuresState and ClimateState request and reply helpers. Like the drive-state ones they have
 * no Go vector in `tools/go-fixtures`: the requests are checked by decoding them, the replies by
 * parsing messages built from Tesla's protos.
 */
class TeslaCommandsClosuresClimateTest {
    private val sentryArmed =
        ClosuresState(
            sentry_mode_state = ClosuresState.SentryModeState(Armed = Void()),
            locked = true,
            door_open_driver_front = false,
            timestamp = ofEpochSecond(2_000, 0),
        )

    private val keeping =
        ClimateState(
            is_climate_on = true,
            climate_keeper_mode = ClimateState.ClimateKeeperMode(Dog = Void()),
            inside_temp_celsius = 21.5f,
            timestamp = ofEpochSecond(2_000, 0),
        )

    @Test
    fun `the closures request asks for the closures state and no other category`() {
        val request = Action.ADAPTER.decode(TeslaCommands.buildClosuresStateRequest())
        assertEquals(GetVehicleData(getClosuresState = GetClosuresState()), request.vehicleAction?.getVehicleData)
    }

    @Test
    fun `the climate request asks for the climate state and no other category`() {
        val request = Action.ADAPTER.decode(TeslaCommands.buildClimateStateRequest())
        assertEquals(GetVehicleData(getClimateState = GetClimateState()), request.vehicleAction?.getVehicleData)
    }

    @Test
    fun `parses the whole closures state out of a vehicle data reply`() {
        val payload = Response(vehicleData = VehicleData(closures_state = sentryArmed)).encode()
        val parsed = TeslaCommands.parseClosuresState(payload)
        assertEquals(sentryArmed, parsed)
        assertTrue(parsed!!.sentryOn)
    }

    @Test
    fun `parses the whole climate state out of a vehicle data reply`() {
        val payload = Response(vehicleData = VehicleData(climate_state = keeping)).encode()
        val parsed = TeslaCommands.parseClimateState(payload)
        assertEquals(keeping, parsed)
        assertTrue(parsed!!.climateOn)
    }

    @Test
    fun `a reply for another category parses to null`() {
        val chargeReply = Response(vehicleData = VehicleData(charge_state = ChargeState(battery_level = 50))).encode()
        assertNull(TeslaCommands.parseClosuresState(chargeReply))
        assertNull(TeslaCommands.parseClimateState(chargeReply))
        assertNull(TeslaCommands.parseClosuresState(Response().encode()))
        assertNull(TeslaCommands.parseClimateState(Response().encode()))
    }

    @Test
    fun `a refused request parses to null and leaves the reason readable`() {
        val refusal = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_ERROR)).encode()
        assertNull(TeslaCommands.parseClosuresState(refusal))
        assertNull(TeslaCommands.parseClimateState(refusal))
        assertEquals(OperationStatus_E.OPERATIONSTATUS_ERROR, TeslaCommands.parseActionStatus(refusal))
    }
}
