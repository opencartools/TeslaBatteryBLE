// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.DemoMode
import com.dzid26.teslable.ble.LogEntry
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.PairingPhase
import com.dzid26.teslable.protocol.ShiftStateKind
import com.dzid26.teslable.protocol.TeslaNames
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.math.roundToInt

/**
 * The simulated car as the full-screen snapshots show it, so the release
 * screenshots read like the old emulator set: "Demo Tesla" charging at 78%
 * next to a second, unpaired car, with two days of seeded history. The shape
 * of the history mirrors the debug source set's `DemoHistory` (the same
 * charge/drive timeline, 3 rated miles and 0.75 kWh per percent, an 85%
 * limit), so the chart, projections and battery-health card come out the same.
 */
internal object DemoScreenFixtures {
    // Constant on purpose: BuildConfig.VERSION_NAME names the commit and would change every run. Bump at release.
    const val VERSION_NAME = "0.5.0"
    const val DEMO_ADDRESS = DemoMode.DEMO_ADDRESS
    const val OTHER_ADDRESS = DemoMode.OTHER_ADDRESS
    const val OTHER_NAME = "S1a2b3c4d5e6f7080C"
    const val DEMO_TITLE = "Demo Tesla"
    const val KEY_SLOT = 0

    private const val MINUTE = 60_000L
    private const val LIVE_PERCENT = 78
    private const val LIVE_TOP_UP_START_MINUTES = 90L
    private const val CHARGE_LIMIT = 85
    private const val RATED_MILES_PER_PERCENT = 3.0f
    private const val KWH_PER_PERCENT = 0.75f
    private const val ODOMETER = 1_234_567
    private const val DRIVE_STEP = 1_000
    private const val DEMO_RSSI_LIST = -48
    private const val DEMO_RSSI_CAR = -47
    private const val OTHER_RSSI = -78

    val bleName: String = TeslaNames.bleName(DemoMode.DEMO_VIN)

    val vehicle =
        Vehicle(
            bleName = bleName,
            address = DEMO_ADDRESS,
            gattName = DEMO_TITLE,
            vin = DemoMode.DEMO_VIN,
            keySlot = KEY_SLOT,
        )

    val carAdvert = TeslaAdvert(name = bleName, address = DEMO_ADDRESS, rssi = DEMO_RSSI_CAR)

    private val listAdvert = carAdvert.copy(rssi = DEMO_RSSI_LIST)
    private val otherAdvert = TeslaAdvert(name = OTHER_NAME, address = OTHER_ADDRESS, rssi = OTHER_RSSI)

    private val charging =
        ChargeState(
            charging_state = ChargeState.ChargingState(Charging = Void()),
            charge_limit_soc = CHARGE_LIMIT,
            battery_range = LIVE_PERCENT * RATED_MILES_PER_PERCENT,
            battery_level = LIVE_PERCENT,
            usable_battery_level = LIVE_PERCENT,
        )

    private val driving =
        ChargeState(
            charging_state = ChargeState.ChargingState(Disconnected = Void()),
            charge_limit_soc = CHARGE_LIMIT,
            battery_range = LIVE_PERCENT * RATED_MILES_PER_PERCENT,
            battery_level = LIVE_PERCENT,
            usable_battery_level = LIVE_PERCENT,
        )

