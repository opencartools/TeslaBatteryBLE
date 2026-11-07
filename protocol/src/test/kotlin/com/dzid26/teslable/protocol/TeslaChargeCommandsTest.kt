// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.server.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The charge controls' requests decode back to the `VehicleAction` the car expects. */
class TeslaChargeCommandsTest {
    @Test
    fun `sets the charge limit`() {
        val action = decode(TeslaChargeCommands.buildChargeLimitRequest(80))

        assertEquals(80, action.vehicleAction?.chargingSetLimitAction?.percent)
    }

    @Test
    fun `starts and stops charging`() {
        assertNotNull(decode(TeslaChargeCommands.buildChargeStartRequest()).vehicleAction?.chargingStartStopAction?.start)
        assertNotNull(decode(TeslaChargeCommands.buildChargeStopRequest()).vehicleAction?.chargingStartStopAction?.stop)
    }

    @Test
    fun `sets the charging current`() {
        val action = decode(TeslaChargeCommands.buildChargingAmpsRequest(16))

        assertEquals(16, action.vehicleAction?.setChargingAmpsAction?.charging_amps)
    }

    private fun decode(bytes: ByteArray): Action = Action.ADAPTER.decode(bytes)
}
