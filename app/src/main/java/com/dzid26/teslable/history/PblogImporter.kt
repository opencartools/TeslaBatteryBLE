// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.ProtoLog
import java.io.File

/**
 * The one-time import of the `.pblog` files the app wrote before ADR-0014 into the [RecordStore]. It is
 * idempotent through the store's unique keys: a file that is read twice (a crash before the rename) adds
 * nothing. An imported file is renamed to `<name>.imported`, which no reader looks at.
 *
 * A database that holds no rows when the import starts (newly created, for example after the old one was
 * kept aside as corrupt) gets the `.imported` files of its own kinds read again, so it does not start
 * empty while the logs are still on the phone. Those files keep their names.
 */
internal class PblogImporter(
    private val dir: File,
    private val store: RecordStore,
) {
    fun importAll() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        // Decided before anything is imported: a file with rows from this run is not "empty".
        val restoreCar = store.isEmpty(events = false)
        val restoreEvents = store.isEmpty(events = true)
        for (file in files) {
            val pending = PblogNames.parse(file.name)
            if (pending != null) {
                if (load(file, pending)) file.renameTo(File(dir, file.name + IMPORTED_SUFFIX))
                continue
            }
            val done = PblogNames.parse(file.name.removeSuffix(IMPORTED_SUFFIX)).takeIf { file.name.endsWith(IMPORTED_SUFFIX) }
            if (done != null && (if (done.second.isEvent) restoreEvents else restoreCar)) load(file, done)
        }
    }

    private fun load(
        file: File,
        series: Pair<String, RecordKind>,
    ): Boolean =
        runCatching {
            val records = ProtoLog.decode(file.readBytes(), BleRecord.ADAPTER)
            records.chunked(IMPORT_BATCH).forEach { store.appendAll(series.first, series.second, it) }
        }.isSuccess

    companion object {
        const val IMPORTED_SUFFIX = ".imported"
        private const val IMPORT_BATCH = 500
    }
}
