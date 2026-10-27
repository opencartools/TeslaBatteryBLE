// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.dzid26.teslable.BuildConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only log pull (ADR-0014): `adb shell am broadcast` writes the history as framed `.pblog` files
 * in `files/battery-history-export/<time>.zip`, which `adb exec-out run-as` can read. See
 * `docs/reference/log-pull.md`. The manifest asks the sender for the DUMP permission, which only the
 * shell holds, and the receiver does nothing unless the app is debuggable.
 */
class ExportLogsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val pending = goAsync()
        thread {
            runCatching {
                // A second reader of the live file: it never moves a database aside, unlike the app's own open.
                val history = HistoryDatabase.openExisting(context)
                val events = EventsDatabase.openExisting(context)
                try {
                    val zip =
                        LogExporter.exportToDirectory(
                            RoomBackedRecordStore(history, events, OriginId.load(context)),
                            File(context.filesDir, EXPORT_DIR_NAME),
                            System.currentTimeMillis(),
                            BuildConfig.VERSION_NAME,
                        )
                    Log.i(TAG, "exported ${zip.name}")
                } finally {
                    history.close()
                    events.close()
                }
            }.onFailure { Log.w(TAG, "export failed: ${it.javaClass.simpleName}") }
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "ExportLogs"
        const val EXPORT_DIR_NAME = "battery-history-export"
    }
}