    private val awakeLocked =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        )

    private val atTheWheel =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
        )

    private fun connection(
        nowMillis: Long,
        pairing: PairingPhase,
        charge: ChargeState = charging,
        status: VehicleStatus = awakeLocked,
    ) = TeslaConnection(
        address = DEMO_ADDRESS,
        name = bleName,
        phase = ConnectionPhase.READY,
        status = status,
        keySlot = KEY_SLOT,
        charge = charge,
        chargeAtMillis = nowMillis - MINUTE,
        pairing = pairing,
    )

    private val log =
        listOf(
            "wake requested",
            "wake OPERATIONSTATUS_OK",
            "VCSEC status locked=true asleep=false userPresent=false",
            "requesting Infotainment session for SOC",
            "session request DOMAIN_INFOTAINMENT",
            "session established (DOMAIN_INFOTAINMENT)",
            "SOC 78% (Charging)",
            "shift state P",
            "sentry off",
            "climate off",
        ).map { LogEntry(vehicleId = bleName, message = "$DEMO_TITLE: $it") }

    /** The cars list: the demo car connected, an unknown second car in range, a scan running. */
    fun listState(nowMillis: Long) =
        BleUiState(
            scanning = true,
            trackingEnabled = true,
            devices = listOf(listAdvert, otherAdvert),
            connections = mapOf(DEMO_ADDRESS to connection(nowMillis, PairingPhase.IDLE)),
            vehicles = listOf(vehicle),
            log = log,
        )

    /** The car screen: the key was just paired, so the "Rename the Phone Key" hint shows. */
    fun carState(nowMillis: Long) =
        listState(nowMillis).copy(
            connections = mapOf(DEMO_ADDRESS to connection(nowMillis, PairingPhase.OK)),
            selectedBleName = bleName,
        )

    /** The car screen while the car drives: not charging, so the trip card is not next to a "Charging to" line. */
    fun drivingState(nowMillis: Long) =
        carState(nowMillis).copy(
            connections = mapOf(DEMO_ADDRESS to connection(nowMillis, PairingPhase.OK, driving, atTheWheel)),
        )

    /** [history] without the live top-up, which began 90 minutes ago. */
    fun drivingHistory(nowMillis: Long): List<BatterySample> =
        history(nowMillis).filter {
            it.timestampMillis <
                nowMillis - LIVE_TOP_UP_START_MINUTES * MINUTE
        }

    private data class Point(
        val minutesAgo: Long,
        val percent: Int,
        val state: ChargingStateKind,
        val chargeStartPercent: Int? = null,
        val driving: Boolean = false,
    )

    /** Points from `minutes.first` to `.second` minutes ago, the level moving from `levels.first` to `.second`. */
    private fun segment(
        minutes: Pair<Long, Long>,
        levels: Pair<Int, Int>,
        stepMin: Long,
        state: ChargingStateKind,
        driving: Boolean = false,
    ): List<Point> {
        val (fromAgo, toAgo) = minutes
        val (fromPercent, toPercent) = levels
        val span = (fromAgo - toAgo).coerceAtLeast(1L)
        val charge = if (state == ChargingStateKind.Charging) fromPercent else null
        val points = mutableListOf<Point>()
        var ago = fromAgo
        while (ago >= toAgo) {
            val fraction = (fromAgo - ago).toDouble() / span
            val percent = (fromPercent + (toPercent - fromPercent) * fraction).roundToInt()
            points += Point(ago, percent, state, charge, driving)
            ago -= stepMin
        }
        if (points.last().minutesAgo != toAgo) points += Point(toAgo, toPercent, state, charge, driving)
        return points
    }

    private val timeline: List<Point> =
        buildList {
            val parked = ChargingStateKind.Disconnected
            val charge = ChargingStateKind.Charging
            addAll(segment(2880L to 2700L, 64 to 63, 60, parked))
            addAll(segment(2700L to 2580L, 63 to 55, 15, parked, driving = true))
            addAll(segment(2580L to 2460L, 55 to 54, 60, parked))
            addAll(segment(2460L to 2400L, 54 to 60, 10, charge))
            addAll(segment(2400L to 2280L, 60 to 52, 15, parked, driving = true))
            addAll(segment(2280L to 2160L, 52 to 51, 60, parked))
            addAll(segment(2160L to 2040L, 51 to 63, 10, charge))
            addAll(segment(2040L to 1920L, 63 to 54, 15, parked, driving = true))
            addAll(segment(1920L to 1560L, 54 to 52, 60, parked))
            addAll(segment(1560L to 1260L, 52 to 85, 10, charge))
            add(Point(1260, 85, ChargingStateKind.Complete))
            addAll(segment(1200L to 90L, 84 to 75, 60, parked))
            addAll(segment(90L to 10L, 75 to 78, 10, charge))
        }.reversed().distinctBy { it.minutesAgo }.reversed()

    /** Two days of charge readings ending at [nowMillis]: three sessions plus the live top-up. */
    fun history(nowMillis: Long): List<BatterySample> =
        timeline.map { point ->
            val added = point.chargeStartPercent?.let { point.percent - it }
            BatterySample(
                timestampMillis = nowMillis - point.minutesAgo * MINUTE,
                batteryLevel = point.percent,
                chargingState = point.state,
                chargeLimit = CHARGE_LIMIT,
                vehicleId = bleName,
                ratedRangeMiles = point.percent * RATED_MILES_PER_PERCENT,
                chargeEnergyAdded = added?.let { it * KWH_PER_PERCENT } ?: 0f,
                chargeMilesAddedRated = added?.let { it * RATED_MILES_PER_PERCENT } ?: 0f,
                readAtMillis = nowMillis - point.minutesAgo * MINUTE,
            )
        }

    /** One drive reading per charge reading; the odometer moves only across the drives. */
    fun drives(nowMillis: Long): List<DriveSample> {
        val moved = timeline.mapIndexed { i, p -> i > 0 && (p.driving || timeline[i - 1].driving) }
        var odometer = ODOMETER - moved.count { it } * DRIVE_STEP
        return timeline.mapIndexed { i, point ->
            if (moved[i]) odometer += DRIVE_STEP
            DriveSample(
                timestampMillis = nowMillis - point.minutesAgo * MINUTE,
                vehicleId = bleName,
                shiftState = if (point.driving) ShiftStateKind.D else ShiftStateKind.P,
                speed = if (point.driving) DRIVE_SPEED else 0,
                power = 0,
                odometerInHundredthsOfAMile = odometer,
            )
        }
    }

    private const val DRIVE_SPEED = 40
}
