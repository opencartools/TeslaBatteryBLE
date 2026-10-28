// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.history.ActivitySample
import com.dzid26.teslable.core.history.ActivityThinner
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.CommandResult
import com.dzid26.teslable.core.history.ConnectionEvent
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.DriveSession
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.history.Trip
import com.dzid26.teslable.core.history.batteryThinner
import com.dzid26.teslable.core.history.continuing
import com.dzid26.teslable.core.history.driveThinner
import com.dzid26.teslable.core.history.segmentTrips
import com.dzid26.teslable.core.history.shouldLogStatus
import com.dzid26.teslable.core.history.toActivitySample
import com.dzid26.teslable.core.history.toBatterySample
import com.dzid26.teslable.core.history.toDriveSample
import com.dzid26.teslable.core.history.toRouteSample
import com.dzid26.teslable.core.history.toStatusSample
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.LocationState
import com.tesla.generated.carserver.vehicle.TirePressureState
import com.tesla.generated.vcsec.VehicleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Instant

/**
 * BLE history as append-only logs of the replies the phone acquired: each is
 * the car's raw response (ADR-0006) wrapped in a [BleRecord] next to the
 * phone's acquisition time (ADR-0008), so new car fields never drop old rows.
 * The records live in Room's `history.db` (ADR-0014), one row per record keyed by vehicle and
 * [RecordKind], and the read models are cached in memory and exposed as [StateFlow]s:
 * - [RecordKind.VCSEC]: VCSEC status readings logged when [shouldLogStatus] says so, timed by
 *   `device_timestamp` ([statusSamples]);
 * - [RecordKind.CHARGE]: every charge reply, raw; the chart uses the ones with the car's own
 *   `ChargeState.timestamp` and a level ([samples]);
 * - [RecordKind.DRIVE]: every DriveState reply, raw; [driveSamples] holds the ones with the car's own
 *   `DriveState.timestamp`;
 * - [RecordKind.CLOSURES]: every ClosuresState reply (it holds the sentry mode state), raw; log only,
 *   nothing reads it back yet;
 * - [RecordKind.CLIMATE]: every ClimateState reply, raw; log only;
 * - [RecordKind.LOCATION]: every LocationState reply (read while driving), raw; log only;
 * - [RecordKind.TIRE_PRESSURE]: every TirePressureState reply (read every 10 minutes), raw; log only;
 * - [RecordKind.CONNECTION]: when the connection to the car became ready or an established one ended.
 *   Log only: nothing reads it back yet;
 * - [RecordKind.COMMAND]: the requests the app sent to the car, raw and with the reason, and the car's
 *   refusals of them (not the routine VCSEC status poll). Log only;
 * - [RecordKind.APP]: the app's own state (screen, foreground, tracking service), for the whole app.
 *   Log only.
 *
 * Every record about a car also carries the phone's latest RSSI for it, when it
 * had one (`rssi`).
 *
 * The `.pblog` files of earlier versions in `filesDir/battery-history/` are imported once and
 * renamed; [exportLogs] writes the records back out as such files in a zip. Older raw
 * `<vehicleId>.pblog` charge files and the pre-store CSV history are neither read nor written
 * (pre-1.0 reset).
 */
