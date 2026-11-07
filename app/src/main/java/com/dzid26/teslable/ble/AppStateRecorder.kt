// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.history.HistoryStore
import java.time.Instant

/** Records the app's own foreground and background changes, dropping a repeat of the last visibility event. */
internal class AppStateRecorder(
    private val historyStore: HistoryStore,
) {
    private var lastVisibilityEvent: AppState.Event? = null

    fun record(
        event: AppState.Event,
        startReason: AppState.StartReason?,
    ) {
        if (event == AppState.Event.APP_FOREGROUND || event == AppState.Event.APP_BACKGROUND) {
            if (event == lastVisibilityEvent) return
            lastVisibilityEvent = event
        }
        historyStore.recordAppState(
            AppState(event = event, start_reason = startReason),
            Instant.ofEpochMilli(System.currentTimeMillis()),
        )
    }
}
