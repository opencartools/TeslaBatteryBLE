// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.StatusDurations
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.PairingPhase
import com.dzid26.teslable.protocol.asleep
import com.dzid26.teslable.protocol.locked
import com.dzid26.teslable.protocol.userPresent
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.VehicleStatus
import java.util.UUID

data class TeslaAdvert(
    val name: String,
    val address: String,
    val rssi: Int? = null,
)

data class TeslaConnection(
    val address: String,
    val name: String,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val gattDeviceName: String? = null,
    val services: List<GattServiceInfo> = emptyList(),
    val mtu: Int? = null,
    val rssi: Int? = null,
    val status: VehicleStatus? = null,
    val keySlot: Int? = null,
    val sessions: List<String> = emptyList(),
    val charge: ChargeState? = null,
    /** When [charge] was last read; null until the first charge response. */
    val chargeAtMillis: Long? = null,
    /** When the link last dropped; null while READY, so a disconnected car can show how long it has been. */
    val disconnectedAtMillis: Long? = null,
    /** How far the Infotainment read in flight has come; null when none is. The VCSEC status poll does not count. */
    val readProgress: ReadProgress? = null,
    /**
     * When the idle poll reads the car next, while it waits out the safety interval; null while reads
     * run every ten seconds (active, holding, asleep, or no read yet). The status pill shows Paused
     * while it is set (ADR-0009).
     */
    val pausedUntilMillis: Long? = null,
    /** Pairing flow state for this car; idle unless a Pair is in flight. */
    val pairing: PairingPhase = PairingPhase.IDLE,
    val pairingKeyId: String? = null,
) {
    /** True when the app key is enrolled (or just was), so the car can authenticate this link. */
    val isPaired: Boolean get() = keySlot != null || pairing == PairingPhase.OK

    /** An Infotainment read is in flight. */
    val readInFlight: Boolean get() = readProgress != null
}

data class GattServiceInfo(
    val uuid: UUID,
    val characteristicUuids: List<UUID>,
)

enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCOVERING,
    READY,
    FAILED,
    DISCONNECTED,
}

data class BleUiState(
    val scanning: Boolean = false,
    val discovering: Boolean = false,
    val explicitScan: Boolean = false,
    val trackingEnabled: Boolean = true,
    val devices: List<TeslaAdvert> = emptyList(),
    val connections: Map<String, TeslaConnection> = emptyMap(),
    /** Known cars, most recently seen first. */
    val vehicles: List<Vehicle> = emptyList(),
    /** The car the UI is focused on, by advertised name (stable across address changes). */
    val selectedBleName: String? = null,
    /** Set when a notification tap asks the UI to open a specific car. */
    val openVehicleRequest: String? = null,
    val log: List<LogEntry> = emptyList(),
    /** The phone's Bluetooth switch; while off no link can come up, so cars read "Bluetooth off". */
    val bluetoothOn: Boolean = true,
    /** A scan is needed but location access is missing; the cars list asks for it. */
    val locationNeeded: Boolean = false,
    /**
     * A VIN prompt waiting for the owner, for the car that asked for one. The Compose layer renders it
     * as a blocking dialog; saving the VIN clears it and replays [VinRequest.replay] once.
     */
    val vinRequest: VinRequest? = null,
    /** The last quick action's result, shown once as a snackbar and then consumed. */
    val actionFeedback: QuickActionFeedback? = null,
)

/** One control on the car screen's quick-action stack. */
enum class QuickAction {
    CHARGE_PORT,
    FLASH_LIGHTS,
    WAKE,
}

/** How a [QuickAction] ended, for the snackbar that reports it. */
enum class QuickActionOutcome {
    /** The request went out; the car has not answered yet. */
    SENT,

    /** The car was asleep, so it was woken and the action goes out once the session is ready. */
    WAKING,

    /** Nothing could be sent: no link, no key, no VIN, or the handshake gave up. */
    FAILED,
}

data class QuickActionFeedback(
    val action: QuickAction,
    val outcome: QuickActionOutcome,
)

