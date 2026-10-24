// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.CarDetails
import com.dzid26.teslable.ble.ConnectionLabel
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.LogEntry
import com.dzid26.teslable.ble.READ_SPINNER_MIN_VISIBLE_MS
import com.dzid26.teslable.ble.STALENESS_TICK_MS
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.ageLabel
import com.dzid26.teslable.ble.batteryPercent
import com.dzid26.teslable.ble.connectionDisplay
import com.dzid26.teslable.ble.connectionLabel
import com.dzid26.teslable.ble.spinnerHoldMs
import com.dzid26.teslable.ble.stalenessTickDelayMs
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.history.HistorySamples
import com.dzid26.teslable.protocol.asleep
import kotlinx.coroutines.delay

/**
 * Two surfaces, no tabs: the cars list, and the car you opened. The last car
 * opened is restored on launch; back is a quiet app-bar arrow plus system back.
 */
@Composable
fun MainScreen(
    state: BleUiState,
    history: HistorySamples,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onOpenVehicle: (String) -> Unit,
    onOpenRequestConsumed: () -> Unit,
    onPairKey: (String) -> Unit,
    onSaveCar: (String, CarDetails) -> Unit,
    onWake: (String?) -> Unit,
    onReadSoc: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var viewingBleName by rememberSaveable { mutableStateOf<String?>(null) }
    var vinTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var initialised by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialised) {
            initialised = true
            // The controller already restored the last opened car from disk;
            // without permissions the cars list is where the fallback button
            // lives, so start there instead of on the car view.
            viewingBleName = if (permissionsGranted) state.selectedBleName else null
        }
    }
    // A notification tap (or any external open request) switches to that car.
    LaunchedEffect(state.openVehicleRequest) {
        val requested = state.openVehicleRequest
        if (requested != null) {
            if (permissionsGranted) viewingBleName = requested
            onOpenRequestConsumed()
        }
    }

    val bleName = viewingBleName
    val vehicle = bleName?.let { name -> state.vehicles.firstOrNull { it.bleName == name } }
    val advert = bleName?.let { name -> state.devices.firstOrNull { it.name == name } }
    val address =
        vehicle?.address
            ?: advert?.address
            ?: bleName?.let { name ->
                state.connections.values
                    .firstOrNull { it.name == name }
                    ?.address
            }

    if (bleName != null && address != null) {
        CarScreen(
            state = state,
            vehicle = vehicle,
            advert = advert,
            address = address,
            history = history.battery,
            driveHistory = history.drive,
            statusHistory = history.status,
            activityHistory = history.activity,
            routes = history.routes,
            trips = history.trips,
            onBack = { viewingBleName = null },
            onEdit = { vinTarget = bleName },
            onPair = { onPairKey(address) },
            onRefresh = {
                // One pull does the right thing for the current state: reconnect,
                // wake the car, or read the battery.
                val connection = state.connections[address]
                when {
                    connection?.phase != ConnectionPhase.READY -> onOpenVehicle(address)
                    connection.status?.asleep != false -> onWake(null)
                    else -> onReadSoc()
                }
            },
            onOpenSettings = onOpenSettings,
            onToggleTracking = onToggleTracking,
        )
    } else {
        ConnectionsScreen(
            state = state,
            history = history.battery,
            permissionsGranted = permissionsGranted,
            locationServicesEnabled = locationServicesEnabled,
            onRequestPermissions = onRequestPermissions,
            onToggleScan = onToggleScan,
            onToggleTracking = onToggleTracking,
            onOpen = { name, rowAddress ->
                if (permissionsGranted) {
                    viewingBleName = name
                    onOpenVehicle(rowAddress)
                } else {
                    // Connecting without permissions would crash on Android 12+.
                    onRequestPermissions()
                }
            },
            onEditVin = { name -> vinTarget = name },
            onWake = onWake,
            onOpenSettings = onOpenSettings,
        )
    }

    CarEditor(
        state = state,
        target = vinTarget,
        onSaveCar = onSaveCar,
        onDismiss = { vinTarget = null },
    )
}

