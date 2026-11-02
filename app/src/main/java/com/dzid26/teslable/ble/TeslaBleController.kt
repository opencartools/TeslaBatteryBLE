// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.history.ActivitySample
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.CommandRecords
import com.dzid26.teslable.core.history.ConnectionEvent
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.history.Trip
import com.dzid26.teslable.core.infotainment.InfotainmentPollPolicy
import com.dzid26.teslable.core.infotainment.InfotainmentSessionGate
import com.dzid26.teslable.history.HistoryStore
import com.dzid26.teslable.history.LogExportRunner
import com.dzid26.teslable.protocol.AntiReplayWindow
import com.dzid26.teslable.protocol.FaultRecovery
import com.dzid26.teslable.protocol.PairingFlow
import com.dzid26.teslable.protocol.PairingHost
import com.dzid26.teslable.protocol.PairingPhase
import com.dzid26.teslable.protocol.PairingReason
import com.dzid26.teslable.protocol.PairingTimer
import com.dzid26.teslable.protocol.SessionHandshake
import com.dzid26.teslable.protocol.SessionHost
import com.dzid26.teslable.protocol.TeslaCommands
import com.dzid26.teslable.protocol.TeslaCrypto
import com.dzid26.teslable.protocol.TeslaLocationTireCommands
import com.dzid26.teslable.protocol.TeslaNames
import com.dzid26.teslable.protocol.TeslaSessionRequests
import com.dzid26.teslable.protocol.TeslaVcsec
import com.dzid26.teslable.protocol.asleep
import com.dzid26.teslable.protocol.chargingStateKind
import com.dzid26.teslable.protocol.isRefusal
import com.dzid26.teslable.protocol.locked
import com.dzid26.teslable.protocol.recovery
import com.dzid26.teslable.protocol.userPresent
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * Owns every vehicle the app knows and one [VehicleLink] per car. Scanning,
 * discovery and selection live here; connecting, sessions, pairing, polling and
 * commands live in the link, so one car failing never touches another.
 */