/** One log line, tagged with the car it came from (null for app-wide lines) and when it was written. */
data class LogEntry(
    val vehicleId: String?,
    val message: String,
    val timeMillis: Long = 0L,
)

/** The live connection for the selected car, if it has one. */
fun BleUiState.selectedConnection(): TeslaConnection? {
    val address =
        vehicles.firstOrNull { it.bleName == selectedBleName }?.address
            ?: devices.firstOrNull { it.name == selectedBleName }?.address
            ?: return null
    return connections[address]
}

/**
 * True when an enrolled car is connected or on its way there, so the tracking
 * service should run: a READY link with an enrolled key, or a link to a car the
 * app already holds a key for that is still connecting. The notification then
 * shows from the first connect attempt, not only after the first read.
 * A pure function of the UI state so Compose can observe it directly.
 */
fun BleUiState.shouldTrack(): Boolean =
    trackingEnabled &&
        connections.values.any { connection ->
            when (connection.phase) {
                ConnectionPhase.READY -> connection.isPaired || connection.sessions.isNotEmpty()
                ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED, ConnectionPhase.DISCOVERING ->
                    vehicles.any { it.address == connection.address && it.isPaired }
                else -> false
            }
        }

/**
 * How recent a live reading must be to count as fresh (blue), while the link is
 * READY and the car is not asleep. An awake, idle car's charge does not move, and
 * charging or driving keeps reads coming every few seconds, so a reading stays
 * fresh for this long after it was read, and goes stale at once when the link
 * drops or the car's latest VCSEC status says asleep, whichever comes first. The
 * stored fallback is always stale.
 */
const val FRESH_READING_MS = 5 * 60_000L

/**
 * How often a screen or the notification re-checks reading ages when no
 * reading is about to turn stale, so ages ("12m ago") stay current on a quiet screen.
 */
const val STALENESS_TICK_MS = 60_000L

/**
 * How long to wait before the clock is read again: until the newest reading
 * turns stale while it is still fresh (so it greys right at [FRESH_READING_MS],
 * not at the next minute), otherwise [STALENESS_TICK_MS].
 */
fun stalenessTickDelayMs(
    readAtMillis: Long?,
    nowMillis: Long,
): Long {
    if (readAtMillis == null) return STALENESS_TICK_MS
    val untilStale = readAtMillis + FRESH_READING_MS - nowMillis
    return if (untilStale > 0) minOf(untilStale + 1, STALENESS_TICK_MS) else STALENESS_TICK_MS
}

/** A read spinner, once shown, stays visible at least this long so a sub-second read does not flicker. */
const val READ_SPINNER_MIN_VISIBLE_MS = 600L

/** How much longer a spinner shown at [shownAtMillis] must stay visible at [nowMillis]; 0 once the minimum has passed. */
fun spinnerHoldMs(
    shownAtMillis: Long,
    nowMillis: Long,
): Long = (READ_SPINNER_MIN_VISIBLE_MS - (nowMillis - shownAtMillis)).coerceIn(0, READ_SPINNER_MIN_VISIBLE_MS)

/** A battery percentage with its freshness: live, or the last stored sample. */
data class BatteryPercent(
    val value: Int,
    val stale: Boolean,
    /** The phone's clock when the reading was read, when known; never the car's clock. */
    val readAtMillis: Long?,
)

/**
 * The age shown in the status card's top-right corner for a stale percentage
 * ("12m ago"); null for a fresh reading (still being read) and for one whose
 * read time is unknown. When the car is not connected ([connection] null or not
 * READY) an age under a minute reads "<1m ago", never "now", which would look
 * like a live reading; "now" stays for a connected car whose reading greyed
 * because it fell asleep.
 */
fun BatteryPercent.ageLabel(
    nowMillis: Long,
    connection: TeslaConnection?,
): String? {
    val readAt = readAtMillis?.takeIf { stale } ?: return null
    val age = readingAgeText(nowMillis - readAt)
    val connected = connection?.phase == ConnectionPhase.READY
    return if (!connected && age == READING_NOW_TEXT) READING_UNDER_A_MINUTE_TEXT else age
}

