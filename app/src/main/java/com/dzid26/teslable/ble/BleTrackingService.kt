// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.MainActivity
import com.dzid26.teslable.R
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.history.statusDurations
import com.dzid26.teslable.protocol.asleep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Tesla BLE connections alive in the
 * background.
 *
 * The GATT connections and polling live in [TeslaBleController] (shared through
 * [BleControllerHolder]); this service promotes the process to foreground so
 * Android does not kill it while the screen is off, and mirrors each tracked
 * car into its own ongoing notification. With one car the foreground
 * notification is that car's; with several it becomes a summary and each car
 * gets its own grouped notification with its own Wake action. ADR-0001
 * milestone 5.
 */
class BleTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false
    private var lastSummary: NotificationModel? = null
    private val lastCarModels = mutableMapOf<String, NotificationModel>()
    private val postedCarIds = mutableMapOf<String, Int>()
    private var startLogged = false

    private val tripSource by lazy { TripNoticeSource() }

    /** Screen on and off only reach a receiver registered at run time; the app-state log keeps them (ADR-0008). */
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val event =
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> AppState.Event.SCREEN_ON
                        Intent.ACTION_SCREEN_OFF -> AppState.Event.SCREEN_OFF
                        else -> return
                    }
                BleControllerHolder.get(context).recordAppState(event)
            }
        }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val controller = BleControllerHolder.get(this)
        scope.launch {
            combine(
                controller.state,
                controller.batteryHistory,
                controller.statusHistory,
                controller.latestRoutes,
            ) { state, history, statuses, routes ->
                modelsFor(state, history, statuses, routes)
            }.collect { models ->
                publish(models)
            }
        }
        // The charge reading ages between state changes; re-evaluate when the
        // newest one is due to turn gray (sooner than the minute tick while it
        // is fresh) and every minute after, so ages and durations stay current.
        // publish() posts only what changed, so this is at most one post a minute.
        scope.launch {
            controller.state
                .map { state ->
                    state.connections.values
                        .mapNotNull { it.chargeAtMillis }
                        .maxOrNull()
                }.distinctUntilChanged()
                .collectLatest { newestRead ->
                    // A newer read restarts this loop, so the timing follows the latest reading.
                    while (true) {
                        delay(stalenessTickDelayMs(newestRead, System.currentTimeMillis()))
                        publish(currentModels(controller))
                    }
                }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Without Bluetooth permission the connected-device foreground type is refused on Android 14+.
        // start() never asks for the service in that state, so this is a sticky restart by the system
        // (which has no start-foreground deadline) after the permission was revoked.
        if (!foregroundStarted && !hasBlePermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val controller = BleControllerHolder.get(this)
        if (!startLogged) {
            startLogged = true
            controller.recordAppState(AppState.Event.TRACKING_STARTED, startReasonOf(intent))
            // The receiver only sees toggles; log the current state too, so it is known after a reboot, update or restart.
            val interactive = getSystemService(PowerManager::class.java)?.isInteractive
            if (interactive != null) {
                controller.recordAppState(if (interactive) AppState.Event.SCREEN_ON else AppState.Event.SCREEN_OFF)
            }
        }
        if (intent?.action == ACTION_WAKE) {
            controller.wakeVehicle(intent.getStringExtra(EXTRA_BLE_NAME))
        }
        publish(currentModels(controller))
        // START_STICKY may restart us after a process kill; find the cars again.
        controller.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(screenReceiver) }
        BleControllerHolder.get(this).recordAppState(AppState.Event.TRACKING_STOPPED)
        scope.cancel()
        // stopService removes only the foreground notification; the grouped per-car ones are ours to cancel.
        val manager = NotificationManagerCompat.from(this)
        postedCarIds.values.forEach(manager::cancel)
        postedCarIds.clear()
        lastCarModels.clear()
        manager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** One model per tracked car, in the controller's (last-seen) order. */
    private fun modelsFor(
        state: BleUiState,
        history: List<BatterySample>,
        statuses: List<StatusSample>,
        routes: Map<String, RouteSample>,
    ): List<NotificationModel> =
        state.vehicles.mapNotNull { vehicle ->
            val connection = state.connections[vehicle.address] ?: return@mapNotNull null
            if (connection.phase == ConnectionPhase.IDLE) return@mapNotNull null
            val advert = state.devices.firstOrNull { it.address == vehicle.address }
            val now = System.currentTimeMillis()
            val durations = statusDurations(statuses, vehicle.bleName, now)
            val display = connectionDisplay(connection, advert, vehicle = vehicle, durations = durations, bluetoothOn = state.bluetoothOn)
            val lastKnown = history.lastOrNull { it.vehicleId == vehicle.bleName }
            val reading = batteryPercent(connection, lastKnown, now)
            val trip = tripSource.noticeFor(vehicle.bleName, history, routes, now)
            NotificationModel(
                bleName = vehicle.bleName,
                title = display.title,
                // While navigating the signal strength is of no interest: it stays out of the text and never re-posts it.
                status = if (trip != null) display.stateText else display.status,
                stateText = display.stateText,
                rssi = if (trip != null) null else display.rssi,
                percent = reading?.value,
                percentStale = reading?.stale == true,
                // Only a stale reading shows its age; a fresh one stays null so the
                // notification is not re-posted while reads keep arriving.
                percentAgeLabel = reading?.ageLabel(now, connection),
                showWake =
                    connection.status?.asleep == true &&
                        connection.sessions.contains("DOMAIN_VEHICLE_SECURITY"),
                trip = trip,
            )
        }

    private fun currentModels(controller: TeslaBleController): List<NotificationModel> =
        modelsFor(controller.state.value, controller.batteryHistory.value, controller.statusHistory.value, controller.latestRoutes.value)

    private fun publish(models: List<NotificationModel>) {
        val summary = summaryModel(models)
        if (!foregroundStarted) {
            // A sticky restart after Bluetooth permission was revoked: Android 14+ refuses the
            // connected-device type without it, and a crash loop would be worse than no service.
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(summary, groupSummary = models.size > 1),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    } else {
                        0
                    },
                )
            } catch (ignored: SecurityException) {
                stopSelf()
                return
            } catch (ignored: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+).
                stopSelf()
                return
            }
            foregroundStarted = true
            lastSummary = summary
        } else if (shouldPost(lastSummary, summary)) {
            lastSummary = summary
            post(NOTIFICATION_ID, buildNotification(summary, groupSummary = models.size > 1))
        }
        syncCarNotifications(models)
    }

    /**
     * With one car the foreground notification is that car's; with several it
     * is a summary and every car gets a grouped notification of its own.
     */
    private fun summaryModel(models: List<NotificationModel>): NotificationModel =
        when {
            models.size == 1 -> models.first()

            models.isEmpty() ->
                NotificationModel(
                    bleName = null,
                    title = getString(R.string.app_name),
                    status = "No car connected",
                    stateText = "No car connected",
                    rssi = null,
                    percent = null,
                    percentStale = false,
                    percentAgeLabel = null,
                    showWake = false,
                )

            else ->
                NotificationModel(
                    bleName = null,
                    title = getString(R.string.app_name),
                    status = "Tracking ${models.size} cars",
                    stateText = "Tracking ${models.size} cars",
                    rssi = null,
                    percent = null,
                    percentStale = false,
                    percentAgeLabel = null,
                    showWake = false,
                )
        }

    private fun syncCarNotifications(models: List<NotificationModel>) {
        val wanted: Map<String, NotificationModel> =
            if (models.size > 1) {
                models.mapNotNull { model -> model.bleName?.let { it to model } }.toMap()
            } else {
                emptyMap()
            }
        // Cancel cars that went away (or collapsed back into the summary).
        for ((bleName, id) in postedCarIds.toMap()) {
            if (!wanted.containsKey(bleName)) {
                NotificationManagerCompat.from(this).cancel(id)
                postedCarIds.remove(bleName)
                lastCarModels.remove(bleName)
            }
        }
        for ((bleName, model) in wanted) {
            if (!shouldPost(lastCarModels[bleName], model)) continue
            lastCarModels[bleName] = model
            val id = postedCarIds.getOrPut(bleName) { carNotificationId(bleName) }
            post(id, buildNotification(model, groupSummary = false))
        }
    }

    private fun post(
        id: Int,
        notification: Notification,
    ) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            manager.notify(id, notification)
        }
    }

    /**
     * The UI polls RSSI fast; a notification only re-posts when the state
     * changes or the signal moves enough to matter.
     */
    private fun shouldPost(
        previous: NotificationModel?,
        current: NotificationModel,
    ): Boolean {
        if (previous == null) return true
        if (previous.title != current.title || previous.stateText != current.stateText) return true
        if (previous.percent != current.percent || previous.percentStale != current.percentStale) {
            return true
        }
        if (previous.percentAgeLabel != current.percentAgeLabel) return true
        if (previous.showWake != current.showWake) return true
        if (previous.trip != current.trip) return true
        val oldRssi = previous.rssi
        val newRssi = current.rssi
        if (oldRssi == null || newRssi == null) return oldRssi != newRssi
        return kotlin.math.abs(newRssi - oldRssi) >= RSSI_NOTIFICATION_STEP
    }

    private fun buildNotification(
        model: NotificationModel,
        groupSummary: Boolean,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tracking)
                .setContentTitle(withBuildLabel(model.title))
                .setContentText(notificationText(model))
                .setContentIntent(openAppIntent(model.bleName))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup(GROUP_KEY)
        // One UI ignores setColor, so a CI (PR) build says what it is in text and by icon shape.
        if (BuildConfig.BUILD_LABEL.isNotEmpty()) {
            builder.setSubText(BuildConfig.BUILD_LABEL)
        }
        if (groupSummary) {
            builder.setGroupSummary(true)
        }
        model.trip?.let { trip ->
            builder.showTrip(TripTextContext(this), trip, notificationText(model), model.percent.takeIf { !model.percentStale })
        }
        if (model.showWake && model.bleName != null) {
            builder.addAction(
                R.drawable.ic_stat_tracking,
                getString(R.string.tracking_wake),
                wakeIntent(model.bleName),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /**
     * The notification line: the state, the last known percentage, and its age
     * once the reading is stale. The age is in the text because OEM skins
     * (Samsung One UI) may drop the gray span that stock Android keeps.
     */
    private fun notificationText(model: NotificationModel): CharSequence {
        val percent = model.percent ?: return model.status
        val percentText = "$percent%"
        var text =
            if (model.status.contains(percentText)) {
                model.status
            } else {
                "${model.status} · $percentText"
            }
        val ageLabel = if (model.percentStale) model.percentAgeLabel else null
        val at = text.indexOf(percentText)
        if (ageLabel != null && at >= 0) {
            val insertAt = at + percentText.length
            text = text.substring(0, insertAt) + " · $ageLabel" + text.substring(insertAt)
        }
        if (!model.percentStale) return text
        val start = text.indexOf(percentText)
        if (start < 0) return text
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(STALE_TEXT_COLOR),
                start,
                start + percentText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Tapping a car's notification opens that car; the summary opens the app. */
    private fun openAppIntent(bleName: String?): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (bleName != null) {
            intent.putExtra(EXTRA_BLE_NAME, bleName)
        }
        return PendingIntent.getActivity(
            this,
            bleName?.hashCode() ?: REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun wakeIntent(bleName: String): PendingIntent {
        val intent =
            Intent(this, BleTrackingService::class.java)
                .setAction(ACTION_WAKE)
                .putExtra(EXTRA_BLE_NAME, bleName)
        return PendingIntent.getService(
            this,
            bleName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** "[PR 332] title" in CI (PR) builds so they read apart from the main app; unchanged elsewhere. */
    private fun withBuildLabel(text: String): String {
        val label = BuildConfig.BUILD_LABEL
        return if (label.isEmpty()) text else "[$label] $text"
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                withBuildLabel(getString(R.string.tracking_channel_name)),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tracking_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private data class NotificationModel(
        val bleName: String?,
        val title: String,
        val status: String,
        val stateText: String,
        val rssi: Int?,
        val percent: Int?,
        val percentStale: Boolean,
        val percentAgeLabel: String?,
        val showWake: Boolean,
        val trip: TripNotice? = null,
    )

    private fun carNotificationId(bleName: String): Int = CAR_NOTIFICATION_BASE + (bleName.hashCode() and 0xFFFF)

    companion object {
        private const val ACTION_START = "com.dzid26.teslable.action.START_TRACKING"
        private const val ACTION_WAKE = "com.dzid26.teslable.action.WAKE_VEHICLE"
        private const val EXTRA_START_REASON = "com.dzid26.teslable.extra.START_REASON"

        /** Extra carrying a car's advertised name for notification taps/wakes. */
        const val EXTRA_BLE_NAME = "com.dzid26.teslable.extra.BLE_NAME"
        private const val LOG_TAG = "BleTrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val GROUP_KEY = "com.dzid26.teslable.cars"
        private const val NOTIFICATION_ID = 1
        private const val CAR_NOTIFICATION_BASE = 1000
        private const val REQUEST_OPEN_APP = 0
        private const val RSSI_NOTIFICATION_STEP = 5
        private const val STALE_TEXT_COLOR = 0xFF9E9E9E.toInt()

        /** True while the foreground service is running (same process). */
        @Volatile
        var isRunning: Boolean = false

        /** Why the service starts, for the app-state log: no intent means Android restarted it after a kill. */
        private fun startReasonOf(intent: Intent?): AppState.StartReason =
            if (intent == null) {
                AppState.StartReason.SYSTEM_RESTART
            } else {
                AppState.StartReason.fromValue(intent.getIntExtra(EXTRA_START_REASON, AppState.StartReason.USER.value))
                    ?: AppState.StartReason.USER
            }

        /** Starts the service; [reason] goes into the app-state log when it was not running yet. */
        fun start(
            context: Context,
            reason: AppState.StartReason,
        ) {
            // startForegroundService must be followed by startForeground within seconds, which the
            // connected-device type refuses without Bluetooth permission; do not start it then.
            if (!hasBlePermissions(context)) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BleTrackingService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_START_REASON, reason.value),
                )
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+): Android
                // forbids starting from the background. The UI retries on resume.
                Log.w(LOG_TAG, "Tracking service start refused: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleTrackingService::class.java))
        }
    }
}
