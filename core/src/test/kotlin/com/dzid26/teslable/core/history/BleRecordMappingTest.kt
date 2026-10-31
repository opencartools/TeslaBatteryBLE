// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import com.squareup.wire.ofEpochSecond
import com.tesla.generated.carserver.common.LatLong
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.GetChargeState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.ResultReason
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.LocationState
import com.tesla.generated.carserver.vehicle.ShiftState
import com.tesla.generated.carserver.vehicle.TirePressureState
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.tesla.generated.carserver.server.OperationStatus_E as CarServerStatus
import com.tesla.generated.vcsec.OperationStatus_E as VcsecStatus

class BleRecordMappingTest {
    /** A charge reply the car stamped at 1000 s on its own clock. */
    private val charging =
        ChargeState(
            charging_state = ChargeState.ChargingState(Charging = Void()),
            charge_limit_soc = 85,
            battery_range = 206.61f,
            battery_level = 77,
            usable_battery_level = 77,
            charge_rate_mph_float = 32.5f,
            timestamp = ofEpochSecond(1_000, 0),
        )

    /** A drive reply the car stamped at 2000 s on its own clock, with every field set, an active route included. */
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

    private val inUse =
        VehicleStatus(
            closureStatuses = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN),
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
        )

    private val asleep =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        )

    private val connected = ConnectionEvent(state = ConnectionEvent.State.CONNECTED)

    private val disconnected = ConnectionEvent(state = ConnectionEvent.State.DISCONNECTED)

    @Test
    fun `envelope fields follow a reading's order on the wire`() {
        // Field numbers are the stored format (ADR-0008): time, VCSEC status,
        // charge, drive, then the signal strength (5), the connection event
        // (6), closures (7) and climate (8) added later. The logs are append-only, so renumbering would orphan
        // every record already written. The first byte of each encoding is the
        // field's tag.
        fun tag(record: BleRecord) = record.encode().first().toInt()

        val varint = 0
        val lengthDelimited = 2
        assertEquals(1 shl 3 or lengthDelimited, tag(BleRecord(device_timestamp = ofEpochSecond(1, 0))))
        assertEquals(2 shl 3 or lengthDelimited, tag(BleRecord(vehicle_status = asleep)))
        assertEquals(3 shl 3 or lengthDelimited, tag(BleRecord(charge_state = charging)))
        assertEquals(4 shl 3 or lengthDelimited, tag(BleRecord(drive_state = DriveState())))
        assertEquals(5 shl 3 or varint, tag(BleRecord(rssi = -60)))
        assertEquals(6 shl 3 or lengthDelimited, tag(BleRecord(connection_event = connected)))
        assertEquals(7 shl 3 or lengthDelimited, tag(BleRecord(closures_state = ClosuresState())))
        assertEquals(8 shl 3 or lengthDelimited, tag(BleRecord(climate_state = ClimateState())))
        // The command log's kinds took the next free numbers.
        assertEquals(9 shl 3 or lengthDelimited, tag(BleRecord(command = Command(reason = Command.Reason.USER_WAKE))))
        assertEquals(10 shl 3 or lengthDelimited, tag(BleRecord(command_result = CommandResult(timed_out = true))))
        assertEquals(11 shl 3 or lengthDelimited, tag(BleRecord(app_state = AppState(event = AppState.Event.SCREEN_ON))))
        // Location and tire pressure took the next two.
        assertEquals(12 shl 3 or lengthDelimited, tag(BleRecord(location_state = LocationState())))
        assertEquals(13 shl 3 or lengthDelimited, tag(BleRecord(tire_pressure_state = TirePressureState())))
    }

    @Test
    fun `location and tire pressure records round-trip through the log codec whole`() {
        val location = LocationState(latitude = 52.25f, longitude = 21.0f, geo_elevation = 112.5f)
        val tires = TirePressureState(tpms_pressure_fl = 2.9f)
        val records =
            listOf(
                BleRecord(device_timestamp = ofEpochSecond(4_001, 0), rssi = -61, location_state = location),
                BleRecord(device_timestamp = ofEpochSecond(4_002, 0), rssi = -62, tire_pressure_state = tires),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        assertEquals(location, decoded[0].location_state)
        assertEquals(tires, decoded[1].tire_pressure_state)
    }

    @Test
    fun `command, result and app state fields keep their numbers on the wire`() {
        val varint = 0
        val lengthDelimited = 2

        fun tag(
            field: Int,
            wireType: Int,
        ) = (field shl 3 or wireType).toByte()
        // Command: reason 1, then the request oneof 2 to 4.
        assertContentEquals(byteArrayOf(tag(1, varint), 1), Command(reason = Command.Reason.USER_WAKE).encode())
        assertEquals(tag(2, lengthDelimited), Command(vcsec = UnsignedMessage()).encode().first())
        assertEquals(tag(3, lengthDelimited), Command(infotainment = Action()).encode().first())
        assertEquals(tag(4, lengthDelimited), Command(session_info = RoutableMessage()).encode().first())
        // CommandResult: reason 1, domain 2, the status oneof 3 to 5, timed_out 6.
        assertEquals(tag(1, varint), CommandResult(reason = Command.Reason.USER_WAKE).encode().first())
        assertEquals(tag(2, varint), CommandResult(domain = Domain.DOMAIN_INFOTAINMENT).encode().first())
        assertEquals(tag(3, lengthDelimited), CommandResult(action_status = ActionStatus()).encode().first())
        assertEquals(tag(4, lengthDelimited), CommandResult(command_status = CommandStatus()).encode().first())
        assertEquals(tag(5, lengthDelimited), CommandResult(message_status = MessageStatus()).encode().first())
        assertContentEquals(byteArrayOf(tag(6, varint), 1), CommandResult(timed_out = true).encode())
        // AppState: event 1, start_reason 2.
        assertContentEquals(byteArrayOf(tag(1, varint), 2), AppState(event = AppState.Event.SCREEN_OFF).encode())
        assertContentEquals(
            byteArrayOf(tag(2, varint), 2),
            AppState(start_reason = AppState.StartReason.BOOT).encode(),
        )
    }

    @Test
    fun `reasons and app events keep their numbers on the wire`() {
        // Stored format: the enum numbers are part of every record already written.
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            Command.Reason.entries.map { it.value },
        )
        assertEquals(1, Command.Reason.USER_WAKE.value)
        assertEquals(2, Command.Reason.USER_REFRESH.value)
        assertEquals(3, Command.Reason.POLICY_ACTIVE.value)
        assertEquals(4, Command.Reason.POLICY_HOLD.value)
        assertEquals(5, Command.Reason.POLICY_SAFETY.value)
        assertEquals(6, Command.Reason.FRESH_START.value)
        assertEquals(7, Command.Reason.SESSION_HANDSHAKE.value)
        assertEquals(8, Command.Reason.FOLLOW_UP.value)
        assertEquals(9, Command.Reason.PAIRING.value)
        assertEquals(10, Command.Reason.KEY_LOOKUP.value)
        assertEquals(11, Command.Reason.USER_ACTION.value)
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6),
            AppState.Event.entries.map { it.value },
        )
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            AppState.StartReason.entries.map { it.value },
        )
    }

    @Test
    fun `command records round-trip through the log codec whole`() {
        val wake = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_WAKE_VEHICLE)
        val read =
            Action(
                vehicleAction = VehicleAction(getVehicleData = GetVehicleData(getChargeState = GetChargeState())),
            )
        val handshake =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
                session_info_request = SessionInfoRequest(),
            )
        val records =
            listOf(
                BleRecord(
                    device_timestamp = ofEpochSecond(20, 0),
                    rssi = -55,
                    command = Command(reason = Command.Reason.USER_WAKE, vcsec = wake),
                ),
                BleRecord(
                    device_timestamp = ofEpochSecond(21, 0),
                    rssi = -56,
                    command = Command(reason = Command.Reason.POLICY_HOLD, infotainment = read),
                ),
                BleRecord(
                    device_timestamp = ofEpochSecond(22, 0),
                    command = Command(reason = Command.Reason.SESSION_HANDSHAKE, session_info = handshake),
                ),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        assertEquals(wake, decoded[0].command?.vcsec)
        assertEquals(read, decoded[1].command?.infotainment)
        assertEquals(handshake, decoded[2].command?.session_info)
        assertNull(decoded[2].rssi)
        // Not a reading: no read model picks a command up.
        decoded.forEach {
            assertNull(it.toBatterySample("car"))
            assertNull(it.toStatusSample("car"))
            assertNull(it.toDriveSample("car"))
        }
    }

    @Test
    fun `command results round-trip through the log codec whole`() {
        val refusedRead =
            CommandResult(
                reason = Command.Reason.POLICY_ACTIVE,
                domain = Domain.DOMAIN_INFOTAINMENT,
                action_status =
                    ActionStatus(
                        result = CarServerStatus.OPERATIONSTATUS_ERROR,
                        result_reason = ResultReason(plain_text = "unavailable"),
                    ),
            )
        val refusedWake =
            CommandResult(
                reason = Command.Reason.USER_WAKE,
                domain = Domain.DOMAIN_VEHICLE_SECURITY,
                command_status = CommandStatus(operationStatus = VcsecStatus.OPERATIONSTATUS_ERROR),
            )
        val fault =
            CommandResult(
                reason = Command.Reason.FOLLOW_UP,
                domain = Domain.DOMAIN_INFOTAINMENT,
                message_status = MessageStatus(signed_message_fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY),
            )
        val silence =
            CommandResult(reason = Command.Reason.USER_REFRESH, domain = Domain.DOMAIN_INFOTAINMENT, timed_out = true)
        val records =
            listOf(refusedRead, refusedWake, fault, silence).mapIndexed { index, result ->
                BleRecord(device_timestamp = ofEpochSecond(30L + index, 0), rssi = -60, command_result = result)
            }
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        assertEquals(
            "unavailable",
            decoded[0]
                .command_result
                ?.action_status
                ?.result_reason
                ?.plain_text,
        )
        assertEquals(true, decoded[3].command_result?.timed_out)
        // A reply with a status carries no timed_out flag: absent, not false.
        assertNull(decoded[0].command_result?.timed_out)
    }

    @Test
    fun `app state records round-trip through the log codec whole`() {
        val records =
            listOf(
                BleRecord(
                    device_timestamp = ofEpochSecond(40, 0),
                    app_state =
                        AppState(
                            event = AppState.Event.TRACKING_STARTED,
                            start_reason = AppState.StartReason.PACKAGE_REPLACED,
                        ),
                ),
                BleRecord(device_timestamp = ofEpochSecond(41, 0), app_state = AppState(event = AppState.Event.SCREEN_OFF)),
                BleRecord(device_timestamp = ofEpochSecond(42, 0), app_state = AppState(event = AppState.Event.APP_BACKGROUND)),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        assertEquals(AppState.StartReason.PACKAGE_REPLACED, decoded[0].app_state?.start_reason)
        // Only a start has a reason.
        assertNull(decoded[1].app_state?.start_reason)
    }

    @Test
    fun `connection event states keep their numbers on the wire`() {
        // The state is field 1 of ConnectionEvent: tag 0x08, then the enum number.
        assertContentEquals(byteArrayOf(0x08, 1), ConnectionEvent(state = ConnectionEvent.State.CONNECTED).encode())
        assertContentEquals(byteArrayOf(0x08, 2), ConnectionEvent(state = ConnectionEvent.State.DISCONNECTED).encode())
    }

    @Test
    fun `a charge record maps onto the sample its raw charge state maps onto`() {
        val sample = BleRecord(device_timestamp = ofEpochSecond(1_001, 0), charge_state = charging).toBatterySample(VEHICLE)
        assertEquals(charging.toBatterySample(VEHICLE)?.copy(readAtMillis = 1_001_000L), sample)
        assertEquals(77, sample?.batteryLevel)
        assertEquals(ChargingStateKind.Charging, sample?.chargingState)
    }

    @Test
    fun `a charge record keeps the car's timestamp as its timeline time`() {
        // The phone read the reply 90 s after the car stamped it: the sample
        // stays on the car's clock, and device_timestamp does not move it.
        val record = BleRecord(device_timestamp = ofEpochSecond(1_090, 0), charge_state = charging)
        assertEquals(1_000_000L, record.toBatterySample(VEHICLE)?.timestampMillis)
    }

    @Test
    fun `a charge sample carries the phone read time apart from the car's timestamp`() {
        // The car stamped the reply at 1_000 s; the phone read it at 1_090 s.
        val sample = BleRecord(device_timestamp = ofEpochSecond(1_090, 0), charge_state = charging).toBatterySample(VEHICLE)
        assertEquals(1_000_000L, sample?.timestampMillis)
        assertEquals(1_090_000L, sample?.readAtMillis)
    }

    @Test
    fun `a charge record maps without device_timestamp`() {
        // The charge timeline is the car's own clock, so the phone's time is not needed to place a sample.
        val sample = BleRecord(charge_state = charging).toBatterySample(VEHICLE)
        assertEquals(charging.toBatterySample(VEHICLE), sample)
        assertNull(sample?.readAtMillis)
    }

    @Test
    fun `a charge record without a car timestamp yields no battery sample`() {
        // device_timestamp is never a stand-in for the car's own time (ADR-0006).
        val at = ofEpochSecond(1_090, 0)
        assertNull(BleRecord(device_timestamp = at, charge_state = charging.copy(timestamp = null)).toBatterySample(VEHICLE))
        assertNull(BleRecord(device_timestamp = at, charge_state = charging.copy(battery_level = null)).toBatterySample(VEHICLE))
    }

    @Test
    fun `a status record maps onto a sample at the phone's acquisition time`() {
        val record = BleRecord(device_timestamp = ofEpochSecond(1, 500_000_000), vehicle_status = inUse)
        val expected =
            StatusSample(
                timestampMillis = 1_500L,
                vehicleId = VEHICLE,
                asleep = false,
                userPresent = true,
                locked = false,
            )
        assertEquals(expected, record.toStatusSample(VEHICLE))
    }

    @Test
    fun `a sleeping car maps onto asleep, locked and away`() {
        val sample = BleRecord(device_timestamp = ofEpochSecond(60, 0), vehicle_status = asleep).toStatusSample("car")
        assertEquals(StatusSample(60_000L, "car", asleep = true, userPresent = false, locked = true), sample)
    }

    @Test
    fun `a status sample needs a status and device_timestamp`() {
        assertNull(BleRecord(device_timestamp = ofEpochSecond(1, 0)).toStatusSample("car"))
        assertNull(BleRecord(vehicle_status = asleep).toStatusSample("car"))
    }

    @Test
    fun `a drive record maps onto a sample on the car's own clock`() {
        // The phone read the reply 90 s after the car stamped it: the sample
        // stays on the car's clock, and device_timestamp does not move it.
        val record = BleRecord(device_timestamp = ofEpochSecond(2_090, 0), drive_state = driving)
        val expected =
            DriveSample(
                timestampMillis = 2_000_000L,
                vehicleId = VEHICLE,
                shiftState = ShiftStateKind.D,
                speed = 42,
                power = -7,
                odometerInHundredthsOfAMile = 1_234_567,
            )
        assertEquals(expected, record.toDriveSample(VEHICLE))
    }

    @Test
    fun `a drive record maps without device_timestamp`() {
        // The drive timeline is the car's own clock, so the phone's time is not needed to place a sample.
        assertEquals(2_000_000L, BleRecord(drive_state = driving).toDriveSample(VEHICLE)?.timestampMillis)
    }

    @Test
    fun `a parked drive record keeps absent fields absent`() {
        val parked = DriveState(shift_state = ShiftState(P = Void()), timestamp = ofEpochSecond(5, 0))
        val sample = BleRecord(drive_state = parked).toDriveSample(VEHICLE)
        val expected =
            DriveSample(
                timestampMillis = 5_000L,
                vehicleId = VEHICLE,
                shiftState = ShiftStateKind.P,
                speed = null,
                power = null,
                odometerInHundredthsOfAMile = null,
            )
        assertEquals(expected, sample)
    }

    @Test
    fun `a drive record with an active route maps onto a route sample`() {
        assertEquals(
            RouteSample(
                timestampMillis = 2_000_000L,
                vehicleId = VEHICLE,
                destination = "Test destination",
                milesToArrival = 6.25f,
                minutesToArrival = 12.5f,
                trafficDelayMinutes = 1.5f,
                energyAtArrivalPercent = 61.5f,
                odometerInHundredthsOfAMile = 1_234_567,
            ),
            BleRecord(drive_state = driving).toRouteSample(VEHICLE),
        )
    }

    @Test
    fun `a route sample does not print its destination`() {
        val text = BleRecord(drive_state = driving).toRouteSample(VEHICLE).toString()
        assertEquals(false, text.contains("Test destination"))
        assertEquals(true, text.contains("<redacted>"))
    }

    @Test
    fun `a drive record without a route yields no route sample`() {
        val parked = DriveState(shift_state = ShiftState(P = Void()), timestamp = ofEpochSecond(5, 0))
        assertNull(BleRecord(drive_state = parked).toRouteSample(VEHICLE))
        assertNull(BleRecord(drive_state = driving.copy(timestamp = null)).toRouteSample(VEHICLE))
        assertNull(BleRecord().toRouteSample(VEHICLE))
    }

    @Test
    fun `a drive record without a car timestamp yields no drive sample`() {
        // device_timestamp is never a stand-in for the car's own time (ADR-0006).
        val record = BleRecord(device_timestamp = ofEpochSecond(2_090, 0), drive_state = driving.copy(timestamp = null))
        assertNull(record.toDriveSample(VEHICLE))
    }

    @Test
    fun `each payload feeds only its own sample`() {
        val deviceTimestamp = ofEpochSecond(1_001, 0)
        val charge = BleRecord(device_timestamp = deviceTimestamp, charge_state = charging)
        val status = BleRecord(device_timestamp = deviceTimestamp, vehicle_status = asleep)
        val drive = BleRecord(device_timestamp = deviceTimestamp, drive_state = driving)
        val connection = BleRecord(device_timestamp = deviceTimestamp, rssi = -70, connection_event = connected)
        assertNull(charge.toStatusSample("car"))
        assertNull(charge.toDriveSample("car"))
        assertNull(status.toBatterySample("car"))
        assertNull(status.toDriveSample("car"))
        assertNull(drive.toBatterySample("car"))
        assertNull(drive.toStatusSample("car"))
        // A connection event feeds no read model.
        assertNull(connection.toBatterySample("car"))
        assertNull(connection.toStatusSample("car"))
        assertNull(connection.toDriveSample("car"))
        val empty = BleRecord(device_timestamp = deviceTimestamp)
        assertNull(empty.toBatterySample("car"))
        assertNull(empty.toDriveSample("car"))
    }

    @Test
    fun `mixed records round-trip through the log codec with their payloads intact`() {
        val records =
            listOf(
                BleRecord(device_timestamp = ofEpochSecond(0, 0), vehicle_status = asleep),
                BleRecord(device_timestamp = ofEpochSecond(1_090, 0), charge_state = charging),
                BleRecord(device_timestamp = ofEpochSecond(1_800, 0), vehicle_status = inUse),
                BleRecord(device_timestamp = ofEpochSecond(2_090, 0), drive_state = driving),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        // The drive reply comes back whole: the route fields and the destination survive.
        assertEquals(driving, decoded.last().drive_state)
    }

    @Test
    fun `connection events and signal strength round-trip through the log codec`() {
        val records =
            listOf(
                BleRecord(device_timestamp = ofEpochSecond(10, 0), rssi = -72, connection_event = connected),
                BleRecord(device_timestamp = ofEpochSecond(11, 0), rssi = -58, vehicle_status = asleep),
                BleRecord(device_timestamp = ofEpochSecond(12, 0), rssi = -64, charge_state = charging),
                BleRecord(device_timestamp = ofEpochSecond(13, 0), rssi = -80, connection_event = disconnected),
                // The phone had no reading yet.
                BleRecord(device_timestamp = ofEpochSecond(14, 0), connection_event = connected),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        assertEquals(-72, decoded[0].rssi)
        assertEquals(ConnectionEvent.State.CONNECTED, decoded[0].connection_event?.state)
        assertEquals(ConnectionEvent.State.DISCONNECTED, decoded[3].connection_event?.state)
        assertNull(decoded[4].rssi)
    }

    @Test
    fun `closures and climate records round-trip through the log codec whole`() {
        val closures = ClosuresState(sentry_mode_state = ClosuresState.SentryModeState(Armed = Void()), locked = true)
        val climate =
            ClimateState(
                is_climate_on = true,
                climate_keeper_mode = ClimateState.ClimateKeeperMode(Dog = Void()),
                inside_temp_celsius = 21.5f,
                timestamp = ofEpochSecond(3_000, 0),
            )
        val records =
            listOf(
                BleRecord(device_timestamp = ofEpochSecond(3_001, 0), rssi = -61, closures_state = closures),
                BleRecord(device_timestamp = ofEpochSecond(3_002, 0), rssi = -62, climate_state = climate),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        assertEquals(closures, decoded[0].closures_state)
        assertEquals(climate, decoded[1].climate_state)
        // No read model yet: neither maps onto a sample.
        decoded.forEach {
            assertNull(it.toBatterySample("car"))
            assertNull(it.toStatusSample("car"))
            assertNull(it.toDriveSample("car"))
        }
    }

    @Test
    fun `a record without a signal strength reads it as absent`() {
        // A record from before rssi existed, or from a phone with no reading, has no field 5.
        val withoutSignal = BleRecord(device_timestamp = ofEpochSecond(5, 0), vehicle_status = asleep)
        val decoded = ProtoLog.decode(ProtoLog.encode(listOf(withoutSignal)), BleRecord.ADAPTER).single()
        assertNull(decoded.rssi)
        assertEquals(withoutSignal, decoded)
        // A reading of 0 dBm is a reading, not an absence.
        assertEquals(0, BleRecord.ADAPTER.decode(BleRecord(rssi = 0).encode()).rssi)
    }

    @Test
    fun `a payload kind from a newer version reads as a record without a known payload`() {
        // A newer writer's record: field 12, a payload kind added after app_state, beside device_timestamp.
        val fieldTwelve = byteArrayOf(0x62, 0x02, 0x08, 0x01)
        val record = BleRecord.ADAPTER.decode(BleRecord(device_timestamp = ofEpochSecond(5, 0)).encode() + fieldTwelve)
        assertEquals(ofEpochSecond(5, 0), record.device_timestamp)
        assertNull(record.toBatterySample("car"))
        assertNull(record.toStatusSample("car"))
        assertNull(record.toDriveSample("car"))
    }

    private companion object {
        const val VEHICLE = "Se1f0941734830fe7C"
    }
}