class HistoryStore internal constructor(
    private val historyDir: File,
    private val recordStoreProvider: Lazy<RecordStore>,
    private val scope: CoroutineScope,
    private val derivedProvider: Lazy<DerivedDatabase?> = lazy { null },
) {
    constructor(context: Context) : this(
        File(context.filesDir, HISTORY_DIR_NAME),
        lazy { RoomBackedRecordStore(HistoryDatabase.open(context), EventsDatabase.open(context), OriginId.load(context)) },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        lazy { DerivedDatabase.open(context) },
    )

    private val mutex = Mutex()

    /** Opened on first use, which is always off the main thread: the open touches the database file. */
    private val recordStore: RecordStore get() = recordStoreProvider.value

    // Each read model is its full-resolution recent window plus the thinned long-term history before it (ADR-0014).
    private val battery = TieredList<BatterySample>(MAX_SAMPLES, { it.timestampMillis }, batteryThinner()::accept)
    private val drives = TieredList<DriveSample>(MAX_SAMPLES, { it.timestampMillis }, driveThinner()::accept)
    private val statuses = TieredList<StatusSample>(MAX_SAMPLES, { it.timestampMillis })
    private val activity = TieredList<ActivitySample>(MAX_ACTIVITY_SAMPLES, { it.timestampMillis })

    /** Builds `derived.db` from the raw records; null where there is none (the tests of the raw store). */
    private val derivedBuilder: DerivedBuilder? by lazy { derivedProvider.value?.let { DerivedBuilder(recordStore, it) } }
    private val sessionBuilder: SessionBuilder? by lazy { derivedProvider.value?.let { SessionBuilder(it) } }

    /** One build at a time: the startup build and the periodic catch-ups never overlap. */
    private val buildMutex = Mutex()
    private var appendsSinceBuild = 0

    private val _samples = MutableStateFlow<List<BatterySample>>(emptyList())
    val samples: StateFlow<List<BatterySample>> = _samples.asStateFlow()
    private val _statusSamples = MutableStateFlow<List<StatusSample>>(emptyList())

    /** VCSEC status readings logged for every car, oldest first. */
    val statusSamples: StateFlow<List<StatusSample>> = _statusSamples.asStateFlow()
    private val _activitySamples = MutableStateFlow<List<ActivitySample>>(emptyList())

    /** Sentry and climate readings logged for every car, oldest first. */
    val activitySamples: StateFlow<List<ActivitySample>> = _activitySamples.asStateFlow()
    private val _driveSamples = MutableStateFlow<List<DriveSample>>(emptyList())

    /** DriveState readings logged for every car, oldest first. */
    val driveSamples: StateFlow<List<DriveSample>> = _driveSamples.asStateFlow()

    private val _latestRoutes = MutableStateFlow<Map<String, RouteSample>>(emptyMap())

    /**
     * The newest drive reading per car that carried route fields, for the live trip card (#207).
     * Held in memory only: the destination name stays out of every read model but this one, and
     * the raw reply in the drive log is the only place it is kept.
     */
    val latestRoutes: StateFlow<Map<String, RouteSample>> = _latestRoutes.asStateFlow()

    private val routeReadings = mutableMapOf<String, List<RouteSample>>()
    private val _trips = MutableStateFlow<Map<String, List<Trip>>>(emptyMap())

    /**
     * The finished navigations per car, oldest first, for the past-trips card. Segmented from
     * the route readings of the drive log and kept in memory only, like [latestRoutes]: the
     * destination names stay out of every other read model.
     */
    val trips: StateFlow<Map<String, List<Trip>>> = _trips.asStateFlow()

    private val _chargeSessions = MutableStateFlow<List<ChargeSession>>(emptyList())

    /**
     * Every car's charge sessions as `derived.db` stores them, oldest first, as of its last build (at start and every
     * [BUILD_EVERY] appends). The newest one may still be growing: join it with the readings since through
     * `withLiveTail`.
     */
    val chargeSessions: StateFlow<List<ChargeSession>> = _chargeSessions.asStateFlow()
    private val _driveSessions = MutableStateFlow<List<DriveSession>>(emptyList())

    /** Every car's drives as `derived.db` stores them, oldest first; see [chargeSessions]. */
    val driveSessions: StateFlow<List<DriveSession>> = _driveSessions.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /**
     * False while the start is still publishing the logs (the recent windows, then the long-term history after the
     * `derived.db` build); true once every log holds what it will hold, so a reader can tell a half-loaded screen
     * from a quiet one.
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Thins the Sentry and climate readings as they load and arrive. */
    private val activityThinner = ActivityThinner()

    /** The newest status record logged per vehicle, which [shouldLogStatus] compares the next reading against. */
    private val lastLoggedStatus = mutableMapOf<String, BleRecord>()

    init {
        scope.launch {
            mutex.withLock {
                runCatching { PblogImporter(historyDir, recordStore).importAll() }
                battery.setRecent(readSamples())
                _samples.value = battery.all
                statuses.setRecent(readStatusSamples())
                _statusSamples.value = statuses.all
                val (driveSamples, latestRoutes) = readDrive()
                drives.setRecent(driveSamples)
                _driveSamples.value = drives.all
                _latestRoutes.value = latestRoutes
                _trips.value = routeReadings.mapValues { (_, readings) -> segmentTrips(readings).takeLast(MAX_TRIPS) }
                activity.setRecent(readActivitySamples())
                _activitySamples.value = activity.all
            }
            // The recent windows are on screen; the long-term history follows once derived.db is up to date.
            // Retention runs only after that build succeeded, so a record the cap drops has already reached
            // derived.db; after a failed build this start skips it, and the next start tries again.
            val built = buildDerived()
            mutex.withLock {
                if (built) (recordStore as? RoomBackedRecordStore)?.let { runCatching { it.trim() } }
                loadOlder()
                _loaded.value = true
            }
        }
    }

    /**
     * Brings `derived.db` up to date with the raw records; a failure leaves the long-term history as it was. True
     * when every raw record has reached it, or when there is no `derived.db` to reach (the tests of the raw store).
     */
    private suspend fun buildDerived(): Boolean =
        buildMutex.withLock {
            runCatching {
                derivedBuilder?.build()
                // The sessions follow the samples; a failure there leaves the samples built and the marks in place.
                runCatching { sessionBuilder?.build() }
                loadSessions()
            }.isSuccess
        }

    /** Publishes the stored sessions. */
    private fun loadSessions() {
        val dao = derivedProvider.value?.sessions() ?: return
        runCatching {
            _chargeSessions.value = dao.charges().map { it.toSession() }
            _driveSessions.value = dao.drives().map { it.toSession() }
        }
    }

    /** Counts appended records and catches `derived.db` up every [BUILD_EVERY], so the next start has little to read. */
    private fun countAppend() {
        if (++appendsSinceBuild < BUILD_EVERY) return
        appendsSinceBuild = 0
        scope.launch { buildDerived() }
    }

    /** Puts the thinned history from before each recent window under its read model. */
    private fun loadOlder() {
        val dao = derivedProvider.value?.reads() ?: return
        runCatching {
            battery.setOlder(dao.batteryBefore(battery.olderBefore).map { it.toSample() })
            drives.setOlder(dao.driveBefore(drives.olderBefore).map { it.toSample() })
            statuses.setOlder(dao.statusBefore(statuses.olderBefore).map { it.toSample() })
            activity.setOlder(dao.activityBefore(activity.olderBefore).mapNotNull { it.toSample() })
        }
        _samples.value = battery.all
        _driveSamples.value = drives.all
        _statusSamples.value = statuses.all
        _activitySamples.value = activity.all
    }

    /**
     * Logs one charge reply. [deviceTimestamp] is the phone's clock when it
     * arrived; it goes only into the record's own `device_timestamp`, so the sample
     * stays on the car's `ChargeState.timestamp`. [rssi] is the phone's
     * latest RSSI for the car, or null when it has none.
     */
    fun record(
        vehicleId: String,
        charge: ChargeState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        // Every reply is logged as the car sent it. Chart time comes only from
        // the car's own timestamp and is never filled in, so a reply without
        // one (or without a level) stays in the log but off the chart.
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, charge_state = charge)
        val sample = record.toBatterySample(vehicleId)
        scope.launch {
            mutex.withLock {
                recordStore.append(vehicleId, RecordKind.CHARGE, record)
                countAppend()
                if (sample != null) {
                    battery.append(listOf(sample))
                    _samples.value = battery.all
                }
            }
        }
    }

    /**
     * Logs one VCSEC status reading when [shouldLogStatus] says so. The reply
     * carries no time, so [deviceTimestamp] is the phone's clock at receipt
     * and the reading's time on the timeline; it goes only into the record's
     * own `device_timestamp`, never into a car field. [rssi] is the phone's latest
     * RSSI for the car, or null when it has none.
     */
    fun recordStatus(
        vehicleId: String,
        status: VehicleStatus,
        deviceTimestamp: Instant,
        rssi: Int?,
        firstAfterConnect: Boolean,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, vehicle_status = status)
        scope.launch {
            mutex.withLock {
                if (shouldLogStatus(lastLoggedStatus[vehicleId], status, deviceTimestamp, firstAfterConnect)) {
                    appendStatus(vehicleId, record)
                }
            }
        }
    }

    /**
     * Logs one DriveState reply, verbatim: every field the car sent, the
     * navigation destination and route included. [deviceTimestamp] is the
     * phone's clock when it arrived; it goes only into the record's own
     * `device_timestamp`, so the sample stays on the car's `DriveState.timestamp`.
     * [rssi] is the phone's latest RSSI for the car, or null when it has none.
     */
    fun recordDrive(
        vehicleId: String,
        drive: DriveState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        // Every reply is logged as the car sent it. Drive time comes only from
        // the car's own timestamp and is never filled in, so a reply without
        // one stays in the log but out of [driveSamples].
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, drive_state = drive)
        val sample = record.toDriveSample(vehicleId)
        val route = record.toRouteSample(vehicleId)
        scope.launch {
            mutex.withLock {
                recordStore.append(vehicleId, RecordKind.DRIVE, record)
                countAppend()
                if (sample != null) {
                    drives.append(listOf(sample))
                    _driveSamples.value = drives.all
                }
                if (route != null) {
                    val readings = (routeReadings[vehicleId].orEmpty() + route).takeLast(MAX_ROUTE_READINGS)
                    routeReadings[vehicleId] = readings
                    _trips.value = _trips.value + (vehicleId to segmentTrips(readings).takeLast(MAX_TRIPS))
                }
                // A reply without a route ends the trip, but the last route stays for [TRIP_END_GAP_MILLIS] more
                // (see activeRoute), stamped with when the car stopped reporting it.
                val previous = _latestRoutes.value[vehicleId]
                val phoneNow = deviceTimestamp.toEpochMilli()
                _latestRoutes.value =
                    when {
                        // A reading that follows within the gap resumes the same trip; after it, a new one starts.
                        route != null -> _latestRoutes.value + (vehicleId to route.continuing(previous?.takeIf { it.isHeldAt(phoneNow) }))
                        previous == null || previous.endedAtMillis != null -> _latestRoutes.value
                        else -> _latestRoutes.value + (vehicleId to previous.copy(endedAtMillis = phoneNow))
                    }
            }
        }
    }

    /**
     * Logs one ClosuresState reply, verbatim (ADR-0008). It holds the sentry
     * mode state next to the doors, windows and lock state. [deviceTimestamp] is the
     * phone's clock when it arrived; [rssi] is the phone's latest RSSI for the
     * car, or null when it has none. No read model yet.
     */
    fun recordClosures(
        vehicleId: String,
        closures: ClosuresState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, closures_state = closures)
        scope.launch {
            mutex.withLock {
                recordStore.append(vehicleId, RecordKind.CLOSURES, record)
                appendActivity(vehicleId, record)
            }
        }
    }

    /**
     * Logs one ClimateState reply, verbatim (ADR-0008). [deviceTimestamp] is the
     * phone's clock when it arrived; [rssi] is the phone's latest RSSI for the
     * car, or null when it has none. No read model yet.
     */
    fun recordClimate(
        vehicleId: String,
        climate: ClimateState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, climate_state = climate)
        scope.launch {
            mutex.withLock {
                recordStore.append(vehicleId, RecordKind.CLIMATE, record)
                appendActivity(vehicleId, record)
            }
        }
    }

    /**
     * Logs one LocationState reply, verbatim (ADR-0008): coordinates, heading and elevation, and
     * any field the app does not know yet. [deviceTimestamp] and [rssi] as for the other kinds.
     * No read model yet.
     */
    fun recordLocation(
        vehicleId: String,
        location: LocationState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, location_state = location)
        scope.launch { mutex.withLock { recordStore.append(vehicleId, RecordKind.LOCATION, record) } }
    }

    /** Logs one TirePressureState reply, verbatim (ADR-0008). No read model yet. */
    fun recordTirePressure(
        vehicleId: String,
        tirePressure: TirePressureState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, tire_pressure_state = tirePressure)
        scope.launch { mutex.withLock { recordStore.append(vehicleId, RecordKind.TIRE_PRESSURE, record) } }
    }

    /**
     * Logs a change in the phone's connection to [vehicleId] (ADR-0008):
     * [state] is `CONNECTED` when the connection became ready and
     * `DISCONNECTED` when an established one ended, so a DISCONNECTED marks
     * when watching ended. [deviceTimestamp] is when it happened, and [rssi]
     * the phone's last RSSI for the car before that, or null when it has none.
     * Nothing reads these records back yet.
     */
    fun recordConnection(
        vehicleId: String,
        state: ConnectionEvent.State,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record =
            BleRecord(
                device_timestamp = deviceTimestamp,
                rssi = rssi,
                connection_event = ConnectionEvent(state = state),
            )
        scope.launch {
            mutex.withLock {
                recordStore.append(vehicleId, RecordKind.CONNECTION, record)
            }
        }
    }

    /**
     * Logs a request the app sent to [vehicleId]'s car, with its reason (ADR-0008). [command] holds the
     * plaintext request as built; the caller leaves out the routine VCSEC status poll. [deviceTimestamp]
     * is when it was sent and [rssi] the phone's latest RSSI for the car, or null when it has none.
     */
    fun recordCommand(
        vehicleId: String,
        command: Command,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, command = command)
        scope.launch { mutex.withLock { recordStore.append(vehicleId, RecordKind.COMMAND, record) } }
    }

    /**
     * Logs that the car refused a command or never answered it (ADR-0008). Replies that went fine are
     * already visible as data records. [deviceTimestamp] is when the reply arrived, or when the
     * wait ended.
     */
    fun recordCommandResult(
        vehicleId: String,
        result: CommandResult,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, command_result = result)
        scope.launch { mutex.withLock { recordStore.append(vehicleId, RecordKind.COMMAND, record) } }
    }

    /** Logs a change in the app's own state (ADR-0008); it belongs to no car, so it has no RSSI. */
    fun recordAppState(
        state: AppState,
        deviceTimestamp: Instant,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, app_state = state)
        scope.launch { mutex.withLock { recordStore.append("", RecordKind.APP, record) } }
    }

    /**
     * Writes every record as framed `<vehicleId>.<kind>.pblog` files into a zip on [out] and returns
     * how many records it holds (ADR-0014). The caller owns the stream's target; this closes [out].
     */
    suspend fun exportLogs(out: OutputStream): Int =
        withContext(Dispatchers.IO) { LogExporter.export(recordStore, out, BuildConfig.VERSION_NAME) }

    private fun appendStatus(
        vehicleId: String,
        record: BleRecord,
    ) {
        recordStore.append(vehicleId, RecordKind.VCSEC, record)
        lastLoggedStatus[vehicleId] = record
        val sample = record.toStatusSample(vehicleId) ?: return
        statuses.append(listOf(sample))
        _statusSamples.value = statuses.all
    }

    private fun appendActivity(
        vehicleId: String,
        record: BleRecord,
    ) {
        val sample = record.toActivitySample(vehicleId) ?: return
        val kept = activityThinner.accept(sample)
        if (kept.isEmpty()) return
        activity.append(kept)
        _activitySamples.value = activity.all
    }

    /** Reads the closures and climate logs, which hold Sentry and climate state, into one list, oldest first. */
    private fun readActivitySamples(): List<ActivitySample> =
        runCatching {
            mergeByVehicle(
                recordStore.readRecent(RecordKind.CLOSURES, MAX_SAMPLES),
                recordStore.readRecent(RecordKind.CLIMATE, MAX_SAMPLES),
            ).flatMap { (vehicleId, records) -> records.mapNotNull { it.toActivitySample(vehicleId) } }
                .sortedBy { it.timestampMillis }
                .flatMap { activityThinner.accept(it) }
                .takeLast(MAX_ACTIVITY_SAMPLES)
        }.getOrDefault(emptyList())

    private fun readSamples(): List<BatterySample> =
        runCatching {
            recordStore
                .readRecent(RecordKind.CHARGE, MAX_SAMPLES)
                .flatMap { (vehicleId, records) ->
                    records.mapNotNull { it.toBatterySample(vehicleId) }
                }.sortedBy { it.timestampMillis }
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())

    /** Reads every status log and seeds [lastLoggedStatus] with each file's newest status record. */
    private fun readStatusSamples(): List<StatusSample> =
        runCatching {
            val logs = recordStore.readRecent(RecordKind.VCSEC, MAX_SAMPLES)
            for ((vehicleId, records) in logs) {
                records.lastOrNull { it.vehicle_status != null }?.let { lastLoggedStatus[vehicleId] = it }
            }
            logs
                .flatMap { (vehicleId, records) ->
                    records.mapNotNull { it.toStatusSample(vehicleId) }
                }.sortedBy { it.timestampMillis }
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())

    /**
     * Reads every drive log once: the drive samples, and each car's route, which is the route of its
     * newest drive record, so a newer reply without one means navigation ended.
     */
    private fun readDrive(): Pair<List<DriveSample>, Map<String, RouteSample>> =
        runCatching {
            val logs = recordStore.readRecent(RecordKind.DRIVE, MAX_SAMPLES)
            val samples =
                logs
                    .flatMap { (vehicleId, records) ->
                        records.mapNotNull { it.toDriveSample(vehicleId) }
                    }.sortedBy { it.timestampMillis }
                    .takeLast(MAX_SAMPLES)
            val routes =
                logs
                    .mapNotNull { (vehicleId, records) ->
                        routeReadings[vehicleId] =
                            records.mapNotNull { it.toRouteSample(vehicleId) }.takeLast(MAX_ROUTE_READINGS)
                        // Oldest first: a reply without a route ends the trip, so the fold
                        // ends on the live route, or null once navigation stopped.
                        val live =
                            records
                                .sortedBy { it.drive_state?.timestamp?.toEpochMilli() ?: Long.MIN_VALUE }
                                .fold<BleRecord, RouteSample?>(null) { previous, record ->
                                    record.toRouteSample(vehicleId)?.continuing(previous)
                                }
                        live?.let { vehicleId to it }
                    }.toMap()
            samples to routes
        }.getOrDefault(emptyList<DriveSample>() to emptyMap())

    private companion object {
        const val HISTORY_DIR_NAME = "battery-history"

        const val MAX_SAMPLES = 20_000

        /** Appended records between two catch-ups of `derived.db`. */
        const val BUILD_EVERY = 1_000

        /** Thinned Sentry and climate readings kept in memory: a state change and one every ten minutes of a run. */
        const val MAX_ACTIVITY_SAMPLES = 50_000

        /** Route readings kept per car for the past trips: a navigation sends one every few minutes. */
        const val MAX_ROUTE_READINGS = 5_000

        /** The past trips the selector offers per car. */
        const val MAX_TRIPS = 30
    }
}

/** The logs the screens read: battery, drive and status samples, each oldest first. */
data class HistorySamples(
    val battery: List<BatterySample>,
    val drive: List<DriveSample>,
    val status: List<StatusSample> = emptyList(),
    /** Sentry and climate readings, which say when a locked car was kept busy. */
    val activity: List<ActivitySample> = emptyList(),
    /** The newest route-bearing drive reading per car, by vehicle id. */
    val routes: Map<String, RouteSample> = emptyMap(),
    /** The finished navigations per car, by vehicle id, oldest first. */
    val trips: Map<String, List<Trip>> = emptyMap(),
    /** Every car's charge sessions as `derived.db` stores them, oldest first; see [HistoryStore.chargeSessions]. */
    val chargeSessions: List<ChargeSession> = emptyList(),
    /** False while the start is still publishing the logs; see [HistoryStore.loaded]. */
    val loaded: Boolean = true,
)
