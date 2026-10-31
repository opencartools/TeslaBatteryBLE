// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ChargePortDoorOpen
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.server.VehicleControlFlashLightsAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The charge-port and flash-lights builders. Like the other request helpers they have no Go vector
 * in `tools/go-fixtures`: each request is checked by decoding it back to the `VehicleAction` field it
 * must carry.
 */
class TeslaCommandsActionsTest {
    @Test
    fun `the charge port request opens the charge port door and nothing else`() {
        val request = Action.ADAPTER.decode(TeslaCommands.buildChargePortOpenRequest())

        assertEquals(VehicleAction(chargePortDoorOpen = ChargePortDoorOpen()), request.vehicleAction)
        assertNull(request.vehicleAction?.getVehicleData)
        assertNull(request.vehicleAction?.vehicleControlFlashLightsAction)
    }

    @Test
    fun `the flash lights request flashes the lights and nothing else`() {
        val request = Action.ADAPTER.decode(TeslaCommands.buildFlashLightsRequest())

        assertEquals(VehicleAction(vehicleControlFlashLightsAction = VehicleControlFlashLightsAction()), request.vehicleAction)
        assertNull(request.vehicleAction?.getVehicleData)
        assertNull(request.vehicleAction?.chargePortDoorOpen)
    }
}