private const val READING_NOW_TEXT = "now"
private const val READING_UNDER_A_MINUTE_TEXT = "<1m\u00A0ago"

/**
 * The percentage to show for a car: the live charge when there is one,
 * otherwise the newest stored sample. [BatteryPercent.stale] marks a live
 * reading that is [FRESH_READING_MS] old or older, whose connection is not READY,
 * or whose car's latest status says asleep, and every stored fallback.
 */
fun batteryPercent(
    connection: TeslaConnection?,
    lastKnown: BatterySample?,
    nowMillis: Long,
): BatteryPercent? {
    val live = connection?.charge?.battery_level
    if (live != null) {
        val readAt = connection.chargeAtMillis
        return BatteryPercent(
            value = live,
            stale =
                readAt == null ||
                    nowMillis - readAt >= FRESH_READING_MS ||
                    connection.phase != ConnectionPhase.READY ||
                    connection.status?.asleep == true,
            readAtMillis = readAt,
        )
    }
    return lastKnown?.let {
        // The age is the phone's read time; the sample's own timestamp is the car's clock.
        BatteryPercent(value = it.batteryLevel, stale = true, readAtMillis = it.readAtMillis)
    }
}

/**
 * How long ago a reading was taken, worded the same on the notification and the
 * car view: "now" under a minute, then whole minutes ("7m ago") up to an hour,
 * then whole hours ("2h ago") and days ("3d ago"). It does not depend on
 * freshness: a reading greyed by sleep 3 minutes after it was read says "3m ago".
 */
fun readingAgeText(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    return when {
        minutes < 1 -> READING_NOW_TEXT
        minutes < 60 -> "${minutes}m\u00A0ago"
        minutes < 24 * 60 -> "${minutes / 60}h\u00A0ago"
        else -> "${minutes / (24 * 60)}d\u00A0ago"
    }
}

/** The title and status line both the app list and the notification show for a car. */
data class ConnectionDisplay(
    val title: String,
    val stateText: String,
    val rssi: Int?,
) {
    val status: String get() = rssi?.let { "$stateText · RSSI\u00A0${it}dBm" } ?: stateText
}

/**
 * Builds the display for a car, shared by the app list and the tracking
 * notification so the two surfaces cannot drift apart.
 */
fun connectionDisplay(
    connection: TeslaConnection?,
    advert: TeslaAdvert?,
    vehicle: Vehicle? = null,
    durations: StatusDurations? = null,
    bluetoothOn: Boolean = true,
): ConnectionDisplay {
    val title =
        vehicle?.displayName?.takeIf { it.isNotBlank() }
            ?: connection?.gattDeviceName
            ?: vehicle?.gattName
            ?: connection?.name
            ?: advert?.name
            ?: "Tesla"
    return ConnectionDisplay(
        title = title,
        stateText = connectionStateText(connection, durations, bluetoothOn),
        // Live RSSI while connected; otherwise the scan advert's, so unpaired
        // cars show signal strength too.
        rssi = if (connection?.phase == ConnectionPhase.READY) connection.rssi ?: advert?.rssi else advert?.rssi,
    )
}

/**
 * The connection states: disconnected, connected while asleep, or connected.
 * The battery percentage lives in the readout, never in the status line.
 */
private fun connectionStateText(
    connection: TeslaConnection?,
    durations: StatusDurations?,
    bluetoothOn: Boolean,
): String =
    when (connectionLabel(connection, bluetoothOn)) {
        ConnectionLabel.BLUETOOTH_OFF -> "Bluetooth off"

        ConnectionLabel.DISCONNECTED, ConnectionLabel.FAILED -> "Disconnected"

        ConnectionLabel.CONNECTING -> "Connecting..."

        ConnectionLabel.CONNECTED, ConnectionLabel.AWAKE, ConnectionLabel.PAUSED -> "Connected"

        ConnectionLabel.ASLEEP ->
            "Connected \uD83D\uDCA4" +
                asleepForMillis(connection?.status, durations)?.let(::compactDuration)?.let { " ·\u00A0$it" }.orEmpty()
    }

