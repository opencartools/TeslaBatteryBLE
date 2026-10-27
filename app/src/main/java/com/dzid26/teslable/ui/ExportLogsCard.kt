// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun ExportLogsCard(onExportLogs: suspend (OutputStream) -> Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ExportStatus>(ExportStatus.Idle) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            status = ExportStatus.Running
            scope.launch { status = writeExport(context, uri, onExportLogs) }
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_export_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { launcher.launch(exportFileName(System.currentTimeMillis())) },
                    enabled = status != ExportStatus.Running,
                ) {
                    Text(stringResource(R.string.settings_export_button))
                }
                Spacer(Modifier.width(12.dp))
                ExportStatusLine(status)
            }
        }
    }
}

@Composable
private fun ExportStatusLine(status: ExportStatus) {
    val body = MaterialTheme.typography.bodyMedium
    when (status) {
        ExportStatus.Idle -> Unit
        ExportStatus.Running -> {
            BusySpinner(size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_export_running), style = body)
        }
        is ExportStatus.Done -> Text(stringResource(R.string.settings_export_done, status.records), style = body)
        ExportStatus.Failed ->
            Text(stringResource(R.string.settings_export_failed), style = body, color = MaterialTheme.colorScheme.error)
    }
}

/** Not cancelled by leaving Settings: a half-written zip is worse than a finished one. */
private suspend fun writeExport(
    context: Context,
    uri: Uri,
    onExportLogs: suspend (OutputStream) -> Int,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): ExportStatus =
    withContext(NonCancellable) {
        withContext(dispatcher) {
            runCatching {
                val out = checkNotNull(context.contentResolver.openOutputStream(uri)) { "no stream" }
                ExportStatus.Done(onExportLogs(out))
            }.getOrElse {
                // Do not leave an empty or half-written file behind.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                ExportStatus.Failed
            }
        }
    }

private sealed interface ExportStatus {
    data object Idle : ExportStatus

    data object Running : ExportStatus

    data class Done(
        val records: Int,
    ) : ExportStatus

    data object Failed : ExportStatus
}

private val EXPORT_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

internal fun exportFileName(nowMillis: Long): String =
    "teslabatteryble-logs-" + EXPORT_NAME_FORMAT.format(Instant.ofEpochMilli(nowMillis)) + ".zip"
