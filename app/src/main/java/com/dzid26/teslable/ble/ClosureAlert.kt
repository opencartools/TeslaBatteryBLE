// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dzid26.teslable.ClosureAlertStore
import com.dzid26.teslable.MainActivity
import com.dzid26.teslable.R
import com.dzid26.teslable.protocol.locked
import com.dzid26.teslable.protocol.userPresent
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.VehicleStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * The "closure left open" alert, start to finish: the owner's switch, the posted notification and the
 * conditions only the controller knows (tracking on, Bluetooth off). [ClosureAlertEpisodes] owns the
 * per-car timing and the one-beep-per-stretch rule.
 *
 * A drop is routine, so this fires on the first drop after a status on that connection reported a door,
 * trunk or tonneau open. The same open stretch stays quiet across retries. A link that stayed up for a
 * minute by the time of the next drop counts as a return, so a later departure beeps again; so does a
 * status with every closure shut. Its own notification, not part of the tracking service's ongoing one,
 * so it can be dismissed and the service's next update cannot take it away. The channel carries the
 * importance and the car lock chirp.
 */
internal class ClosureAlert(
    private val context: Context,
    private val state: StateFlow<BleUiState>,
    private val log: (String) -> Unit,
    private val episodes: ClosureAlertEpisodes = ClosureAlertEpisodes(),
    private val store: ClosureAlertStore = ClosureAlertStore(context),
) {
    /** Cars whose alert is in the shade, so a later all-closed status cancels instead of re-cancelling every 10 s. */
    private val posted = mutableSetOf<String>()

    fun onStatus(
        bleName: String,
        status: VehicleStatus,
    ) {
        val closureOpen = status.closureStatuses.anyClosureOpen()
        episodes.onStatus(bleName, closureOpen = closureOpen)
        if (clearsWhenShut(closureOpen, status.userPresent, status.locked)) clear(bleName)
    }

    /** A new connection: only a status received on it may arm the alert, and its stable-up clock starts. */
    fun onLinkReady(bleName: String) {
        episodes.onConnectionStarted(bleName, System.currentTimeMillis())
    }

    /** Beeps now when the last status on this connection had a closure open; retries stay silent. */
    fun onLinkDropped(
        bleName: String,
        title: String,
    ) {
        val enabled = store.enabled() && state.value.trackingEnabled && !bluetoothOff()
        if (!episodes.onLinkDropped(bleName, System.currentTimeMillis(), enabled)) return
        post(bleName, title)
        log("$title: a door or trunk is open; link dropped, alert posted")
    }

    /** Forgets every episode, for a deliberate teardown. */
    fun disarmAll() {
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

    /** Every closure is shut and the car is secured: the warning has done its job. */
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
    }
}

/**
 * A closed car clears the alert once it is also clearly secured: the owner is in key range, or the car
 * itself has locked. A third party can shut and lock a door while the phone is away.
 */
internal fun clearsWhenShut(
    closureOpen: Boolean,
    userPresent: Boolean,
    locked: Boolean,
): Boolean = !closureOpen && (userPresent || locked)

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
