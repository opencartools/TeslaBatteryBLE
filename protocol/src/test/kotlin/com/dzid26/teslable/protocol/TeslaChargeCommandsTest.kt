// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.server.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale

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

    /**
     * The exact bytes `vehicle-command` at `protocol/src/main/proto/TESLA_COMMIT` marshals for the
     * same `VehicleAction`. Decoding our own output cannot catch a wrong field number or oneof.
     */
    @Test
    fun `the Go oracle bytes match`() {
        assertEquals("12042a020850", hex(TeslaChargeCommands.buildChargeLimitRequest(80)))
        assertEquals("1205da02020810", hex(TeslaChargeCommands.buildChargingAmpsRequest(16)))
        assertEquals("120432021200", hex(TeslaChargeCommands.buildChargeStartRequest()))
        assertEquals("120432022a00", hex(TeslaChargeCommands.buildChargeStopRequest()))
    }

    private fun decode(bytes: ByteArray): Action = Action.ADAPTER.decode(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
