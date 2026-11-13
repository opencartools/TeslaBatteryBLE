// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ChargingSetLimitAction
import com.tesla.generated.carserver.server.ChargingStartStopAction
import com.tesla.generated.carserver.server.SetChargingAmpsAction
import com.tesla.generated.carserver.server.VehicleAction

/** The charge controls' Infotainment requests: limit, current, start and stop. */
object TeslaChargeCommands {
    /** Sets the charge limit in percent: `ChargingSetLimitAction`, the `VehicleAction` field 5. */
    fun buildChargeLimitRequest(percent: Int): ByteArray =
        Action(
            vehicleAction = VehicleAction(chargingSetLimitAction = ChargingSetLimitAction(percent = percent)),
        ).encode()

    /** Starts charging: `ChargingStartStopAction`, the `VehicleAction` field 6. */
    fun buildChargeStartRequest(): ByteArray = buildStartStopRequest(ChargingStartStopAction(start = Void()))

    /** Stops charging: `ChargingStartStopAction`, the `VehicleAction` field 6. */
    fun buildChargeStopRequest(): ByteArray = buildStartStopRequest(ChargingStartStopAction(stop = Void()))

    /** Sets the charging current in amps: `SetChargingAmpsAction`, the `VehicleAction` field 43. */
    fun buildChargingAmpsRequest(amps: Int): ByteArray =
        Action(
            vehicleAction = VehicleAction(setChargingAmpsAction = SetChargingAmpsAction(charging_amps = amps)),
        ).encode()

    private fun buildStartStopRequest(action: ChargingStartStopAction): ByteArray =
        Action(vehicleAction = VehicleAction(chargingStartStopAction = action)).encode()
}
