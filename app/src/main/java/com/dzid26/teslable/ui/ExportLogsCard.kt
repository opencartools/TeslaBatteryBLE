// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.history.LogExportStatus
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The picker wrapper: asks where to save, then hands the chosen document to [onExport]. */
@Composable
internal fun ExportLogsCard(
    status: LogExportStatus,
    onExport: (Uri) -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) onExport(uri)
        }
    ExportLogsCardBody(
        status = status,
        onPickFile = { launcher.launch(exportFileName(System.currentTimeMillis())) },
    )
}

@Composable
internal fun ExportLogsCardBody(
    status: LogExportStatus,
    onPickFile: () -> Unit,
) {
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
            Button(onClick = onPickFile, enabled = status != LogExportStatus.Running) {
                Text(stringResource(R.string.settings_export_button))
            }
            if (status != LogExportStatus.Idle) {
                // Under the button, not beside it: a long message would be squeezed into a sliver on narrow screens.
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { ExportStatusLine(status) }
            }
        }
    }
}

@Composable
private fun ExportStatusLine(status: LogExportStatus) {
    val body = MaterialTheme.typography.bodyMedium
    when (status) {
        LogExportStatus.Idle -> Unit
        LogExportStatus.Running -> {
            BusySpinner(size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_export_running), style = body)
        }
        is LogExportStatus.Done -> {
            val count = NumberFormat.getIntegerInstance().format(status.records)
            Text(pluralStringResource(R.plurals.settings_export_done, status.records, count), style = body)
        }
        is LogExportStatus.Failed ->
            Text(
                text = stringResource(if (status.leftover) R.string.settings_export_failed_leftover else R.string.settings_export_failed),
                style = body,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

private val EXPORT_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

internal fun exportFileName(nowMillis: Long): String =
    "teslabatteryble-logs-" + EXPORT_NAME_FORMAT.format(Instant.ofEpochMilli(nowMillis)) + ".zip"