/**
 * What a car's connection amounts to for the owner. The status pill, the status line and its color all
 * read it, so they cannot disagree; every phase is listed, so a new one does not build until it has a label.
 */
enum class ConnectionLabel(
    /** The status pill's word. */
    val pillText: String,
) {
    DISCONNECTED("Disconnected"),
    FAILED("Failed"),

    /** The phone's Bluetooth is off, so the car cannot be reached whatever the last phase was. */
    BLUETOOTH_OFF("Bluetooth off"),

    /** Connecting, connected or discovering services: the link is not usable yet. */
    CONNECTING("Connecting"),

    /** Ready, before the car's first VCSEC status says awake or asleep. */
    CONNECTED("Connected"),
    AWAKE("Awake"),
    ASLEEP("Asleep"),

    /** Awake, but the poll is waiting out the idle safety interval before its next read. */
    PAUSED("Paused"),
}

fun connectionLabel(
    connection: TeslaConnection?,
    bluetoothOn: Boolean = true,
): ConnectionLabel =
    if (!bluetoothOn) {
        ConnectionLabel.BLUETOOTH_OFF
    } else {
        phaseLabel(connection)
    }

private fun phaseLabel(connection: TeslaConnection?): ConnectionLabel =
    when (connection?.phase) {
        null, ConnectionPhase.IDLE, ConnectionPhase.DISCONNECTED -> ConnectionLabel.DISCONNECTED

        ConnectionPhase.FAILED -> ConnectionLabel.FAILED

        ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED, ConnectionPhase.DISCOVERING -> ConnectionLabel.CONNECTING

        ConnectionPhase.READY ->
            when {
                connection.status?.asleep == true -> ConnectionLabel.ASLEEP
                connection.pausedUntilMillis != null -> ConnectionLabel.PAUSED
                connection.status != null -> ConnectionLabel.AWAKE
                else -> ConnectionLabel.CONNECTED
            }
    }

/**
 * "Asleep 1h 12m · Locked 3h · User away" instead of raw booleans. Asleep and
 * locked show how long they have held when [durations] knows (and still matches
 * the live [status]); user presence never shows one.
 */
fun vehicleStatusText(
    status: VehicleStatus,
    durations: StatusDurations? = null,
): String {
    val asleepFor = asleepForMillis(status, durations)
    val lockedFor = durations?.takeIf { it.locked == status.locked }?.lockedForMillis
    return listOf(
        withDuration(if (status.asleep) "Asleep" else "Awake", asleepFor),
        withDuration(if (status.locked) "Locked" else "Unlocked", lockedFor),
        if (status.userPresent) "User\u00A0present" else "User\u00A0away",
    ).joinToString(" · ")
}

/** How long the car has been asleep, when [status] says asleep and [durations] knows. */
fun asleepForMillis(
    status: VehicleStatus?,
    durations: StatusDurations?,
): Long? = durations?.takeIf { status != null && it.asleep == status.asleep }?.asleepForMillis

private fun withDuration(
    label: String,
    millis: Long?,
): String = millis?.let(::compactDuration)?.let { "$label\u00A0$it" } ?: label

/** Friendly names for the per-domain secure sessions. */
fun sessionNames(sessions: List<String>): String =
    sessions.joinToString(", ") { session ->
        when {
            session.contains("VEHICLE_SECURITY") -> "Security"
            session.contains("INFOTAINMENT") -> "Infotainment"
            session.contains("BROADCAST") -> "Broadcast"
            else -> session
        }
    }

/** Friendly charging state for the charge details line. */
fun chargingStateText(state: ChargingStateKind): String =
    when (state) {
        ChargingStateKind.Charging -> "Charging"
        ChargingStateKind.Complete -> "Charging complete"
        ChargingStateKind.Stopped -> "Charging stopped"
        ChargingStateKind.Disconnected -> "Unplugged"
        ChargingStateKind.NoPower -> "No power"
        ChargingStateKind.Starting -> "Starting"
        ChargingStateKind.Calibrating -> "Calibrating"
        ChargingStateKind.Unknown -> "Unknown"
    }
