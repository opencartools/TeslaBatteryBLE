// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ChargePortDoorOpen
import com.tesla.generated.carserver.server.GetChargeState
import com.tesla.generated.carserver.server.GetClimateState
import com.tesla.generated.carserver.server.GetClosuresState
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage

/**
 * The protocol's request builders and reply parsers. The object grows with every car-server
 * action the app can send, so the function-count threshold is waived here on purpose: splitting
 * the wake/VCSEC helpers, the reads and the actions into separate objects would only make the
 * library API harder to find.
 */
@Suppress("TooManyFunctions")
object TeslaCommands {
    fun buildWakeRequest(): ByteArray = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_WAKE_VEHICLE).encode()

    fun buildChargeStateRequest(): ByteArray =
        Action(
            vehicleAction =
                VehicleAction(
                    getVehicleData = GetVehicleData(getChargeState = GetChargeState()),
                ),
        ).encode()

    /** One state category per `GetVehicleData`, as Tesla's Go SDK requests them (`pkg/vehicle/state.go`). */
    fun buildDriveStateRequest(): ByteArray =
        Action(
            vehicleAction =
                VehicleAction(
                    getVehicleData = GetVehicleData(getDriveState = GetDriveState()),
                ),
        ).encode()

    /**
     * Asks for the `ClosuresState`, the category that holds the sentry mode state. Tesla's protos have a
     * `VehicleState` message too, but it carries only the guest mode and no `GetVehicleData` category
     * returns it.
     */
    fun buildClosuresStateRequest(): ByteArray =
        Action(
            vehicleAction =
                VehicleAction(
                    getVehicleData = GetVehicleData(getClosuresState = GetClosuresState()),
                ),
        ).encode()

    fun buildClimateStateRequest(): ByteArray =
        Action(
            vehicleAction =
                VehicleAction(
                    getVehicleData = GetVehicleData(getClimateState = GetClimateState()),
                ),
        ).encode()

    /** The car-server action that opens the charge-port door (field 62). */
    fun buildChargePortOpenRequest(): ByteArray =
        Action(
            vehicleAction =
                VehicleAction(
                    chargePortDoorOpen = ChargePortDoorOpen(),
                ),
        ).encode()

    /**
     * The car's raw `ChargeState` from a vehicle-data reply, or null when the
     * reply holds none. It is returned whole: the history logs it verbatim
     * (ADR-0008), and derived values come from [chargingStateKind] and the
     * other properties in `StateViews.kt`.
     */
    fun parseChargeState(payload: ByteArray): ChargeState? {
        val data = Response.ADAPTER.decode(payload).vehicleData ?: return null
        return data.charge_state
    }

    /**
     * The car's raw `DriveState` from a vehicle-data reply, or null when the
     * reply holds none. It is returned whole rather than parsed into fields:
     * the history logs it verbatim, navigation destination and route included
     * (ADR-0008).
     */
    fun parseDriveState(payload: ByteArray): DriveState? {
        val data = Response.ADAPTER.decode(payload).vehicleData ?: return null
        return data.drive_state
    }

    /**
     * The car's raw `ClosuresState` from a vehicle-data reply, or null when the reply holds none. It
     * is returned whole: the history logs it verbatim (ADR-0008); [sentryOn] derives the sentry flag.
     */
    fun parseClosuresState(payload: ByteArray): ClosuresState? {
        val data = Response.ADAPTER.decode(payload).vehicleData ?: return null
        return data.closures_state
    }

    /**
     * The car's raw `ClimateState` from a vehicle-data reply, or null when the reply holds none. It
     * is returned whole: the history logs it verbatim (ADR-0008); [climateOn] derives the climate flag.
     */
    fun parseClimateState(payload: ByteArray): ClimateState? {
        val data = Response.ADAPTER.decode(payload).vehicleData ?: return null
        return data.climate_state
    }

    fun parseActionStatus(payload: ByteArray): String? =
        Response.ADAPTER
            .decode(payload)
            .actionStatus
            ?.result
            ?.name
}