/** The car editor, opened from the cars list or the car screen; renders nothing unless a car is being edited. */
@Composable
private fun CarEditor(
    state: BleUiState,
    target: String?,
    onSaveCar: (String, CarDetails) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return
    val vehicle = state.vehicles.firstOrNull { it.bleName == target } ?: return
    CarDialog(
        bleName = target,
        vehicle = vehicle,
        onSave = { details ->
            onSaveCar(target, details)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

// ------------------------------------------------------------------ cars list

internal data class VehicleRow(
    val bleName: String,
    val address: String,
    val title: String,
    val vehicle: Vehicle?,
    val connection: TeslaConnection?,
    val advert: TeslaAdvert?,
    val lastKnown: BatterySample?,
)

private fun vehicleRows(
    state: BleUiState,
    history: List<BatterySample>,
): List<VehicleRow> {
    val known =
        state.vehicles
            .sortedBy { it.bleName }
            .map { vehicle ->
                VehicleRow(
                    bleName = vehicle.bleName,
                    address = vehicle.address,
                    title = vehicle.title,
                    vehicle = vehicle,
                    connection = state.connections[vehicle.address],
                    advert = state.devices.firstOrNull { it.name == vehicle.bleName },
                    lastKnown = history.lastOrNull { it.vehicleId == vehicle.bleName },
                )
            }
    val discovered =
        state.devices
            .filter { device -> state.vehicles.none { it.bleName == device.name } }
            .sortedBy { it.name }
            .map { device ->
                VehicleRow(
                    bleName = device.name,
                    address = device.address,
                    title = state.connections[device.address]?.gattDeviceName ?: device.name,
                    vehicle = null,
                    connection = state.connections[device.address],
                    advert = device,
                    lastKnown = null,
                )
            }
    return known + discovered
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionsScreen(
    state: BleUiState,
    history: List<BatterySample>,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onOpen: (String, String) -> Unit,
    onEditVin: (String) -> Unit,
    onWake: (String) -> Unit,
    onOpenSettings: () -> Unit,
    // Injectable for the Paparazzi snapshots; production call sites use the
    // default, which keeps the same per-row ticking clocks as before.
    nowMillis: Long? = null,
) {
    val rows = vehicleRows(state, history)
    // A fresh install has nothing to show, so start the scan it would ask for;
    // the global Enable toggle still gates it.
    LaunchedEffect(Unit) {
        if (rows.isEmpty() && permissionsGranted && state.trackingEnabled) {
            onToggleScan()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.main_title)) },
                actions = {
                    TrackingToggle(
                        checked = state.trackingEnabled,
                        onCheckedChange = onToggleTracking,
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.scanning,
            onRefresh = {
                if (!permissionsGranted) {
                    onRequestPermissions()
                } else if (!state.scanning) {
                    onToggleScan()
                }
            },
            state = pullState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            indicator = {
                // The pill is the manual-pull affordance only: it shows while
                // the finger is dragging and no scan is running. The status
                // row below carries the scan spinner, so a running scan never
                // shows two spinners.
                if (pullState.distanceFraction > 0f && !state.scanning) {
                    RefreshPill(
                        state = pullState,
                        isRefreshing = state.scanning,
                        pullLabel = stringResource(R.string.main_pull_scan),
                        refreshingLabel = null,
                    )
                }
            },
        ) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
            ) {
                // The log stays a fraction of the screen so the car list keeps
                // room in landscape; its content scrolls internally.
                val logMaxHeight = (maxHeight * LOG_HEIGHT_FRACTION).coerceAtLeast(96.dp)
                Column(modifier = Modifier.fillMaxSize()) {
                    SetupBanners(permissionsGranted, state.bluetoothOn, locationServicesEnabled, onRequestPermissions)

                    val connectedCount = state.connections.values.count { it.phase == ConnectionPhase.READY }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text =
                                if (rows.isNotEmpty()) {
                                    stringResource(R.string.main_car_summary, rows.size, connectedCount)
                                } else {
                                    stringResource(R.string.main_no_scan)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.scanning || state.discovering) {
                            BusySpinner(size = 18.dp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    VehicleList(
                        rows = rows,
                        scanning = state.scanning,
                        bluetoothOn = state.bluetoothOn,
                        onOpen = onOpen,
                        onEditVin = onEditVin,
                        onWake = onWake,
                        modifier = Modifier.weight(1f),
                        nowMillis = nowMillis,
                    )
                    Spacer(Modifier.height(8.dp))
                    LogCard(state.log, maxContentHeight = logMaxHeight)
                }
            }
        }
    }
}

/** The empty state or the car cards; the log sits pinned below this list. */
@Composable
private fun VehicleList(
    rows: List<VehicleRow>,
    scanning: Boolean,
    bluetoothOn: Boolean,
    onOpen: (String, String) -> Unit,
    onEditVin: (String) -> Unit,
    onWake: (String) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long? = null,
) {
    if (rows.isEmpty()) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = stringResource(if (scanning) R.string.main_looking else R.string.main_no_cars),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(if (scanning) R.string.main_keep_open else R.string.main_pull_down),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(rows, key = { it.bleName }) { row ->
                VehicleCard(
                    row = row,
                    onOpen = { onOpen(row.bleName, row.address) },
                    onEditVin = { onEditVin(row.bleName) },
                    onWake = { onWake(row.bleName) },
                    bluetoothOn = bluetoothOn,
                    nowMillis = nowMillis ?: rememberNowMillis(row.connection?.chargeAtMillis),
                )
            }
        }
    }
}

/** What stops scans from finding cars, above the list. Bluetooth can go off at any time, so it is not part of the wizard. */
@Composable
private fun SetupBanners(
    permissionsGranted: Boolean,
    bluetoothOn: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
) {
    if (!permissionsGranted) {
        PermissionCard(
            title = stringResource(R.string.main_permission_title),
            body = stringResource(R.string.main_permission_body),
            button = stringResource(R.string.main_permission_button),
            onClick = onRequestPermissions,
        )
        Spacer(Modifier.height(8.dp))
    } else if (!bluetoothOn) {
        BluetoothOffCard()
        Spacer(Modifier.height(8.dp))
    }
    if (!locationServicesEnabled) {
        Text(
            text = stringResource(R.string.main_location_off),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BluetoothOffCard() {
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    PermissionCard(
        title = stringResource(R.string.main_bluetooth_off_title),
        body = stringResource(R.string.main_bluetooth_off_body),
        button = stringResource(R.string.main_bluetooth_off_button),
        onClick = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
    )
}

/** A short, explicit prompt the wizard or a system settings screen backs up. */
@Composable
private fun PermissionCard(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) {
                Text(button)
            }
        }
    }
}

@Composable
internal fun VehicleCard(
    row: VehicleRow,
    onOpen: () -> Unit,
    onEditVin: () -> Unit,
    onWake: () -> Unit,
    bluetoothOn: Boolean = true,
    // Injectable for the Paparazzi snapshots; production call sites use the
    // default, which is the same ticking clock as before.
    nowMillis: Long = rememberNowMillis(row.connection?.chargeAtMillis),
    // Injectable for the Paparazzi snapshots; null keeps the timed spinner.
    spinnerOverride: Boolean? = null,
) {
    val display = connectionDisplay(row.connection, row.advert, vehicle = row.vehicle, bluetoothOn = bluetoothOn)
    val reading = batteryPercent(row.connection, row.lastKnown, nowMillis)
    val canWake =
        row.connection?.status?.asleep == true &&
            row.connection?.sessions?.contains("DOMAIN_VEHICLE_SECURITY") == true
    val vin = row.vehicle?.vin
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { if (row.vehicle != null) menuOpen = true },
                ),
    ) {
        Box {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(display.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = display.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(row.connection, bluetoothOn),
                    )
                    Text(
                        text = vin ?: row.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (vin != null) FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reading != null) {
                    // How old a stale reading is sits under the percentage.
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${reading.value}%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color =
                                if (reading.stale) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                        reading.ageLabel(nowMillis, row.connection)?.let { age ->
                            Text(
                                text = age,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    StatusPill(row.connection, bluetoothOn = bluetoothOn)
                }
            }
            // The top-right corner: a spinner while a read is in flight.
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReadingSpinner(row.connection, forceVisible = spinnerOverride)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tracking_wake)) },
                    enabled = canWake,
                    onClick = {
                        menuOpen = false
                        onWake()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (vin == null) R.string.vin_add else R.string.vin_edit)) },
                    onClick = {
                        menuOpen = false
                        onEditVin()
                    },
                )
            }
        }
    }
}

