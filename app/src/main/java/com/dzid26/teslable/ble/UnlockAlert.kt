// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dzid26.teslable.MainActivity
import com.dzid26.teslable.R
import com.dzid26.teslable.UnlockAlertStore
import com.dzid26.teslable.protocol.locked
import com.dzid26.teslable.protocol.userPresent
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.VehicleStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * The "car left unlocked" alert, start to finish: the wait after a drop, the per-car episode state, the
 * owner's switch and the heads-up.
 *
 * A car ends idle links all the time, so a drop only starts a timer. A successful reconnect before
 * [UnlockAlerts.DEFAULT_WAIT_MILLIS] cancels it; a link that stays down for the whole wait posts the alert
 * if the last status said the car was unlocked and open (or empty). Its own notification, not part of the
 * tracking service's ongoing one, so it can be dismissed and the service's next update cannot take it away.
 * The channel carries the importance and the car lock sound; the notification itself only asks to alert.
 */
internal class UnlockAlert(
    private val context: Context,
    private val state: StateFlow<BleUiState>,
    private val log: (String) -> Unit,
    private val episodes: UnlockAlerts = UnlockAlerts(),
    private val store: UnlockAlertStore = UnlockAlertStore(context),
) {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, Runnable>()

    /** True when a door or trunk is open, ajar, opening or failed to latch. */
    private fun ClosureStatuses?.anyOpen(): Boolean =
        this != null &&
            listOf(frontDriverDoor, frontPassengerDoor, rearDriverDoor, rearPassengerDoor, rearTrunk, frontTrunk, tonneau)
                .any { it in openClosures }

    fun onStatus(
        bleName: String,
        status: VehicleStatus,
    ) {
        episodes.onStatus(
            bleName,
            unlocked = !status.locked,
            closureOpen = status.closureStatuses.anyOpen(),
            userPresent = status.userPresent,
        )
    }

    /** Starts the wait after a link that was up drops; a reconnect before it ends cancels the wait. */
    fun onLinkDropped(
        bleName: String,
        title: String,
    ) {
        if (pending.containsKey(bleName)) return
        val runnable = Runnable { fire(bleName, title) }
        pending[bleName] = runnable
        handler.postDelayed(runnable, UnlockAlerts.DEFAULT_WAIT_MILLIS)
    }

    /** The link is back: the car is reachable, so this drop was not a departure. */
    fun onLinkReady(bleName: String) {
        pending.remove(bleName)?.let(handler::removeCallbacks)
        episodes.onLinkReady(bleName)
    }

    /** Forgets every episode and cancels every wait, for a deliberate teardown. */
    fun disarmAll() {
        pending.values.forEach(handler::removeCallbacks)
        pending.clear()
        episodes.disarmAll()
    }

    private fun fire(
        bleName: String,
        title: String,
    ) {
        pending.remove(bleName)
        val enabled = store.enabled() && state.value.trackingEnabled && !bluetoothOff()
        if (!episodes.due(bleName, System.currentTimeMillis(), enabled)) {
            episodes.cancelPending(bleName)
            return
        }
        post(bleName, title)
        val minutes = UnlockAlerts.DEFAULT_WAIT_MILLIS / MILLIS_PER_MINUTE
        log("$title: unlocked with a door or trunk open, no reconnect for $minutes min; alert posted")
    }

    /** True while the radio is off: the adapter ended that link, not the owner walking away with the phone. */
    private fun bluetoothOff(): Boolean {
        if (!state.value.bluetoothOn) return true
        return runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false
        }.getOrDefault(false)
    }

    private fun post(
        bleName: String,
        title: String,
    ) {
        TrackingChannels.ensure(context)
        val notification =
            NotificationCompat
                .Builder(context, TrackingChannels.UNLOCK)
                .setSmallIcon(R.drawable.ic_stat_tracking)
                .setContentTitle(TrackingChannels.withBuildLabel(context.getString(R.string.unlock_alert_notification_title)))
                .setContentText(context.getString(R.string.unlock_alert_notification_text, title))
                .setContentIntent(openCarIntent(bleName))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            manager.notify(notificationId(bleName), notification)
        }
    }

    /** Tapping the alert opens the app on that car, so the owner can lock it or reconnect. */
    private fun openCarIntent(bleName: String): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BleTrackingService.EXTRA_BLE_NAME, bleName)
        return PendingIntent.getActivity(
            context,
            bleName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(bleName: String): Int = NOTIFICATION_BASE + (bleName.hashCode() and ID_MASK)

    private companion object {
        /** Each car gets its own slot, away from the tracking notification ids. */
        const val NOTIFICATION_BASE = 2000
        const val ID_MASK = 0xFFFF
        const val MILLIS_PER_MINUTE = 60_000L

        /** A door or trunk left open counts even when the car says someone is present; a charge port alone does not. */
        val openClosures =
            setOf(
                ClosureState_E.CLOSURESTATE_OPEN,
                ClosureState_E.CLOSURESTATE_AJAR,
                ClosureState_E.CLOSURESTATE_OPENING,
                ClosureState_E.CLOSURESTATE_FAILED_UNLATCH,
            )
    }
}
