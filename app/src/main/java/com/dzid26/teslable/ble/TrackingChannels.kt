// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.R

/** The notification channels of the tracking service, created on first use and openable in system settings. */
internal object TrackingChannels {
    const val TRACKING = "tracking"
    const val TRIP = "trip"
    const val UNLOCK = "unlock_alert"

    /** "[PR 332] title" in CI (PR) builds so they read apart from the main app; unchanged elsewhere. */
    fun withBuildLabel(text: String): String {
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
        if (manager.getNotificationChannel(TRACKING) == null) {
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
        // Its own channel, not the silent trip one: a high-importance channel is what gives the heads-up
        // and the car lock sound on Android 8 and later.
        if (manager.getNotificationChannel(UNLOCK) == null) {
            val channel =
                NotificationChannel(
                    UNLOCK,
                    withBuildLabel(context.getString(R.string.unlock_alert_channel_name)),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.unlock_alert_channel_description)
                    setShowBadge(true)
                    setSound(unlockSound(context), unlockAudioAttributes())
                    enableVibration(true)
                }
            manager.createNotificationChannel(channel)
        }
    }

    /** The bundled car lock beep, which the alert channel plays. */
    private fun unlockSound(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.car_lock}")

    /** A notification sound, so the alert does not ride the ringtone volume of an alarm or a call. */
    private fun unlockAudioAttributes(): AudioAttributes {
        val builder = AudioAttributes.Builder()
        builder.setUsage(AudioAttributes.USAGE_NOTIFICATION)
        builder.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        return builder.build()
    }

    /**
     * Opens the Do Not Disturb exceptions page in system settings, where the app can be allowed through, as
     * Samsung's One UI needs: its channel pages have no "Override Do Not Disturb". Phones without that page get
     * the trip channel's page, which has the override on stock Android. The channel is created first, so either
     * page lists it before the service has ever run.
     */
    fun openDoNotDisturbSettings(context: Context) {
        ensure(context)
        // Starting the intent is the check: resolving it first would need a package visibility entry. Some ROMs
        // export the zen page behind a permission, so a SecurityException counts as missing too.
        val pages =
            listOf(
                Intent(ZEN_MODE_PRIORITY_SETTINGS),
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, TRIP),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        pages.firstOrNull { page ->
            runCatching { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }

    /** The system's Do Not Disturb exceptions screen; not a public constant in [Settings]. */
    private const val ZEN_MODE_PRIORITY_SETTINGS = "android.settings.ZEN_MODE_PRIORITY_SETTINGS"
}
