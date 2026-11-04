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
import com.dzid26.teslable.ClosureAlertStore
import com.dzid26.teslable.MainActivity
import com.dzid26.teslable.R
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.VehicleStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * The "closure left open" alert, start to finish: the per-car episode state, the owner's switch and the
 * heads-up. The controller feeds it each VCSEC status, each connection event and the two conditions only
 * it knows (tracking on, Bluetooth off).
 *
 * A drop is routine, so this fires on the first drop after a status on that connection reported a door,
 * trunk or tonneau open. The same open stretch stays quiet across retries. A link that stays up for a
 * minute counts as a return, so a later departure beeps again; so does a status with every closure shut.
 * Its own notification, not part of the tracking service's ongoing one, so it can be dismissed and the
 * service's next update cannot take it away. The channel carries the importance and the car lock chirp.
 */
internal class ClosureAlert(
    private val context: Context,
    private val state: StateFlow<BleUiState>,
    private val log: (String) -> Unit,
    private val episodes: ClosureAlerts = ClosureAlerts(),
    private val store: ClosureAlertStore = ClosureAlertStore(context),
) {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingRearm = mutableMapOf<String, Runnable>()

    /** Cars whose alert is in the shade, so a later all-closed status cancels instead of re-cancelling every 10 s. */
    private val posted = mutableSetOf<String>()

    fun onStatus(
        bleName: String,
        status: VehicleStatus,
    ) {
        val closureOpen = status.closureStatuses.anyClosureOpen()
        episodes.onStatus(bleName, closureOpen = closureOpen)
        // Presence flips with key range and was PRESENT during the real door-open test, so it cannot clear this.
        if (!closureOpen) clear(bleName)
    }

    /** A new connection: nothing may arm until a status arrives on it, and staying up re-arms the alert. */
    fun onLinkReady(bleName: String) {
        episodes.onConnectionStarted(bleName)
        pendingRearm.remove(bleName)?.let(handler::removeCallbacks)
        val runnable =
            Runnable {
                pendingRearm.remove(bleName)
                episodes.onLinkUpStable(bleName)
            }
        pendingRearm[bleName] = runnable
        handler.postDelayed(runnable, REARM_MILLIS)
    }

    /** Beeps now when the last status on this connection had a closure open; retries stay silent. */
    fun onLinkDropped(
        bleName: String,
        title: String,
    ) {
        pendingRearm.remove(bleName)?.let(handler::removeCallbacks)
        val enabled = store.enabled() && state.value.trackingEnabled && !bluetoothOff()
        if (!episodes.onLinkDropped(bleName, enabled)) return
        post(bleName, title)
        log("$title: a door or trunk is open; link dropped, alert posted")
    }

    /** Forgets every episode and cancels every re-arm, for a deliberate teardown. */
    fun disarmAll() {
        pendingRearm.values.forEach(handler::removeCallbacks)
        pendingRearm.clear()
        episodes.disarmAll()
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
                .Builder(context, TrackingChannels.CLOSURE)
                .setSmallIcon(R.drawable.ic_stat_tracking)
                .setContentTitle(TrackingChannels.withBuildLabel(context.getString(R.string.closure_alert_notification_title)))
                .setContentText(context.getString(R.string.closure_alert_notification_text, title))
                .setContentIntent(openCarIntent(bleName))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            manager.notify(notificationId(bleName), notification)
            posted.add(bleName)
        }
    }

    /** Every closure is shut again: the warning has done its job. */
    private fun clear(bleName: String) {
        if (!posted.remove(bleName)) return
        NotificationManagerCompat.from(context).cancel(notificationId(bleName))
    }

    /** Tapping the alert opens the app on that car, so the owner can close it. */
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

        /** A link that stays up this long counts as a return; a later departure can beep again. */
        const val REARM_MILLIS = 60_000L
    }
}

/**
 * Any door, trunk or tonneau that is not closed. `OPENING` counts because the closure is still not shut;
 * `AJAR` counts although the car never reports it. `CLOSED`, `CLOSING`, `FAILED_UNLATCH` and `UNKNOWN` do
 * not count. The charge port is left out: it is normally open while charging.
 */
internal fun ClosureStatuses?.anyClosureOpen(): Boolean =
    this != null &&
        listOf(frontDriverDoor, frontPassengerDoor, rearDriverDoor, rearPassengerDoor, frontTrunk, rearTrunk, tonneau)
            .any { it in openClosureStates }

private val openClosureStates =
    setOf(
        ClosureState_E.CLOSURESTATE_OPEN,
        ClosureState_E.CLOSURESTATE_AJAR,
        ClosureState_E.CLOSURESTATE_OPENING,
    )