class TeslaBleController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val keyStore = PairingKeyStore(appContext)
    private val vehicleStore = VehicleStore(appContext)
    private val vehicles = mutableMapOf<String, Vehicle>()
    private val historyStore: HistoryStore

    /** Battery readings recorded from every charge response, oldest first. */
    val batteryHistory: StateFlow<List<BatterySample>> get() = historyStore.samples

    /** VCSEC status readings logged for every car, oldest first. */
    val statusHistory: StateFlow<List<StatusSample>> get() = historyStore.statusSamples

    /** Sentry and climate readings logged for every car, oldest first. */
    val activityHistory: StateFlow<List<ActivitySample>> get() = historyStore.activitySamples

    /** Drive readings recorded from every DriveState response, oldest first. */
    val driveHistory: StateFlow<List<DriveSample>> get() = historyStore.driveSamples

    /** False while the start is still publishing the logs; see [HistoryStore.loaded]. */
    val historyLoaded: StateFlow<Boolean> get() = historyStore.loaded

    /** Every car's charge sessions as `derived.db` stores them, oldest first. */
    val chargeSessions: StateFlow<List<ChargeSession>> get() = historyStore.chargeSessions

    /** The newest route-bearing drive reading per car, for the live trip card. */
    val latestRoutes: StateFlow<Map<String, RouteSample>> get() = historyStore.latestRoutes
    val trips: StateFlow<Map<String, List<Trip>>> get() = historyStore.trips

    /** The Settings "Export logs" run into a picked document: one at a time, result kept across screens. */
    val logExport: LogExportRunner by lazy { LogExportRunner(appContext.contentResolver, historyStore::exportLogs) }

    private val links = mutableMapOf<String, VehicleLink>()
    private val handler = Handler(Looper.getMainLooper())
    private val clientAddress = TeslaCrypto.randomBytes(16)
    private var selectedBleName: String? = null
    private val vinPrompts = VinPrompts(_state, ::log)
    private val carActions = CarActions()

    /** Cars a scan already connected to once, only to read their name; reset by each new scan. */
    private val nameProbes = mutableSetOf<String>()
    private var uiVisible = false
    private var lastVisibilityEvent: AppState.Event? = null

    /** The latest adverts from each source, merged so a real callback cannot hide the demo rows between ticks. */
    private var realAdverts: List<TeslaAdvert> = emptyList()
    private var demoAdverts: List<TeslaAdvert> = emptyList()

    private val scanner =
        TeslaScanner(
            context = appContext,
            onDevices = { adverts ->
                realAdverts = adverts
                onDevicesFound(realAdverts + demoAdverts)
            },
            onLog = ::log,
            // A refused start never ran: stopScan drops the scanning state (so the button and the
            // "scanning" text are honest) and leaves the scanner's own retry to run.
            onRefused = ::stopScan,
        )

    private fun demoCar(): DemoMode.Car? = if (DemoMode.isEnabled()) DemoMode.car else null

    private fun createTransport(
        address: String,
        listener: TeslaTransport.Listener,
    ): TeslaTransport =
        demoCar()
            ?.takeIf { DemoMode.isDemoAddress(address) }
            ?.createTransport(address, listener)
            ?: TeslaGattClient(appContext, listener, autoConnect = DebugSwitches(appContext).autoConnect)

    private val demoCars = DemoCars()

    /** Fake adverts next to the real scan while the demo switch is on. */
    private val demoScan =
        object : Runnable {
            override fun run() {
                demoAdverts = demoCar()?.adverts() ?: emptyList()
                onDevicesFound(realAdverts + demoAdverts)
                if (_state.value.scanning || _state.value.discovering) {
                    handler.postDelayed(this, DEMO_SCAN_MS)
                }
            }
        }

    /** Silent discovery is a recovery path, not a permanent scan. */
    private val stopDiscovery =
        Runnable {
            if (_state.value.discovering) {
                stopScanner()
                _state.update { it.copy(discovering = false) }
            }
        }

    /**
     * Ends a scan once no new car has appeared for [SCAN_QUIET_MS]: the list
     * has settled, so scanning on would only spin the radio.
     */
    private val stopQuietScan =
        Runnable {
            if (_state.value.scanning) {
                log("scan: no new cars for ${SCAN_QUIET_MS / 1000}s; stopping")
                stopScan()
            }
        }

    /**
     * Follows the Bluetooth switch for the life of the process: the screens show "Bluetooth off" while it is off,
     * and links reconnect as soon as it is back on instead of waiting out the reconnect backoff.
     */
    private val bluetoothReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> _state.update { it.copy(bluetoothOn = false) }

                    BluetoothAdapter.STATE_ON -> {
                        _state.update { it.copy(bluetoothOn = true) }
                        ensureConnected(bluetoothJustOn = true)
                    }
                }
            }
        }

    init {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter != null && !adapter.isEnabled) _state.update { it.copy(bluetoothOn = false) }
        ContextCompat.registerReceiver(
            appContext,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        vehicleStore.load().forEach { vehicles[it.bleName] = it }
        // A restored backup can carry cached key slots for a key this device
        // does not have (device-only keys never leave the phone). Enrollment is
        // re-verified on connect, so drop the stale cache.
        val staleSlots = vehicles.values.filter { it.keySlot != null && !keyStore.hasKey(it.bleName) }
        if (staleSlots.isNotEmpty()) {
            staleSlots.forEach { vehicles[it.bleName] = it.copy(keySlot = null) }
            vehicleStore.save(vehicles.values)
        }
        historyStore = HistoryStore(appContext)
        if (!prefs.getBoolean(KEY_TRACKING_ENABLED, true)) {
            _state.update { it.copy(trackingEnabled = false) }
        }
        DemoMode.onEnabledChanged = demoCars::onChanged
        publishVehicles()
        val saved = prefs.getString(KEY_SELECTED_VEHICLE, null)
        if (saved != null && vehicles.containsKey(saved)) {
            // The last car the owner opened is the default on launch.
            selectVehicle(saved)
        }
    }

    // ---------------------------------------------------------------- UI actions

    /**
     * Saves what the owner entered for one car. A VIN, when given, must hash to the car's advertised name;
     * without one the saved VIN stays. The new-car range and capacity are saved as given, null to clear them.
     */
    fun saveCar(
        bleName: String,
        details: CarDetails,
    ) = carActions.save(bleName, details)

    /** The car screen's banner: opens the same VIN prompt without anything to replay. */
    fun requestVin(bleName: String) = carActions.requestVin(bleName)

    /** The owner dismissed the VIN prompt: nothing is saved and the blocked action is not replayed. */
    fun dismissVinRequest() = carActions.dismiss()

    fun startScan() {
        if (!_state.value.trackingEnabled) return
        if (!_state.scanAllowed(appContext, ::log)) return
        // Additive scan: keep the current connections and look for more cars.
        links.values.forEach {
            it.reconnectAttempts = 0
            it.nextRetryAtMs = 0
        }
        nameProbes.clear()
        _state.update {
            it.copy(
                scanning = true,
                discovering = false,
                explicitScan = true,
                locationNeeded = false,
            )
        }
        startScanner()
        handler.removeCallbacks(stopQuietScan)
        handler.postDelayed(stopQuietScan, SCAN_QUIET_MS)
    }

    fun stopScan() {
        handler.removeCallbacks(stopDiscovery)
        handler.removeCallbacks(stopQuietScan)
        stopScanner()
        _state.update { it.copy(scanning = false, discovering = false) }
    }

    /** The UI opened a car by its current address. */
    fun openVehicle(address: String) {
        val name =
            _state.value.connections[address]?.name
                ?: _state.value.devices
                    .firstOrNull { it.address == address }
                    ?.name
                ?: vehicles.values.firstOrNull { it.address == address }?.bleName
                ?: return
        openVehicle(name, address)
    }

    /** A notification tap asked for a specific car, by its stable advertised name. */
    fun openVehicleByBleName(bleName: String) {
        val vehicle = vehicles[bleName] ?: return
        openVehicle(bleName, vehicle.address)
    }

    /** Clears the one-shot notification open request once the UI showed it. */
    fun consumeOpenVehicleRequest() {
        _state.update { it.copy(openVehicleRequest = null) }
    }

    /**
     * Forgets each car's cached Bluetooth address and pairing slots, so the next scan finds the
     * cars again at their current addresses. Cars, stored keys and history are kept.
     */
    fun clearCarCache() {
        links.values.forEach { it.close() }
        links.clear()
        vehicles.keys.forEach { name -> vehicles[name] = vehicles.getValue(name).copy(address = "", keySlot = null) }
        vehicleStore.save(vehicles.values)
        publishVehicles()
        _state.update { it.copy(devices = emptyList(), connections = emptyMap()) }
        log("Car cache cleared; the next scan finds the cars again")
    }

    private fun openVehicle(
        bleName: String,
        address: String,
    ) {
        selectVehicle(bleName, persist = true)
        _state.update { it.copy(openVehicleRequest = bleName) }
        // The master switch is off: the car view opens with what is stored, but nothing connects.
        if (!_state.value.trackingEnabled) return
        val link = links[bleName]
        when (link?.phase) {
            ConnectionPhase.READY -> {
                link.beginStartup()
                stopScan()
                link.startRssiPoll()
                link.postPoll()
            }

            ConnectionPhase.CONNECTING -> Unit

            else -> ensureLink(bleName, address).connect()
        }
    }

    /**
     * Reconnects every known car when nothing is connected (app open, tracking
     * on, service start). Direct connects first; discovery takes over when a
     * car stopped advertising or moved to a new address. The scan button stays
     * a manual "find new cars" action.
     *
     * [bluetoothJustOn]: a retry that fired while Bluetooth was off never completed, and the backoff may be a minute
     * long by now, so every car that is not connected tries again at once.
     */
    fun ensureConnected(bluetoothJustOn: Boolean = false) {
        if (!_state.value.trackingEnabled) return
        if (!hasBlePermissions(appContext)) return
        if (hasLocationPermission(appContext) && _state.value.locationNeeded) {
            _state.update { it.copy(locationNeeded = false) }
            startDiscovery()
        }
        if (bluetoothJustOn) links.values.filter { it.phase != ConnectionPhase.READY }.forEach { it.retryNow() }
        if (_state.value.scanning) return
        val ordered = orderedVehicles()
        var active = links.values.count { it.phase in ACTIVE_PHASES }
        for (vehicle in ordered) {
            if (active >= MAX_ACTIVE_LINKS) break
            val link = links[vehicle.bleName]
            if (link != null && link.phase in ACTIVE_PHASES) continue
            if (vehicle.address.isEmpty()) continue
            log("${vehicle.title}: reconnecting to paired car")
            ensureLink(vehicle.bleName, vehicle.address).connect()
            active++
        }
        if (_state.value.devices.isEmpty() && ordered.isNotEmpty()) {
            _state.update {
                it.copy(devices = ordered.map { vehicle -> TeslaAdvert(vehicle.bleName, vehicle.address) })
            }
        }
    }

    fun pairKey(address: String) {
        val link =
            linkForAddress(address) ?: run {
                log("No selected car ready for pairing")
                return
            }
        selectVehicle(link.bleName)
        link.pairing.pairKey()
    }

    /** True when an enrolled car is connected, so the tracking service should run. */
    fun shouldTrack(): Boolean = _state.value.shouldTrack()

    /** Master switch: off tears everything down, on reconnects to paired cars. */
    fun setTrackingEnabled(enabled: Boolean) {
        if (_state.value.trackingEnabled == enabled) return
        prefs.edit().putBoolean(KEY_TRACKING_ENABLED, enabled).apply()
        _state.update { it.copy(trackingEnabled = enabled) }
        if (enabled) {
            log("Background tracking enabled")
            ensureConnected()
        } else {
            disableTracking()
        }
    }

    private fun disableTracking() {
        BleTrackingService.stop(appContext)
        handler.removeCallbacks(stopDiscovery)
        stopScanner()
        links.values.forEach { it.close() }
        links.clear()
        handler.removeCallbacksAndMessages(null)
        selectedBleName = null
        // The log stays: switching tracking off says nothing about what the app did until now, and
        // the line below is the last entry of that story.
        _state.update {
            it.copy(
                scanning = false,
                discovering = false,
                explicitScan = false,
                devices = emptyList(),
                connections = emptyMap(),
                selectedBleName = null,
                log = emptyList(),
                vinRequest = null,
            )
        }
        log("Background tracking disabled")
    }

    /**
     * The car screen's pull: connect, wake the car or read it, whichever fits the link now. The VIN gate
     * covers all three, and a saved VIN replays the same gesture, so the pull behaves the same before and
     * after the prompt. [address] is the live one from the screen; a stored car falls back to its own.
     */
    fun refreshVehicle(
        bleName: String? = null,
        address: String? = null,
    ) = carActions.refresh(bleName, address)

    fun wakeVehicle(bleName: String? = null) = carActions.wake(bleName)

    fun requestChargeState(bleName: String? = null) = carActions.read(bleName)

    /** Sends one quick action to the selected (or given) car; the snackbar reports each step. */
    fun sendQuickAction(
        action: QuickAction,
        bleName: String? = null,
    ) = carActions.quickAction(action, bleName)

    /** The car screen showed the last quick action's snackbar. */
    fun consumeActionFeedback() {
        _state.update { it.copy(actionFeedback = null) }
    }

    /** The one slot the car screen's snackbar reads; a newer result replaces one that was never shown. */
    internal fun publishActionFeedback(feedback: QuickActionFeedback) {
        _state.update { it.copy(actionFeedback = feedback) }
    }

    /**
     * The car screen's user actions behind the VIN gate, and the prompt that asks for it. Nested so the
     * controller's own body stays within the class-size limit; every public entry point delegates here.
     */
    private inner class CarActions {
        /** Saves what the owner entered for a car, then replays the action its VIN prompt blocked, once. */
        fun save(
            bleName: String,
            details: CarDetails,
        ) {
            val vehicle = vehicles[bleName] ?: return
            val normalized = details.vin?.let(Vehicle::normalizeVin)?.takeIf { it.isNotEmpty() }
            if (normalized != null && !vehicle.acceptsVin(normalized)) {
                log("${vehicle.title}: VIN does not match this car's advertised name")
                return
            }
            updateVehicle(bleName) {
                it.copy(
                    vin = normalized ?: it.vin,
                    factoryRangeMiles = details.factoryRangeMiles,
                )
            }
            log("${vehicle.title}: details saved")
            // A VIN unlocks the sessions; retry the handshake now.
            if (normalized != null) links[bleName]?.takeIf { it.phase == ConnectionPhase.READY }?.startSession()
            saved(bleName)
        }

        /** Pull to refresh: reconnect, wake or read, whichever fits the link now. */
        fun refresh(
            bleName: String?,
            address: String?,
        ) {
            val name = bleName ?: selectedBleName ?: return
            if (blocks(vehicles[name], VinIntent.Refresh)) return
            val link = links[name]
            val linkAddress = address ?: vehicles[name]?.address
            if (link == null || link.phase != ConnectionPhase.READY) {
                if (linkAddress != null) openVehicle(name, linkAddress)
            } else if (
                _state.value.connections[link.address]
                    ?.status
                    ?.asleep != false
            ) {
                link.wake()
            } else {
                readLink(name)
            }
        }

        fun wake(bleName: String?) {
            val name = bleName ?: selectedBleName
            if (blocks(name?.let(vehicles::get), VinIntent.Wake)) return
            val link = name?.let(links::get)
            if (link == null) log("No connected car to wake") else link.wake()
        }

        fun read(bleName: String?) {
            val name = bleName ?: selectedBleName ?: return
            if (blocks(vehicles[name], VinIntent.Refresh)) return
            readLink(name)
        }

        /**
         * A quick action. Waking is the gate's own wake intent; the other two move the car, so they are a
         * control and a car with no VIN asks for one before it is told to move.
         */
        fun quickAction(
            action: QuickAction,
            bleName: String?,
        ) {
            if (action == QuickAction.WAKE) {
                wake(bleName)
                return
            }
            val name = bleName ?: selectedBleName
            if (blocks(name?.let(vehicles::get), VinIntent.Control(action))) return
            val link = name?.let(links::get)
            if (link == null) {
                log("No connected car for ${action.name.lowercase()}")
                publishActionFeedback(QuickActionFeedback(action, QuickActionOutcome.FAILED))
            } else {
                link.sendQuickAction(action)
            }
        }

        fun requestVin(bleName: String) {
            vehicles[bleName]?.let { vinPrompts.request(it) }
        }

        fun dismiss() = vinPrompts.dismiss()

        /** The VIN was saved: the action its prompt blocked runs now, once. */
        fun saved(bleName: String) {
            when (val intent = vinPrompts.saved(vehicles[bleName])) {
                VinIntent.Refresh -> refresh(bleName, address = null)
                VinIntent.Wake -> wake(bleName)
                is VinIntent.Control -> quickAction(intent.action, bleName)
                null -> Unit
            }
        }

        /** The read behind both the pull and a saved VIN's replay; never gated again. */
        private fun readLink(name: String) = links[name]?.requestChargeState(Command.Reason.USER_REFRESH)

        /** True when the VIN prompt took over [intent], so the caller must stop. */
        private fun blocks(
            vehicle: Vehicle?,
            intent: VinIntent,
        ): Boolean = vehicle != null && vinPrompts.request(vehicle, intent)
    }

    /**
     * Logs a change in the app's own state (ADR-0008); [startReason] goes with a tracking start. A
     * foreground or background event that repeats the last one (a rotation restarts the activity) is dropped.
     */
    fun recordAppState(
        event: AppState.Event,
        startReason: AppState.StartReason? = null,
    ) {
        if (event == AppState.Event.APP_FOREGROUND || event == AppState.Event.APP_BACKGROUND) {
            if (event == lastVisibilityEvent) return
            lastVisibilityEvent = event
        }
        historyStore.recordAppState(
            AppState(event = event, start_reason = startReason),
            Instant.ofEpochMilli(System.currentTimeMillis()),
        )
    }

    /** The UI polls RSSI fast only while it is on screen. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        links.values.forEach { it.stopRssiPoll() }
        if (visible) {
            // Opening the app is the signal to try again now, not after the long background wait.
            links.values.forEach { it.resetBackoff() }
            selectedLink()?.takeIf { it.phase == ConnectionPhase.READY }?.let { link ->
                link.startRssiPoll()
                // A paused poll should not greet the owner with a reading from before the pause.
                link.readForUi()
            }
        }
    }

    // ------------------------------------------------------- scanning / discovery

    private fun onDevicesFound(devices: List<TeslaAdvert>) {
        val now = System.currentTimeMillis()
        // A new car resets the quiet timer that ends the scan.
        val previousNames =
            _state.value.devices
                .map { it.name }
                .toSet()
        if (_state.value.scanning && devices.any { it.name !in previousNames }) {
            handler.removeCallbacks(stopQuietScan)
            handler.postDelayed(stopQuietScan, SCAN_QUIET_MS)
        }
        val strongestByName =
            devices
                .groupBy { it.name }
                .mapValues { (_, found) -> found.maxBy { it.rssi ?: Int.MIN_VALUE } }
        for ((name, device) in strongestByName) {
            val known = vehicles[name] ?: continue
            vehicles[name] = known.copy(lastSeenMillis = now)
            // The car turned up after a silence while its link waits out a backoff: try again within
            // seconds. A car that never stops advertising but will not connect does not reset it.
            if (ReconnectBackoff.isArrival(now, known.lastSeenMillis)) {
                links[name]?.takeIf { it.phase !in ACTIVE_PHASES }?.resetBackoff()
            }
            if (known.address == device.address) continue
            // The car is advertising from a new address; follow it.
            updateVehicle(name) { it.copy(address = device.address) }
            links[name]?.let { link ->
                if (link.address != device.address) link.close()
            }
        }
        _state.update { state ->
            val connections = state.connections.toMutableMap()
            for (device in devices) {
                val existing = connections[device.address]
                if (existing == null) {
                    connections[device.address] =
                        TeslaConnection(
                            address = device.address,
                            name = device.name,
                            rssi = device.rssi,
                        )
                } else if (existing.name != device.name || existing.rssi != device.rssi) {
                    connections[device.address] =
                        existing.copy(
                            name = device.name,
                            rssi = device.rssi,
                        )
                }
            }
            state.copy(
                devices = visibleDevices(devices, connections, state.explicitScan),
                connections = connections,
            )
        }
        publishVehicles()
        autoConnect()
        if (_state.value.explicitScan) probeNames(strongestByName.values)
    }

    private fun worthProbing(device: TeslaAdvert): Boolean =
        (device.rssi ?: Int.MIN_VALUE) > NAME_PROBE_MIN_RSSI &&
            TeslaNames.isTeslaBleName(device.name) &&
            !vehicles.containsKey(device.name)

    /**
     * The car's name is only readable over a connection, so a scan briefly connects to each
     * unknown car in range once, which lets its row show the name without a tap. Capped like any link.
     */
    private fun probeNames(found: Collection<TeslaAdvert>) {
        var active = links.values.count { it.phase in ACTIVE_PHASES }
        for (device in found) {
            if (active >= MAX_ACTIVE_LINKS) break
            val name = device.name
            if (!worthProbing(device) || !nameProbes.add(name)) continue
            if (links[name]?.phase in ACTIVE_PHASES) continue
            ensureLink(name, device.address).connect()
            active++
        }
    }

    /**
     * Connected cars stay listed; an explicit scan adds every discovered car
     * (strongest per name), while silent discovery surfaces every known car and
     * at most one unknown one. Unpaired cars only stay while they advertise.
     */
    private fun visibleDevices(
        found: List<TeslaAdvert>,
        connections: Map<String, TeslaConnection>,
        explicitScan: Boolean,
    ): List<TeslaAdvert> {
        val visible = mutableListOf<TeslaAdvert>()
        connections.values
            .filter { it.phase in ACTIVE_PHASES }
            .forEach { connection ->
                visible += found.firstOrNull { it.address == connection.address }
                    ?: TeslaAdvert(
                        name = connection.name,
                        address = connection.address,
                        rssi = connection.rssi,
                    )
            }
        val candidates =
            if (explicitScan) {
                found
            } else {
                val known = found.filter { vehicles.containsKey(it.name) }
                known.ifEmpty { listOfNotNull(found.maxByOrNull { it.rssi ?: Int.MIN_VALUE }) }
            }
        candidates
            .groupBy { it.name }
            .mapValues { (_, devices) -> devices.maxBy { it.rssi ?: Int.MIN_VALUE } }
            .values
            .forEach { device ->
                if (visible.none { it.address == device.address }) visible += device
            }
        return visible
    }

    /** Connects every known car that is in range, up to the connection cap. */
    private fun autoConnect() {
        if (!_state.value.trackingEnabled) return
        val state = _state.value
        var active = links.values.count { it.phase in ACTIVE_PHASES }
        val now = System.currentTimeMillis()
        for (device in state.devices) {
            if (active >= MAX_ACTIVE_LINKS) break
            if (!vehicles.containsKey(device.name)) continue
            val link = links[device.name]
            if (link != null && link.phase in ACTIVE_PHASES) continue
            if (link != null && now < link.nextRetryAtMs) continue
            log("${device.name}: reconnecting to paired car")
            ensureLink(device.name, device.address).connect()
            active++
        }
    }

    /**
     * [background] is silent discovery of the known cars: filtered by their advertised names and
     * scanned in low-power mode. A user's scan stays unfiltered and low-latency, because it has to
     * find cars it has never seen.
     */
    private fun startScanner(background: Boolean = false) {
        // The demo adverts run alongside the real scan, so the fake car can join a real one.
        if (DemoMode.isEnabled()) {
            handler.removeCallbacks(demoScan)
            handler.post(demoScan)
        }
        if (background && vehicles.isNotEmpty()) {
            scanner.start(deviceNames = vehicles.keys.toList(), lowPower = true)
        } else {
            scanner.start(lowPower = background)
        }
    }

    private fun stopScanner() {
        handler.removeCallbacks(demoScan)
        scanner.stop()
    }

    /** Finds known cars without taking over the scan button (reconnection). */
    private fun startDiscovery() {
        if (!_state.value.trackingEnabled) return
        if (_state.value.scanning || _state.value.discovering) return
        if (!hasBlePermissions(appContext)) return
        if (!_state.scanAllowed(appContext, ::log)) return
        _state.update { it.copy(discovering = true, explicitScan = false, locationNeeded = false) }
        handler.removeCallbacks(stopDiscovery)
        handler.postDelayed(stopDiscovery, DISCOVERY_TIMEOUT_MS)
        startScanner(background = true)
    }

    /** Silent discovery is done once every known car has a live link. */
    private fun maybeStopDiscovery() {
        if (!_state.value.discovering) return
        val known = vehicles.values
        if (known.isNotEmpty() && known.all { links[it.bleName]?.phase == ConnectionPhase.READY }) {
            stopScan()
        }
    }

    // -------------------------------------------------------- vehicle bookkeeping

    private fun orderedVehicles(): List<Vehicle> =
        vehicles.values.sortedWith(
            compareByDescending<Vehicle> { it.bleName == selectedBleName }
                .thenByDescending { it.lastSeenMillis },
        )

    private fun rememberVehicle(address: String) {
        val connection = _state.value.connections[address] ?: return
        rememberVehicle(connection.name, address, connection.gattDeviceName)
    }

    /** Stores a car by its advertised name so later opens and scans find it; the UI logs the chooser's result. */
    internal fun rememberVehicle(
        name: String,
        address: String,
        gattName: String?,
    ) {
        if (!TeslaNames.isTeslaBleName(name)) return
        val existing = vehicles[name]
        val demoVin = DemoMode.vinFor(name, address)
        val updated =
            (existing ?: Vehicle(bleName = name, address = address)).copy(
                address = address,
                gattName = gattName ?: existing?.gattName,
                vin = existing?.vin ?: demoVin,
                lastSeenMillis = System.currentTimeMillis(),
            )
        val changed =
            existing == null ||
                existing.address != updated.address ||
                (gattName != null && existing.gattName != gattName) ||
                (updated.vin != null && existing.vin == null)
        vehicles[name] = updated
        vehicleStore.save(vehicles.values)
        publishVehicles()
        if (changed) log("${nameFor(address)}: remembered for reconnect")
    }

    /**
     * The debug switch changed. The fake adverts only join a scan already in progress; a new demo car is
     * discovered by scanning, like a real one. Dropping it closes only its link, so real cars keep theirs.
     */
    private inner class DemoCars {
        fun onChanged(enabled: Boolean) {
            log("Demo car ${if (enabled) "on" else "off"}")
            if (enabled) {
                if (_state.value.scanning || _state.value.discovering) {
                    handler.removeCallbacks(demoScan)
                    handler.post(demoScan)
                }
            } else {
                handler.removeCallbacks(demoScan)
                demoAdverts = emptyList()
                closeLinks()
                remove()
            }
        }

        private fun closeLinks() {
            links.values
                .filter { DemoMode.isDemoAddress(it.address) }
                .forEach { links.remove(it.bleName)?.close() }
            val name = name()
            _state.update { state ->
                state.copy(
                    connections = state.connections.filterKeys { !DemoMode.isDemoAddress(it) },
                    devices = state.devices.filterNot { DemoMode.isDemoAddress(it.address) || it.name == name },
                )
            }
        }

        private fun remove() {
            val name = name() ?: return
            vehicles.remove(name)
            vehicleStore.save(vehicles.values)
            if (selectedBleName == name) {
                val next = orderedVehicles().firstOrNull()?.bleName
                selectedBleName = next
                _state.update { it.copy(selectedBleName = next) }
            }
            publishVehicles()
        }

        private fun name(): String? = runCatching { TeslaNames.bleName(DemoMode.DEMO_VIN) }.getOrNull()
    }

    private fun selectedLink(): VehicleLink? = selectedBleName?.let(links::get)

    private fun linkForAddress(address: String): VehicleLink? = links.values.firstOrNull { it.address == address }

    private fun selectVehicle(
        name: String,
        persist: Boolean = false,
    ) {
        val previous = selectedBleName
        selectedBleName = name
        if (persist) {
            prefs.edit().putString(KEY_SELECTED_VEHICLE, name).apply()
        }
        _state.update {
            it.copy(
                selectedBleName = name,
                // A prompt belongs to the car that raised it; switching cars drops it.
                vinRequest = if (previous == name) it.vinRequest else null,
            )
        }
    }

    private fun updateVehicle(
        bleName: String,
        transform: (Vehicle) -> Vehicle,
    ) {
        val vehicle = vehicles[bleName] ?: return
        vehicles[bleName] = transform(vehicle)
        vehicleStore.save(vehicles.values)
        publishVehicles()
    }

    private fun publishVehicles() {
        _state.update {
            it.copy(vehicles = vehicles.values.sortedByDescending { vehicle -> vehicle.lastSeenMillis })
        }
    }

    /** One link per car; recreates it when the car shows up at a new address. */
    private fun ensureLink(
        bleName: String,
        address: String,
    ): VehicleLink {
        val existing = links[bleName]
        if (existing != null && existing.address == address) return existing
        existing?.close()
        val link = VehicleLink(bleName, address)
        links[bleName] = link
        return link
    }

    private fun updateConnection(
        address: String,
        transform: (TeslaConnection) -> TeslaConnection,
    ) {
        _state.update { state ->
            val connection = state.connections[address] ?: return@update state
            state.copy(connections = state.connections + (address to transform(connection)))
        }
    }

    private fun nameFor(address: String): String {
        val connection = _state.value.connections[address]
        return connection?.gattDeviceName
            ?: connection?.name
            ?: _state.value.devices
                .firstOrNull { it.address == address }
                ?.name
            ?: address
    }

    /** The phone's latest RSSI for the car at [address] in dBm, or null before the first reading; every history record carries it. */
    private fun latestRssi(address: String): Int? = _state.value.connections[address]?.rssi

    /**
     * Logs a change in the connection to [link]'s car: [ConnectionEvent.State.CONNECTED] right after it became ready,
     * [ConnectionEvent.State.DISCONNECTED] right before a connection that had become ready ends. Both run while the phase is READY,
     * so a connection attempt that never got there is not logged.
     */
    private fun recordConnection(
        link: VehicleLink,
        state: ConnectionEvent.State,
    ) {
        if (link.phase != ConnectionPhase.READY) return
        val deviceTimestamp = Instant.ofEpochMilli(System.currentTimeMillis())
        historyStore.recordConnection(link.bleName, state, deviceTimestamp, latestRssi(link.address))
        // Whatever the car has not answered by now, it will not answer.
        if (state == ConnectionEvent.State.DISCONNECTED) link.commands.logUnanswered(linkEnded = true)
    }

    internal fun log(message: String) = appendLog(null, message)

    private fun appendLog(
        vehicleId: String?,
        message: String,
    ) {
        _state.update { it.copy(log = (it.log + LogEntry(vehicleId, message, System.currentTimeMillis())).takeLast(LOG_MAX_LINES)) }
    }

    // ------------------------------------------------------------- per-vehicle link

    private data class PendingCommand(
        val domain: Domain,
        val requestId: ByteArray,
        val kind: CommandKind,
        val reason: Command.Reason,
        val window: AntiReplayWindow = AntiReplayWindow(),
        val sentAtMillis: Long = System.currentTimeMillis(),
    )

    private enum class CommandKind {
        WAKE,
        CHARGE,
        DRIVE,
        CLOSURES,
        CLIMATE,
        LOCATION,
        TIRE_PRESSURE,
        ACTUATION,
        ;

        /** Part of an Infotainment read (charge, drive, closures, climate, location, tire pressure). */
        val isRead: Boolean
            get() =
                when (this) {
                    WAKE, ACTUATION -> false
                    CHARGE, DRIVE, CLOSURES, CLIMATE, LOCATION, TIRE_PRESSURE -> true
                }
    }

    /**
     * Everything that belongs to one car: the transport, sessions, pending
     * commands, retry/backoff counters, pairing state and its timers. The
     * controller treats each link as independent; nothing here touches another
     * car's state.
     */
    private inner class VehicleLink(
        val bleName: String,
        var address: String,
    ) {
        var phase: ConnectionPhase = ConnectionPhase.IDLE
        var nextRetryAtMs: Long = 0L

        /** The last time the link was up: when it went ready and at the first drop, not on each failed attempt. */
        var lastAliveMillis: Long = 0L

        private var transport: TeslaTransport? = null
        private val pendingCommands = mutableMapOf<String, PendingCommand>()
        private var wakeAfterSession = false

        /** Whether that wake came from the quick-action stack, so only its own snackbar reports it. */
        private var wakeFromStack = false

        /** The stack's queued and sent actions, with the snackbar feedback they produce. */
        private val quickActionSender =
            QuickActionSender(
                object : QuickActionHost {
                    override val carName: String get() = name()

                    override fun log(message: String) = this@VehicleLink.log(message)

                    override fun isAwake(): Boolean = this@VehicleLink.isAwake()

                    override fun hasInfotainmentSession(): Boolean = handshake.has(Domain.DOMAIN_INFOTAINMENT)

                    override fun readWaiting(): Boolean = infotainmentGate.readWaiting

                    override fun startSession(): Boolean = this@VehicleLink.startSession()

                    override fun resumeWaitingRead() = this@VehicleLink.resumeWaitingRead()

                    override fun wakeForAction(fromStack: Boolean) = wake(fromStack)

                    override fun forgetQueuedWake() = clearWake()

                    // The action goes out on the read's serial path: same domain, same reason.
                    override fun sendActuation(payload: ByteArray): String? =
                        sendAuthenticated(
                            Domain.DOMAIN_INFOTAINMENT,
                            payload,
                            CommandKind.ACTUATION,
                            Command.Reason.USER_ACTION,
                        )

                    override fun publish(feedback: QuickActionFeedback) = publishActionFeedback(feedback)
                },
            )
        var reconnectAttempts = 0
        private var wakeRefreshAttempts = 0
        private var decryptFailures = 0

        /** Resyncs since the car last accepted one of our commands; a streak ends in a new handshake. */
        private var resyncsWithoutReply = 0
        private var lastRehandshakeMs = 0L

        /** The next VCSEC status is the first since the link became ready; the status log always keeps it. */
        private var firstStatusAfterConnect = true

        /** Decides when an Infotainment read is due (ADR-0009). */
        private val infotainmentPolicy = InfotainmentPollPolicy()

        /** Whether a due read is waiting for the Infotainment handshake; never left set once it is over. */
        private val infotainmentGate = InfotainmentSessionGate()

        /** Keeps the command log: every request goes out through [transmit], which logs it with its reason (ADR-0008). */
        val commands = CommandRecorder(bleName, historyStore, rssi = { latestRssi(address) })

        /** Whether an Infotainment read is in flight, for the spinner; every exit clears it. */
        private val readInFlight = ReadInFlight { progress -> updateConnection(address) { it.copy(readProgress = progress) } }

        /** The reason of the read that is waiting for the Infotainment handshake. */
        private var waitingReadReason = Command.Reason.REASON_UNSPECIFIED

        /**
         * When the app last read on coming to the foreground; reopening the screen sooner than
         * [UI_READ_MIN_INTERVAL_MS] later reads nothing.
         */
        private var lastUiReadAtMs = 0L

        /** Logs and feeds the policy with the drive, closures and climate replies (ADR-0008, ADR-0009). */
        private val stateReplies =
            InfotainmentStateReplies(
                vehicleId = bleName,
                historyStore = historyStore,
                policy = infotainmentPolicy,
                rssi = { latestRssi(address) },
                log = { log("${name()}: $it") },
            )

        /** The status reply has not come yet, so the key slot lookup and the session request still wait. */
        private var startupPending = false

        private val startupFollowUps = Runnable { runStartupFollowUps() }

        /**
         * Asks for the status first and sends the key slot lookup and the session request only after
         * its reply, so one VCSEC request is in flight at a time (docs/protocol/capabilities.md,
         * Constraints → Polling). A timer sends them anyway if the reply never comes.
         */
        fun beginStartup() {
            startupPending = true
            requestStatus()
            handler.removeCallbacks(startupFollowUps)
            handler.postDelayed(startupFollowUps, STARTUP_FOLLOW_UP_MS)
        }

        private fun runStartupFollowUps() {
            if (!startupPending) return
            startupPending = false
            handler.removeCallbacks(startupFollowUps)
            // The session request goes first; the key slot lookup waits for it to be answered so no
            // two VCSEC requests are in flight together (see onEstablished and the poll).
            if (startSession()) {
                startupSlotLookupPending = true
            } else {
                pairing.requestKeySlot()
            }
        }

        /** The key slot lookup still waiting for the VCSEC session request to be answered. */
        private var startupSlotLookupPending = false

        private fun runStartupSlotLookup() {
            if (!startupSlotLookupPending) return
            startupSlotLookupPending = false
            pairing.requestKeySlot()
        }

        val poll =
            object : Runnable {
                override fun run() {
                    if (phase != ConnectionPhase.READY) return
                    transport?.readRssi()
                    requestStatus()
                    commands.logUnanswered()
                    // Once the VCSEC session exists the lookup is no longer waiting on anything; until then it stays
                    // behind the session request (a handshake that gives up releases it).
                    if (handshake.has(Domain.DOMAIN_VEHICLE_SECURITY)) runStartupSlotLookup()
                    expirePendingCommands(System.currentTimeMillis())
                    readInFlight.expire(System.currentTimeMillis())
                    handler.postDelayed(this, POLL_MS)
                }
            }

        val rssiPoll =
            object : Runnable {
                override fun run() {
                    if (!uiVisible || phase != ConnectionPhase.READY) return
                    transport?.readRssi()
                    handler.postDelayed(this, RSSI_POLL_MS)
                }
            }

        val reconnect =
            object : Runnable {
                override fun run() {
                    if (!_state.value.trackingEnabled) return
                    if (phase == ConnectionPhase.READY || phase == ConnectionPhase.CONNECTING) return
                    log("${name()}: reconnecting (attempt ${reconnectAttempts + 1})")
                    connect()
                }
            }

        val wakeRefresh =
            object : Runnable {
                override fun run() {
                    if (_state.value.connections[address]
                            ?.status
                            ?.asleep == false
                    ) {
                        return
                    }
                    if (wakeRefreshAttempts++ >= WAKE_REFRESH_MAX_ATTEMPTS) {
                        log("${name()}: wake not confirmed; the car may be out of range")
                        quickActionSender.failQueued(wakeToo = true)
                        return
                    }
                    requestStatus()
                    handler.postDelayed(this, WAKE_REFRESH_MS)
                }
            }

        private val sessionRetry = Runnable { handshake.onRetryTimer() }

        private val pairingTimers: Map<PairingTimer, Runnable> =
            PairingTimer.entries.associateWith { timer -> Runnable { pairing.onTimer(timer) } }

        private val pairingHost: PairingHost =
            object : PairingHost {
                override val isReady: Boolean get() = phase == ConnectionPhase.READY

                override fun log(message: String): Unit = this@VehicleLink.log("${name()}: $message")

                override fun transmitVcsec(
                    request: ByteArray,
                    reason: PairingReason,
                ) {
                    this@VehicleLink.transmitVcsec(request, CommandRecords.reasonOf(reason))
                }

                override fun transmitAddKey(request: ByteArray): Boolean =
                    transmit(request, CommandRecords.addKey(Command.Reason.PAIRING, request))

                override fun publishPairing(
                    phase: PairingPhase,
                    keyId: String?,
                ) {
                    updateConnection(address) { it.copy(pairing = phase, pairingKeyId = keyId) }
                }

                override fun publishKeySlot(slot: Int?) {
                    updateConnection(address) { it.copy(keySlot = slot) }
                    updateVehicle(bleName) { it.copy(keySlot = slot) }
                }

                override fun hasConnectionKeySlot(): Boolean = _state.value.connections[address]?.keySlot != null

                override fun hasShownKeySlot(): Boolean = hasConnectionKeySlot() || vehicles[bleName]?.keySlot != null

                override fun onKeyConfirmed() {
                    rememberVehicle(address)
                    startSession()
                }

                override fun onFreshlyEnrolled() {
                    infotainmentPolicy.onFreshStart(System.currentTimeMillis())
                }

                override fun schedule(
                    timer: PairingTimer,
                    delayMs: Long,
                ) {
                    handler.removeCallbacks(pairingTimers.getValue(timer))
                    handler.postDelayed(pairingTimers.getValue(timer), delayMs)
                }

                override fun cancel(timer: PairingTimer) {
                    handler.removeCallbacks(pairingTimers.getValue(timer))
                }
            }

        /** Enrolls this phone's key and finds its slot; the state machine lives in core. */
        val pairing: PairingFlow =
            PairingFlow(
                keys = StoredPairingKeys(keyStore, bleName),
                host = pairingHost,
            )

        private val sessionHost: SessionHost =
            object : SessionHost {
                override val isReady: Boolean get() = phase == ConnectionPhase.READY
                override val isAwake: Boolean get() = this@VehicleLink.isAwake()
                override val vin: String? get() = this@VehicleLink.vin().takeIf { it.length == Vehicle.VIN_LENGTH }

                override fun log(message: String) {
                    this@VehicleLink.log("${name()}: $message")
                }

                override fun transmitSessionRequest(request: ByteArray): Boolean =
                    transmit(request, CommandRecords.sessionInfo(Command.Reason.SESSION_HANDSHAKE, request))

                override fun publishSessions(domains: List<String>) {
                    updateConnection(address) { it.copy(sessions = domains) }
                }

                override fun mayRequestInfotainment(awake: Boolean): Boolean =
                    (quickActionSender.hasPending && awake) || infotainmentGate.mayRequestSession(awake)

                override fun onEstablished(domain: Domain) {
                    rememberVehicle(address)
                    // A fresh session starts with a clean count of resyncs.
                    resyncsWithoutReply = 0
                    if (domain == Domain.DOMAIN_VEHICLE_SECURITY) runStartupSlotLookup()
                    if (domain == Domain.DOMAIN_INFOTAINMENT) {
                        // A quick action goes first; the read that was waiting for this session follows its reply.
                        if (quickActionSender.hasPending) quickActionSender.sendPending() else resumeWaitingRead()
                    }
                    if (domain == Domain.DOMAIN_VEHICLE_SECURITY && wakeAfterSession) {
                        wakeAfterSession = false
                        sendWake()
                    }
                }

                override fun onGaveUp() {
                    infotainmentGate.onGaveUp()
                    quickActionSender.failQueued(wakeToo = wakeAfterSession)
                    clearWake()
                    runStartupSlotLookup()
                }

                override fun onKeyUnusable() {
                    pairing.forgetEnrollment("the stored key can't be used on this phone")
                }

                override fun scheduleRetry(delayMs: Long) {
                    handler.postDelayed(sessionRetry, delayMs)
                }

                override fun cancelRetry() {
                    handler.removeCallbacks(sessionRetry)
                }
            }

        /** Asks the car for sessions and keeps them; the handshake lives in core. */
        private val handshake: SessionHandshake =
            SessionHandshake(
                keys = StoredSessionKeys(keyStore, bleName),
                host = sessionHost,
                clientAddress = clientAddress,
            )

        fun name(): String {
            val connection = _state.value.connections[address]
            return connection?.gattDeviceName ?: connection?.name ?: bleName
        }

        fun gattName(): String? = _state.value.connections[address]?.gattDeviceName

        fun vin(): String = vehicles[bleName]?.vin.orEmpty()

        private fun log(message: String) {
            this@TeslaBleController.appendLog(bleName, message)
        }

        fun connect() {
            if (address.isEmpty()) return
            if (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.READY) return
            if (!hasBlePermissions(appContext)) {
                // Android 12+ throws a SecurityException from connectGatt() when
                // BLUETOOTH_CONNECT is missing; fail quietly instead.
                log("${name()}: Bluetooth permissions are missing; grant them to connect")
                return
            }
            cancelCallbacks()
            transport?.close()
            transport = null
            nextRetryAtMs = 0
            ensureConnectionEntry()
            phase = ConnectionPhase.CONNECTING
            updateConnection(address) { it.copy(phase = ConnectionPhase.CONNECTING) }
            val client = createTransport(address, listener())
            transport = client
            client.connect(address)
        }

        /** Connects now with a fresh backoff, dropping a connect attempt that can no longer complete. */
        fun retryNow() {
            reconnectAttempts = 0
            if (phase == ConnectionPhase.CONNECTING) phase = ConnectionPhase.DISCONNECTED
            log("${name()}: Bluetooth is back on, reconnecting")
            connect()
        }

        fun close() {
            recordConnection(this, ConnectionEvent.State.DISCONNECTED)
            cancelCallbacks()
            transport?.close()
            transport = null
            phase = ConnectionPhase.IDLE
            handshake.clear()
            pendingCommands.clear()
            pairing.onLinkClosed()
            infotainmentGate.onLinkDropped()
            readInFlight.onLinkDropped()
            clearWake()
            quickActionSender.failQueued(wakeToo = false)
            quickActionSender.clear()
            updateConnection(address) {
                it.copy(
                    phase = ConnectionPhase.IDLE,
                    sessions = emptyList(),
                    disconnectedAtMillis = System.currentTimeMillis(),
                )
            }
        }

        /** Drops the cached key slot and sessions after the pairing cache was cleared. */
        fun clearCachedPairing() {
            handshake.clear()
            pendingCommands.clear()
            readInFlight.onLinkDropped()
            pairing.onCacheCleared()
            clearWake()
            quickActionSender.clear()
            updateConnection(address) {
                it.copy(
                    keySlot = null,
                    sessions = emptyList(),
                    pairing = PairingPhase.IDLE,
                    pairingKeyId = null,
                )
            }
        }

        fun postPoll() {
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, POLL_MS)
        }

        fun startRssiPoll() {
            handler.removeCallbacks(rssiPoll)
            handler.post(rssiPoll)
        }

        fun stopRssiPoll() {
            handler.removeCallbacks(rssiPoll)
        }

        private fun cancelCallbacks() {
            handler.removeCallbacks(poll)
            handler.removeCallbacks(rssiPoll)
            handler.removeCallbacks(reconnect)
            handler.removeCallbacks(wakeRefresh)
            handler.removeCallbacks(startupFollowUps)
            startupPending = false
            startupSlotLookupPending = false
            handshake.cancelTimer()
            pairing.cancelTimers()
        }

        private fun scheduleReconnect() {
            if (!_state.value.trackingEnabled) return
            reconnectAttempts++
            if (reconnectAttempts >= DISCOVERY_AFTER_ATTEMPTS) {
                // Direct connects keep failing; rediscover in case the car
                // stopped advertising or came back at a new address.
                startDiscovery()
            }
            val now = System.currentTimeMillis()
            val unseenFor = ReconnectBackoff.unseenForMs(now, vehicles[bleName]?.lastSeenMillis ?: 0L, lastAliveMillis)
            val delay = ReconnectBackoff.delayMs(reconnectAttempts, unseenFor)
            nextRetryAtMs = System.currentTimeMillis() + delay
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, delay)
        }

        /**
         * Closes a link that only connected to read the car's name during a scan, so a car
         * nobody opened does not stay connected. Returns whether it did.
         */
        fun endNameProbe(): Boolean {
            if (bleName !in nameProbes || selectedBleName == bleName || vehicles.containsKey(bleName)) return false
            close()
            return true
        }

        /** Brings a pending retry forward to the shortest wait and starts the backoff over. */
        fun resetBackoff() {
            if (reconnectAttempts == 0 || phase == ConnectionPhase.READY) return
            // Adverts arrive every few seconds; an attempt already that close is left to run.
            if (nextRetryAtMs - System.currentTimeMillis() <= ReconnectBackoff.FIRST_DELAY_MS) return
            reconnectAttempts = 0
            nextRetryAtMs = System.currentTimeMillis() + ReconnectBackoff.FIRST_DELAY_MS
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, ReconnectBackoff.FIRST_DELAY_MS)
        }

        private fun ensureConnectionEntry() {
            _state.update { state ->
                if (state.connections.containsKey(address)) {
                    state
                } else {
                    state.copy(
                        connections =
                            state.connections + (
                                address to TeslaConnection(address = address, name = bleName)
                            ),
                    )
                }
            }
        }

        // ------------------------------------------------------------ protocol

        /** The routine VCSEC status poll: the one request the command log leaves out, the status records imply it. */
        fun requestStatus() {
            if (!transmit(TeslaVcsec.buildStatusRequest(), command = null)) {
                log("${name()}: failed to send VCSEC status request")
            }
        }

        /**
         * The one place every request to the car goes out. [command] is what the command log keeps, with
         * its reason; null only for the routine status poll. It is logged once the transport took it.
         */
        private fun transmit(
            bytes: ByteArray,
            command: Command?,
            awaitingKey: String? = null,
        ): Boolean = (transport?.send(bytes) == true).also { if (it && command != null) commands.sent(command, awaitingKey) }

        /** A VCSEC information request (whitelist or key slot), [bytes] as `TeslaVcsec` builds it. */
        private fun transmitVcsec(
            bytes: ByteArray,
            reason: Command.Reason,
        ) = transmit(bytes, CommandRecords.vcsecRoutable(reason, bytes))

        private fun isAwake(): Boolean =
            _state.value.connections[address]
                ?.status
                ?.asleep == false

        /** True when session requests went out. */
        fun startSession(): Boolean = handshake.start()

        fun sendQuickAction(action: QuickAction) = quickActionSender.send(action)

        /** The read that waited for the Infotainment session runs after an action, or once the session arrives. */
        private fun resumeWaitingRead() {
            if (infotainmentGate.onSessionEstablished()) requestChargeState(waitingReadReason)
        }

        private fun clearWake() {
            wakeAfterSession = false
            wakeFromStack = false
        }

        /** Wakes the car; [fromStack] says the quick-action stack asked, so only its own snackbar reports the send. */
        fun wake(fromStack: Boolean = false) {
            if (_state.value.connections[address]
                    ?.status
                    ?.asleep == false
            ) {
                log("${name()}: car is already awake")
                return
            }
            wakeRefreshAttempts = 0
            handler.removeCallbacks(wakeRefresh)
            handler.postDelayed(wakeRefresh, WAKE_REFRESH_MS)
            // A long-sleeping car rotates its session; a command encrypted with
            // the old one is dropped without a reply, so handshake again first.
            if (handshake.has(Domain.DOMAIN_VEHICLE_SECURITY)) {
                handshake.drop(Domain.DOMAIN_VEHICLE_SECURITY)
                handshake.publish()
            }
            wakeAfterSession = true
            wakeFromStack = fromStack
            log("${name()}: waking with a fresh session")
            startSession()
        }

        private fun sendWake() {
            // A refresh or a notification wake has no stack snackbar to report to; the car screen's own
            // "Waking…" pill already covers it.
            val fromStack = wakeFromStack
            wakeFromStack = false
            if (!handshake.has(Domain.DOMAIN_VEHICLE_SECURITY)) {
                log("${name()}: no VCSEC session to wake with")
                return
            }
            val request = TeslaCommands.buildWakeRequest()
            val sent =
                sendAuthenticated(Domain.DOMAIN_VEHICLE_SECURITY, request, CommandKind.WAKE, Command.Reason.USER_WAKE) != null
            log("${name()}: " + if (sent) "wake requested" else "failed to send wake request")
            if (fromStack) {
                val outcome = if (sent) QuickActionOutcome.SENT else QuickActionOutcome.FAILED
                publishActionFeedback(QuickActionFeedback(QuickAction.WAKE, outcome))
            }
        }

        /**
         * Reads the charge state, the first of a read's four requests. [reason] says why; the refresh
         * button ([Command.Reason.USER_REFRESH]) is sent at once and counted by the policy.
         */
        fun requestChargeState(reason: Command.Reason = CommandRecords.reasonOf(infotainmentPolicy.lastReadReason)) {
            if (reason == Command.Reason.USER_REFRESH) infotainmentPolicy.onUserRequest(System.currentTimeMillis())
            val now = System.currentTimeMillis()
            when (infotainmentGate.onReadDue(isAwake(), handshake.has(Domain.DOMAIN_INFOTAINMENT))) {
                InfotainmentSessionGate.Action.SKIP -> {
                    log("${name()}: car is asleep; wake it first")
                    readInFlight.onSkipped()
                }

                InfotainmentSessionGate.Action.SEND_READ -> {
                    // The read that waited for this session counts the handshake as done; any other starts here.
                    if (readInFlight.waitingForSession) {
                        readInFlight.sessionReady(now)
                    } else {
                        readInFlight.begin(now, infotainmentPolicy.expectedReplies(now), afterHandshake = false)
                    }
                    sendAuthenticated(
                        domain = Domain.DOMAIN_INFOTAINMENT,
                        payload = TeslaCommands.buildChargeStateRequest(),
                        kind = CommandKind.CHARGE,
                        reason = reason,
                    )
                }

                // The retry loop is already running; the read goes out when the session arrives.
                InfotainmentSessionGate.Action.WAIT_FOR_HANDSHAKE -> {
                    waitingReadReason = reason
                    readInFlight.begin(now, infotainmentPolicy.expectedReplies(now), afterHandshake = true)
                }

                InfotainmentSessionGate.Action.START_HANDSHAKE -> {
                    waitingReadReason = reason
                    log("${name()}: requesting Infotainment session for SOC")
                    readInFlight.begin(now, infotainmentPolicy.expectedReplies(now), afterHandshake = true)
                    if (!startSession()) {
                        infotainmentGate.onNotStartable()
                        readInFlight.onSkipped()
                    }
                }
            }
            syncPauseState()
        }

        /**
         * One read when the app comes to the foreground: the poll may be waiting out its idle
         * safety interval, and the owner opening the screen is the clearest sign they want today's
         * numbers. It counts as a user request, so it starts the hold and the pill leaves Paused; at
         * most one a minute, so a rotation or a quick look at another screen does not read again.
         */
        fun readForUi() {
            if (!isAwake()) return
            val now = System.currentTimeMillis()
            if (now - lastUiReadAtMs < UI_READ_MIN_INTERVAL_MS) return
            lastUiReadAtMs = now
            requestChargeState(Command.Reason.USER_REFRESH)
        }

        /**
         * Publishes the poll's pause for the status pill (ADR-0009): when the idle safety read is
         * due, or null while reads run every ten seconds. Nothing is written while it does not move,
         * so the every-10-s status tick does not churn the UI state.
         */
        private fun syncPauseState() {
            val pausedUntil = infotainmentPolicy.pausedUntilMillis(System.currentTimeMillis())
            if (_state.value.connections[address]?.pausedUntilMillis == pausedUntil) return
            updateConnection(address) { it.copy(pausedUntilMillis = pausedUntil) }
        }

        /**
         * Asks for the next state category after the previous reply (drive
         * after charge, closures after drive, climate after closures, then location while
         * driving and tire pressure when due), only
         * while the car is awake and an Infotainment session already exists:
         * it never starts a session or wakes the car.
         */
        private fun requestFollowUp(
            payload: ByteArray,
            kind: CommandKind,
        ) {
            if (!isAwake() || !handshake.has(Domain.DOMAIN_INFOTAINMENT)) return
            sendAuthenticated(domain = Domain.DOMAIN_INFOTAINMENT, payload = payload, kind = kind)
        }

        /** Sends an authenticated request; returns its request uuid, or null when nothing went out. */
        private fun sendAuthenticated(
            domain: Domain,
            payload: ByteArray,
            kind: CommandKind,
            reason: Command.Reason = Command.Reason.FOLLOW_UP,
        ): String? {
            val uuid = TeslaCrypto.randomBytes(16)
            val session = handshake[domain]
            val routing =
                if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
                    TeslaCrypto.randomBytes(16)
                } else {
                    clientAddress
                }
            val message = TeslaSessionRequests.buildAuthenticatedRequest(domain, payload, routing, uuid)
            val encrypted = session?.encrypt(message, COMMAND_EXPIRES_SECONDS)
            val requestId = encrypted?.let { session.requestId(it) }
            if (session == null || encrypted == null || requestId == null) {
                log("${name()}: cannot authenticate the ${kind.name.lowercase()} request")
                return null
            }
            val key = uuid.toHexString()
            pendingCommands[key] = PendingCommand(domain, requestId, kind, reason)
            val sent = transmit(encrypted.encode(), CommandRecords.authenticated(reason, domain, payload), key)
            if (!sent) log("${name()}: failed to send ${kind.name.lowercase()} request")
            if (sent && kind.isRead) readInFlight.onSent(System.currentTimeMillis())
            return if (sent) key else null
        }

        /** Applies the session info piggybacked on a rejected command; false when there is none or it does not verify. */
        private fun resyncSession(
            domain: Domain,
            message: RoutableMessage,
        ): Boolean {
            // A streak of resyncs with no accepted reply between them means the car's info never
            // settles; a full handshake recovers from that.
            if (resyncsWithoutReply >= MAX_RESYNCS_WITHOUT_REPLY) return false
            val info = message.session_info?.toByteArray()
            val tag =
                message.signature_data
                    ?.session_info_tag
                    ?.tag
                    ?.toByteArray()
            val resynced = info != null && tag != null && handshake.resync(domain, message.request_uuid.toByteArray(), info, tag)
            if (resynced) resyncsWithoutReply++
            return resynced
        }

        /** Forgets requests the car never answered, so they do not pile up for the life of the link. */
        private fun expirePendingCommands(now: Long) {
            val cutoff = now - PENDING_COMMAND_TTL_MS
            val requests = pendingCommands.entries.iterator()
            while (requests.hasNext()) {
                val request = requests.next()
                if (request.value.sentAtMillis >= cutoff) continue
                requests.remove()
                // A quick action whose reply never came reports a failure instead of going quiet.
                if (request.value.kind == CommandKind.ACTUATION) quickActionSender.onExpired(request.key)
            }
        }

        /** Drops the [domain] session and handshakes again, at most once a minute so a broken link cannot loop. */
        private fun rehandshake(
            domain: Domain,
            why: String,
        ) {
            val now = System.currentTimeMillis()
            if (now - lastRehandshakeMs <= REHANDSHAKE_COOLDOWN_MS) return
            lastRehandshakeMs = now
            decryptFailures = 0
            log("${name()}: re-handshaking ${domain.name} after $why")
            handshake.drop(domain)
            startSession()
        }

        private fun handleEncryptedResponse(
            bytes: ByteArray,
            deviceTimestamp: Instant,
        ): Boolean {
            val message =
                runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
                    ?: return false
            // A rejected message may carry a fault and no payload at all (busy, bad signature, ...).
            val fault = message.signedMessageStatus?.takeIf { it.isRefusal }
            val encrypted = message.signature_data?.AES_GCM_Response_data != null
            if (!encrypted && fault == null) return false
            val key = message.request_uuid.toByteArray().toHexString()
            val pending = pendingCommands.remove(key) ?: return false
            commands.replied(key, fault, deviceTimestamp)
            // A reply ends the read unless it sends the next request, which turns it back on.
            if (pending.kind.isRead) readInFlight.onReply()
            if (!encrypted) {
                log("${name()}: ${pending.kind.name.lowercase()} rejected (${fault?.signed_message_fault?.name})")
                if (pending.kind == CommandKind.ACTUATION) quickActionSender.failSent(key)
                // Retrying on the old session fails forever. The car usually attaches fresh session
                // info to such a rejection; when it verifies, the session resyncs in place, and
                // only otherwise does a new handshake recover.
                when (fault?.recovery) {
                    FaultRecovery.RESYNC_SESSION ->
                        if (!resyncSession(pending.domain, message)) rehandshake(pending.domain, "stale session")

                    // The owner removed this phone's key: no session recovers from that.
                    FaultRecovery.PAIR_AGAIN -> pairing.forgetEnrollment("the car does not know this phone's key")

                    FaultRecovery.NONE, null -> Unit
                }
                return true
            }
            val session = handshake[pending.domain] ?: return true
            val plaintext = session.decrypt(message, pending.requestId, pending.window)
            if (plaintext == null) {
                decryptFailures++
                log("${name()}: response decryption failed (${pending.kind.name.lowercase()})")
                // The car may have rotated its session (reboot, deep sleep).
                if (decryptFailures >= 2) rehandshake(pending.domain, "repeated decryption failures")
                return true
            }
            decryptFailures = 0
            resyncsWithoutReply = 0
            CommandRecords.refusalIn(pending.reason, pending.domain, plaintext)?.let { commands.result(it, deviceTimestamp) }
            when (pending.kind) {
                CommandKind.WAKE -> {
                    val status = runCatching { TeslaVcsec.parseCommandStatus(plaintext) }.getOrNull()
                    log("${name()}: wake ${status?.name ?: "response received"}")
                }

                CommandKind.CHARGE -> handleChargeResponse(plaintext, deviceTimestamp)

                CommandKind.DRIVE -> {
                    stateReplies.onDrive(plaintext, deviceTimestamp)
                    requestFollowUp(TeslaCommands.buildClosuresStateRequest(), CommandKind.CLOSURES)
                }

                CommandKind.CLOSURES -> {
                    stateReplies.onClosures(plaintext, deviceTimestamp)
                    requestFollowUp(TeslaCommands.buildClimateStateRequest(), CommandKind.CLIMATE)
                }

                CommandKind.CLIMATE, CommandKind.LOCATION, CommandKind.TIRE_PRESSURE ->
                    handleLaterRead(pending.kind, plaintext, deviceTimestamp)

                CommandKind.ACTUATION -> quickActionSender.onReply(plaintext, key)
            }
            return true
        }

        /** The climate, location and tire pressure replies: each hands over to the next read that is wanted. */
        private fun handleLaterRead(
            kind: CommandKind,
            plaintext: ByteArray,
            deviceTimestamp: Instant,
        ) {
            when (kind) {
                CommandKind.CLIMATE -> {
                    stateReplies.onClimate(plaintext, deviceTimestamp)
                    val now = System.currentTimeMillis()
                    if (infotainmentPolicy.wantsLocation(now)) {
                        infotainmentPolicy.onLocationRequested(now)
                        requestFollowUp(TeslaLocationTireCommands.buildLocationStateRequest(), CommandKind.LOCATION)
                    } else {
                        requestTirePressureIfDue()
                    }
                }

                CommandKind.LOCATION -> {
                    stateReplies.onLocation(plaintext, deviceTimestamp)
                    requestTirePressureIfDue()
                }

                else -> stateReplies.onTirePressure(plaintext, deviceTimestamp)
            }
        }

        /** The tire pressure read, when [InfotainmentPollPolicy.wantsTirePressure] says it is due. */
        private fun requestTirePressureIfDue() {
            val now = System.currentTimeMillis()
            if (!infotainmentPolicy.wantsTirePressure(now)) return
            infotainmentPolicy.onTirePressureRequested(now)
            requestFollowUp(TeslaLocationTireCommands.buildTirePressureStateRequest(), CommandKind.TIRE_PRESSURE)
        }

        private fun handleChargeResponse(
            plaintext: ByteArray,
            deviceTimestamp: Instant,
        ) {
            val charge = runCatching { TeslaCommands.parseChargeState(plaintext) }.getOrNull()
            if (charge == null) {
                val status = runCatching { TeslaCommands.parseActionStatus(plaintext) }.getOrNull()
                log("${name()}: charge response missing data" + (status?.let { " ($it)" } ?: ""))
                return
            }
            val previous = _state.value.connections[address]?.charge
            updateConnection(address) {
                it.copy(charge = charge, chargeAtMillis = deviceTimestamp.toEpochMilli())
            }
            historyStore.record(bleName, charge, deviceTimestamp, latestRssi(address))
            infotainmentPolicy.onChargeReading(charge.chargingStateKind)
            if (previous?.battery_level != charge.battery_level ||
                previous?.chargingStateKind != charge.chargingStateKind
            ) {
                log("${name()}: SOC ${charge.battery_level}% (${charge.chargingStateKind?.let(::chargingStateText) ?: "unknown"})")
            }
            requestFollowUp(TeslaCommands.buildDriveStateRequest(), CommandKind.DRIVE)
        }

        fun onMessage(message: ByteArray) {
            // The phone's clock when this reply arrived, read once: both BLE
            // logs keep it beside the car's reply (ADR-0008). Whole milliseconds,
            // as stored before; Instant.now() can carry finer digits.
            val deviceTimestamp = Instant.ofEpochMilli(System.currentTimeMillis())
            if (handshake.onMessage(message)) return
            // A read reply that sent no follow-up ends the read, however its handling ended. Follow-ups go
            // out on this same call (requestFollowUp -> sendAuthenticated -> onSent), so they are counted first.
            val handled =
                try {
                    handleEncryptedResponse(message, deviceTimestamp)
                } finally {
                    readInFlight.settle()
                }
            if (handled) return
            if (pairing.onMessage(message)) return

            val status = runCatching { TeslaVcsec.parseStatusResponse(message) }.getOrNull()
            if (status != null) {
                // VCSEC replies carry no time: the phone's clock at receipt is
                // the status log's timeline (ADR-0008).
                val rssi = latestRssi(address)
                historyStore.recordStatus(bleName, status, deviceTimestamp, rssi, firstAfterConnect = firstStatusAfterConnect)
                firstStatusAfterConnect = false
                if (!status.asleep) {
                    handler.removeCallbacks(wakeRefresh)
                }
                val previous = _state.value.connections[address]?.status
                updateConnection(address) { it.copy(status = status) }
                if (previous?.locked != status.locked ||
                    previous.asleep != status.asleep ||
                    previous.userPresent != status.userPresent
                ) {
                    log(
                        "${name()}: VCSEC status locked=${status.locked} " +
                            "asleep=${status.asleep} userPresent=${status.userPresent}",
                    )
                }
                // Track SOC for every awake car that can answer, not just the
                // selected one; skip cars with no VIN (no session). The policy
                // decides when a read is due so the car can fall asleep (ADR-0009).
                // A read can be attempted when a session exists or can be started on demand.
                val canRead = handshake.has(Domain.DOMAIN_INFOTAINMENT) || vin().length == Vehicle.VIN_LENGTH
                if (status.asleep) {
                    infotainmentGate.onAsleep()
                    handshake.dropPendingInfotainment()
                }
                if (infotainmentPolicy.onStatus(status, canRead, System.currentTimeMillis())) requestChargeState()
                syncPauseState()
                // A quick action queued on a sleeping car starts its session as soon as the car is up.
                quickActionSender.startIfAwake(status.asleep)
                // The status is in, so the car's awake state is known to the session request.
                runStartupFollowUps()
            } else {
                log("${name()}: RX ${message.size} bytes ${message.toHexString()}")
            }
        }

        private fun listener() =
            object : TeslaTransport.Listener {
                override fun onPhase(phase: ConnectionPhase) {
                    if (phase == ConnectionPhase.FAILED || phase == ConnectionPhase.DISCONNECTED) {
                        if (this@VehicleLink.phase == ConnectionPhase.READY) lastAliveMillis = System.currentTimeMillis()
                        recordConnection(this@VehicleLink, ConnectionEvent.State.DISCONNECTED)
                        transport?.close()
                        transport = null
                        handler.removeCallbacks(poll)
                        handler.removeCallbacks(rssiPoll)
                        infotainmentGate.onLinkDropped()
                        readInFlight.onLinkDropped()
                        quickActionSender.failQueued(wakeToo = false)
                        quickActionSender.clear()
                        handshake.clearPending()
                        val hadData =
                            _state.value.connections[address]?.let {
                                it.sessions.isNotEmpty() || it.status != null || it.charge != null
                            } == true
                        // Failed is for faults a retry will not fix; a dropped or weak link just reads as disconnected.
                        val display =
                            if (phase == ConnectionPhase.FAILED && !hadData) ConnectionPhase.FAILED else ConnectionPhase.DISCONNECTED
                        this@VehicleLink.phase = display
                        updateConnection(address) {
                            it.copy(phase = display, disconnectedAtMillis = System.currentTimeMillis(), pausedUntilMillis = null)
                        }
                        scheduleReconnect()
                        return
                    }
                    this@VehicleLink.phase = phase
                    updateConnection(address) {
                        it.copy(
                            phase = phase,
                            // A reconnect attempt keeps the original drop time; a fresh READY link clears it.
                            disconnectedAtMillis =
                                if (phase == ConnectionPhase.READY) null else it.disconnectedAtMillis,
                        )
                    }
                    if (phase == ConnectionPhase.READY && endNameProbe()) {
                        log("${name()}: name read; disconnecting until opened")
                    } else if (phase == ConnectionPhase.READY) {
                        reconnectAttempts = 0
                        nextRetryAtMs = 0
                        lastAliveMillis = System.currentTimeMillis()
                        firstStatusAfterConnect = true
                        infotainmentPolicy.onLinkReady(System.currentTimeMillis())
                        syncPauseState()
                        recordConnection(this@VehicleLink, ConnectionEvent.State.CONNECTED)
                        // Additive scans stay under the user's control; the selected
                        // car connecting is the one signal that means "found it".
                        if (_state.value.explicitScan && selectedBleName == bleName) stopScan()
                        if (selectedBleName == bleName) startRssiPoll()
                        beginStartup()
                        postPoll()
                        maybeStopDiscovery()
                    }
                }

                override fun onServices(services: List<GattServiceInfo>) {
                    updateConnection(address) { it.copy(services = services) }
                }

                override fun onGattDeviceName(name: String?) {
                    updateConnection(address) { it.copy(gattDeviceName = name) }
                }

                override fun onMtu(mtu: Int) {
                    updateConnection(address) { it.copy(mtu = mtu) }
                }

                override fun onRssi(rssi: Int) {
                    updateConnection(address) { it.copy(rssi = rssi) }
                }

                override fun onMessage(message: ByteArray) {
                    this@VehicleLink.onMessage(message)
                }

                override fun onLog(message: String) {
                    log("${name()}: $message")
                }
            }
    }

    private companion object {
        const val LOG_MAX_LINES = 200

        const val COMMAND_EXPIRES_SECONDS = 5
        const val POLL_MS = 10_000L

        /** Resyncs in a row, with no accepted reply between them, before a full handshake replaces them. */
        const val MAX_RESYNCS_WITHOUT_REPLY = 2

        /** How long the startup key slot lookup and session request wait for the status reply. */
        const val STARTUP_FOLLOW_UP_MS = 3_000L

        /** A request the car has not answered within twice its validity window never will be. */
        const val PENDING_COMMAND_TTL_MS = 2 * COMMAND_EXPIRES_SECONDS * 1000L
        const val RSSI_POLL_MS = 500L
        const val DEMO_SCAN_MS = 2_000L
        const val REHANDSHAKE_COOLDOWN_MS = 60_000L

        /** A foreground read at most this often: the owner reopening the app should not hammer the car. */
        const val UI_READ_MIN_INTERVAL_MS = 60_000L

        const val DISCOVERY_AFTER_ATTEMPTS = 3
        const val DISCOVERY_TIMEOUT_MS = 30_000L

        /** A scan stops once no new car has appeared for this long. */
        const val SCAN_QUIET_MS = 30_000L
        const val WAKE_REFRESH_MS = 5000L
        const val WAKE_REFRESH_MAX_ATTEMPTS = 6
        const val MAX_ACTIVE_LINKS = 3

        /** Weaker adverts (dBm) are too far for a connection to finish; a tap still reads the name. */
        const val NAME_PROBE_MIN_RSSI = -103
        const val PREFS = "teslable"
        const val KEY_TRACKING_ENABLED = "tracking_enabled"
        const val KEY_SELECTED_VEHICLE = "selected_vehicle"
        val ACTIVE_PHASES =
            setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.CONNECTED,
                ConnectionPhase.DISCOVERING,
                ConnectionPhase.READY,
            )
    }
}

/**
 * Location gates every scan (Android withholds beacon-style advertisements without it), but not a
 * connection to a stored address. A refused scan remembers why, so the cars list can ask, and says
 * so once in the log a shared log can explain a car that never shows up.
 */
private fun MutableStateFlow<BleUiState>.scanAllowed(
    context: Context,
    log: (String) -> Unit,
): Boolean {
    if (hasLocationPermission(context)) return true
    if (!value.locationNeeded) {
        update { it.copy(locationNeeded = true) }
        log("Scan blocked: location access is missing")
    }
    return false
}
