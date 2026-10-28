// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.R

/** The notification channels of the tracking service, created on first use and openable in system settings. */
internal object TrackingChannels {
    const val TRACKING = "tracking"
    const val TRIP = "trip"

    /** "[PR 332] title" in CI (PR) builds so they read apart from the main app; unchanged elsewhere. */
    private fun withBuildLabel(text: String): String {
        val label = BuildConfig.BUILD_LABEL
        return if (label.isEmpty()) text else "[$label] $text"
    }

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(TRIP) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    TRIP,
                    withBuildLabel(context.getString(R.string.trip_channel_name)),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.trip_channel_description)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        if (manager.getNotificationChannel(TRACKING) != null) return
        val channel =
            NotificationChannel(
                TRACKING,
                withBuildLabel(context.getString(R.string.tracking_channel_name)),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.tracking_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Opens the trip channel's page in system settings, where "Override Do Not Disturb" lets the trip pop-up
     * through. The channel is created first, so the page exists before the service has ever run.
     */
    fun openTripSettings(context: Context) {
        ensure(context)
        val intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, TRIP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
