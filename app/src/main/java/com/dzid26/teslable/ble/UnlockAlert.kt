// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dzid26.teslable.MainActivity
import com.dzid26.teslable.R
import com.dzid26.teslable.UnlockAlertStore

/**
 * The "car left unlocked" alert, start to finish: the per-car episode state, the owner's switch and the
 * heads-up. The controller feeds it each VCSEC status, each link drop, and the two conditions only the
 * controller knows (tracking on, Bluetooth off).
 *
 * It is its own notification, not part of the tracking service's ongoing one: it can be dismissed, and
 * the service's next update cannot take it away. The channel carries the importance and the car lock
 * sound; the notification itself only asks to alert.
 */
internal class UnlockAlert(
    private val context: Context,
    private val episodes: UnlockAlerts = UnlockAlerts(),
    private val store: UnlockAlertStore = UnlockAlertStore(context),
) {
    fun onStatus(
        bleName: String,
        locked: Boolean,
        userPresent: Boolean,
    ) = episodes.onStatus(bleName, locked, userPresent)

    /** Forgets every episode, for a deliberate teardown such as tracking or Bluetooth being switched off. */
    fun disarmAll() = episodes.disarmAll()

    /**
     * Posts the alert when a link that was up on a car whose last status was unlocked and empty drops.
     * [trackingEnabled] and [bluetoothOff] keep a teardown or the radio switch from looking like the owner
     * walking away. Returns whether it posted.
     */
    fun onLinkDropped(
        bleName: String,
        title: String,
        trackingEnabled: Boolean,
        bluetoothOff: Boolean,
    ): Boolean {
        val alert = episodes.onLinkDropped(bleName, store.enabled(), trackingEnabled, bluetoothOff)
        if (alert) post(bleName, title)
        return alert
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
    }
}
