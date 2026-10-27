// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.io.OutputStream

/** Where the user-started "Export logs" run stands; it outlives the Settings screen. */
sealed interface LogExportStatus {
    data object Idle : LogExportStatus

    data object Running : LogExportStatus

    data class Done(
        val records: Int,
    ) : LogExportStatus

    /** [leftover]: the half-written file could not be removed from the provider and may still exist. */
    data class Failed(
        val leftover: Boolean,
    ) : LogExportStatus
}

/**
 * Runs the Settings "Export logs" action into a document the user picked (Storage Access Framework),
 * one at a time, in its own scope so leaving Settings or rotating neither cancels it nor loses its result.
 */
class LogExportRunner(
    private val resolver: ContentResolver,
    private val export: suspend (OutputStream) -> Int,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _status = MutableStateFlow<LogExportStatus>(LogExportStatus.Idle)
    val status: StateFlow<LogExportStatus> = _status.asStateFlow()

    /** Starts writing the export into [uri]; ignored while another export runs. */
    fun start(uri: Uri) {
        if (_status.getAndUpdate { LogExportStatus.Running } is LogExportStatus.Running) return
        scope.launch { _status.value = write(uri) }
    }

    private suspend fun write(uri: Uri): LogExportStatus =
        runCatching {
            val out = checkNotNull(resolver.openOutputStream(uri)) { "no stream" }
            LogExportStatus.Done(export(out))
        }.getOrElse {
            // Not every provider can delete after a failed write; say so when it could not.
            val removed = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
            LogExportStatus.Failed(leftover = !removed)
        }
}
