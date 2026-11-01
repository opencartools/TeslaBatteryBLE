// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.dzid26.teslable.ble.BleControllerHolder
import com.dzid26.teslable.ble.BleTrackingService
import com.dzid26.teslable.ble.PairingKeyStore
import com.dzid26.teslable.ble.TeslaBleController
import com.dzid26.teslable.ble.hasCompanionChooser
import com.dzid26.teslable.ble.hasLocationPermission
import com.dzid26.teslable.ble.shouldTrack
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.history.HistorySamples
import com.dzid26.teslable.ui.AboutScreen
import com.dzid26.teslable.ui.MainScreen
import com.dzid26.teslable.ui.PermissionWizardHost
import com.dzid26.teslable.ui.SettingsScreen
import com.dzid26.teslable.ui.TeslaBleTheme
import com.dzid26.teslable.ui.rememberCompanionScan

class MainActivity : ComponentActivity() {
    private lateinit var controller: TeslaBleController
    private var resumeCounter by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = BleControllerHolder.get(this)
        // A notification tap can ask for a specific car before the UI exists.
        intent
            ?.getStringExtra(BleTrackingService.EXTRA_BLE_NAME)
            ?.let(controller::openVehicleByBleName)
        setContent {
            TeslaBleTheme {
                val context = LocalContext.current

                // Reconnect to a paired car automatically when the app opens.
                LaunchedEffect(Unit) {
                    controller.ensureConnected()
                }

                // Poll RSSI fast only while the app is visible (started). Home or Recents stop
                // the activity, but the tracking service keeps the process alive, so tying this
                // to composition alone would leave the fast poll running in the background.
                LifecycleStartEffect(Unit) {
                    controller.setUiVisible(true)
                    onStopOrDispose { controller.setUiVisible(false) }
                }

                val state by controller.state.collectAsState()
                val batteryHistory by controller.batteryHistory.collectAsState()
                val driveHistory by controller.driveHistory.collectAsState()
                val statusHistory by controller.statusHistory.collectAsState()
                val activityHistory by controller.activityHistory.collectAsState()
                val latestRoutes by controller.latestRoutes.collectAsState()
                val trips by controller.trips.collectAsState()
                val chargeSessions by controller.chargeSessions.collectAsState()
                val historyLoaded by controller.historyLoaded.collectAsState()
                val logExportStatus by controller.logExport.status.collectAsState()
                val history =
                    remember(
                        batteryHistory,
                        driveHistory,
                        statusHistory,
                        activityHistory,
                        latestRoutes,
                        trips,
                        chargeSessions,
                        historyLoaded,
                    ) {
                        HistorySamples(
                            batteryHistory,
                            driveHistory,
                            statusHistory,
                            activityHistory,
                            latestRoutes,
                            trips,
                            chargeSessions,
                            historyLoaded,
                        )
                    }
                var screen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }
                // MainScreen leaves the composition while Settings is open; keep its
                // saved state (list vs. car view) so Back returns where the user was
                // instead of re-running the restore-last-car step.
                val screenStates = rememberSaveableStateHolder()

                val notificationPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) {
                        // Start tracking whether or not notifications were allowed: the
                        // foreground service runs either way, it is just silent without
                        // the permission.
                        BleTrackingService.start(context, AppState.StartReason.USER)
                    }

                // Once a car with an enrolled key is connected, hand off to the
                // foreground service so BLE keeps running with the app backgrounded.
                val trackingNeeded = state.shouldTrack()
                LaunchedEffect(trackingNeeded) {
                    if (!trackingNeeded || BleTrackingService.isRunning) return@LaunchedEffect
                    if (!hasNotificationPermission(context)) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        BleTrackingService.start(context, AppState.StartReason.USER)
                    }
                }

                // The system's companion-device chooser finds a car without location permission;
                // it is the fallback once Android will not show the location dialog again.
                var locationCardHidden by rememberSaveable { mutableStateOf(false) }
                val companionScan = rememberCompanionScan(controller) { locationCardHidden = true }

                PermissionWizardHost(
                    refreshKey = resumeCounter,
                    onGranted = { controller.ensureConnected() },
                ) { permissionsGranted, locationEnabled, requestPermissions, requestLocation ->
                    when (screen) {
                        AppScreen.SETTINGS ->
                            SettingsScreen(
                                keyStore = remember { PairingKeyStore(context) },
                                vehicles = state.vehicles,
                                onClearCarCache = controller::clearCarCache,
                                logExportStatus = logExportStatus,
                                onExportLogs = controller.logExport::start,
                                onOpenAbout = { screen = AppScreen.ABOUT },
                                onBack = { screen = AppScreen.MAIN },
                            )

                        AppScreen.ABOUT ->
                            AboutScreen(onBack = { screen = AppScreen.SETTINGS })

                        AppScreen.MAIN ->
                            screenStates.SaveableStateProvider(AppScreen.MAIN.name) {
                                MainScreen(
                                    state = state,
                                    history = history,
                                    permissionsGranted = permissionsGranted,
                                    locationServicesEnabled = locationEnabled,
                                    locationNeeded = state.locationNeeded && !locationCardHidden,
                                    onRequestPermissions = requestPermissions,
                                    onRequestLocation = { requestLocation(null) { controller.ensureConnected() } },
                                    onToggleScan = {
                                        when {
                                            state.scanning -> controller.stopScan()
                                            !permissionsGranted -> requestPermissions()
                                            hasLocationPermission(context) -> controller.startScan()
                                            else ->
                                                requestLocation(if (hasCompanionChooser(context)) companionScan else null) {
                                                    controller.startScan()
                                                }
                                        }
                                    },
                                    onToggleTracking = controller::setTrackingEnabled,
                                    onOpenVehicle = controller::openVehicle,
                                    onOpenRequestConsumed = controller::consumeOpenVehicleRequest,
                                    onPairKey = controller::pairKey,
                                    onSaveCar = controller::saveCar,
                                    onWake = { bleName -> controller.wakeVehicle(bleName) },
                                    onRefreshVehicle = { bleName, address -> controller.refreshVehicle(bleName, address) },
                                    onRequestVin = controller::requestVin,
                                    onDismissVin = controller::dismissVinRequest,
                                    onOpenSettings = { screen = AppScreen.SETTINGS },
                                )
                            }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent
            .getStringExtra(BleTrackingService.EXTRA_BLE_NAME)
            ?.let(controller::openVehicleByBleName)
    }

    override fun onResume() {
        super.onResume()
        // Bump the composition so permission and location state refresh after
        // the user returns from a system settings screen.
        resumeCounter++
        // Android forbids starting a foreground service from the background;
        // retry on resume so tracking comes up after a denied attempt.
        if (!BleTrackingService.isRunning && controller.shouldTrack()) {
            BleTrackingService.start(this, AppState.StartReason.USER)
        }
    }

    // The activity's start and stop mark the app coming to and leaving the foreground for the
    // app-state log (ADR-0008); it is the only activity. A rotation stops and restarts it
    // without the app ever leaving the screen, so that is not logged.
    override fun onStart() {
        super.onStart()
        controller.recordAppState(AppState.Event.APP_FOREGROUND)
    }

    override fun onStop() {
        if (!isChangingConfigurations) controller.recordAppState(AppState.Event.APP_BACKGROUND)
        super.onStop()
    }
}

/** Top-level surfaces; the car list is the default and the back destination. */
private enum class AppScreen {
    MAIN,
    SETTINGS,
    ABOUT,
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
