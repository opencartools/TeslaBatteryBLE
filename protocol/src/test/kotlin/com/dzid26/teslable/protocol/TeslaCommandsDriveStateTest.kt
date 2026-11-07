// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import com.squareup.wire.ofEpochSecond
import com.tesla.generated.carserver.common.LatLong
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.ShiftState
import com.tesla.generated.carserver.vehicle.VehicleData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The DriveState request and reply helpers. Like the charge-state request,
 * they have no Go vector in `tools/go-fixtures`: the request is checked by
 * decoding it, the reply by parsing messages built from Tesla's protos.
 */
class TeslaCommandsDriveStateTest {
    /** A moving car with an active route: every field set, as the car may send it. */
    private val driving =
        DriveState(
            shift_state = ShiftState(D = Void()),
            speed = 42,
            power = -7,
            timestamp = ofEpochSecond(2_000, 0),
            odometer_in_hundredths_of_a_mile = 1_234_567,
            speed_float = 42.4f,
            active_route_destination = "Test destination",
            active_route_minutes_to_arrival = 12.5f,
            active_route_miles_to_arrival = 6.25f,
            active_route_traffic_minutes_delay = 1.5f,
            active_route_energy_at_arrival = 61.5f,
            last_route_update = 1_790_000_000,
            last_traffic_update = ofEpochSecond(1_990, 0),
            active_route_coordinates = LatLong(latitude = 12.5f, longitude = 34.5f),
        )

    @Test
    fun `the drive request asks for the drive state and no other category`() {
        // One state category per GetVehicleData, the way Tesla's Go SDK requests them.
        val request = Action.ADAPTER.decode(TeslaCommands.buildDriveStateRequest())
        assertEquals(GetVehicleData(getDriveState = GetDriveState()), request.vehicleAction?.getVehicleData)
    }

    @Test
    fun `parses the whole drive state out of a vehicle data reply`() {
        // The raw message comes back unchanged: nothing is filtered, the navigation destination and route included.
        val payload = Response(vehicleData = VehicleData(drive_state = driving)).encode()
        assertEquals(driving, TeslaCommands.parseDriveState(payload))
    }

    @Test
    fun `a reply without a drive state parses to null`() {
        val chargeReply = Response(vehicleData = VehicleData(charge_state = ChargeState(battery_level = 50))).encode()
        assertNull(TeslaCommands.parseDriveState(chargeReply))
        assertNull(TeslaCommands.parseDriveState(Response().encode()))
    }

    @Test
    fun `a refused request parses to null and leaves the reason readable`() {
        val refusal = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_ERROR)).encode()
        assertNull(TeslaCommands.parseDriveState(refusal))
        assertEquals(OperationStatus_E.OPERATIONSTATUS_ERROR, TeslaCommands.parseActionStatus(refusal))
    }
}