/** The app-wide log; every line is tagged with the car it came from. */
@Composable
private fun LogCard(
    log: List<LogEntry>,
    maxContentHeight: Dp = 200.dp,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (log.isEmpty()) {
                Text(
                    text = stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val logScroll = rememberScrollState()
                LaunchedEffect(logScroll.maxValue) {
                    logScroll.scrollTo(logScroll.maxValue)
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxContentHeight)
                            .verticalScroll(logScroll),
                ) {
                    log.takeLast(100).forEach { line ->
                        Text(
                            text = line.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Shared by the cars list and the car view, so the two app bars cannot drift. */
@Composable
internal fun TrackingToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, end = 4.dp),
    ) {
        Text(stringResource(R.string.enable_toggle), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Shared by the cars list and the car view. */
@Composable
internal fun StatusPill(
    connection: TeslaConnection?,
    onAsleepClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bluetoothOn: Boolean = true,
) {
    val label = connectionLabel(connection, bluetoothOn)
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when (label) {
            ConnectionLabel.AWAKE -> colors.primaryContainer to colors.onPrimaryContainer
            ConnectionLabel.ASLEEP -> colors.secondaryContainer to colors.onSecondaryContainer
            ConnectionLabel.FAILED, ConnectionLabel.BLUETOOTH_OFF -> colors.errorContainer to colors.onErrorContainer
            ConnectionLabel.DISCONNECTED, ConnectionLabel.CONNECTING, ConnectionLabel.CONNECTED ->
                colors.surfaceVariant to colors.onSurfaceVariant
        }
    val asleep = label == ConnectionLabel.ASLEEP
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
        modifier = if (asleep && onAsleepClick != null) modifier.clickable { onAsleepClick() } else modifier,
    ) {
        Text(
            text = label.pillText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The wall clock, re-read each time the screen starts and then every
 * [STALENESS_TICK_MS] while it shows, or sooner when the reading taken at
 * [readAtMillis] is about to turn stale ([stalenessTickDelayMs]). A reading's
 * age measured against it keeps growing on a quiet screen, where a clock read
 * once per composition would leave an old reading looking fresh until
 * something else redrew the screen.
 */
@Composable
internal fun rememberNowMillis(readAtMillis: Long? = null): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val nowMillis by produceState(System.currentTimeMillis(), lifecycle, readAtMillis) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(stalenessTickDelayMs(readAtMillis, value))
            }
        }
    }
    return nowMillis
}

/**
 * A small spinner while an Infotainment read is in flight on a connected car. Once it shows it
 * stays at least [READ_SPINNER_MIN_VISIBLE_MS], since a read takes well under a second.
 * [forceVisible] is a deterministic override for the Paparazzi snapshots: when non-null
 * the timing is skipped and the spinner shows exactly when true.
 */
@Composable
internal fun ReadingSpinner(
    connection: TeslaConnection?,
    forceVisible: Boolean? = null,
) {
    if (forceVisible != null) {
        if (forceVisible) {
            BusySpinner(size = 13.dp)
        }
        return
    }
    val inFlight = connection?.readInFlight == true && connection.phase == ConnectionPhase.READY
    var visible by remember { mutableStateOf(false) }
    val shownAt = remember { longArrayOf(0L) }
    LaunchedEffect(inFlight) {
        if (inFlight) {
            if (!visible) shownAt[0] = System.currentTimeMillis()
            visible = true
        } else if (visible) {
            // A read that starts again cancels this wait, and the spinner stays up.
            delay(spinnerHoldMs(shownAt[0], System.currentTimeMillis()))
            visible = false
        }
    }
    if (visible) {
        BusySpinner(size = 13.dp)
    }
}

@Composable
private fun statusColor(
    connection: TeslaConnection?,
    bluetoothOn: Boolean,
): Color =
    when (connectionLabel(connection, bluetoothOn)) {
        ConnectionLabel.AWAKE -> MaterialTheme.colorScheme.primary
        ConnectionLabel.FAILED, ConnectionLabel.BLUETOOTH_OFF -> MaterialTheme.colorScheme.error
        ConnectionLabel.DISCONNECTED, ConnectionLabel.CONNECTING, ConnectionLabel.CONNECTED, ConnectionLabel.ASLEEP ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }

/** The log card's share of the cars-list height; the list keeps the rest. */
private const val LOG_HEIGHT_FRACTION = 0.3f
