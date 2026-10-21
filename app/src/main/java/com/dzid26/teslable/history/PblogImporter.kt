// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.ProtoLog
import java.io.File

/**
 * The one-time import of the `.pblog` files the app wrote before ADR-0014 into the [RecordStore]. It is
 * idempotent through the store's unique key: a file that is read twice (a crash before the rename) adds
 * nothing. An imported file is renamed to `<name>.imported`, which no reader looks at.
 */
internal class PblogImporter(
    private val dir: File,
    private val store: RecordStore,
) {
    fun importAll() {
        val files = dir.listFiles()?.filter { it.isFile && PblogNames.parse(it.name) != null } ?: return
        for (file in files) {
            val (vehicleId, kind) = checkNotNull(PblogNames.parse(file.name))
            val imported =
                runCatching {
                    val records = ProtoLog.decode(file.readBytes(), BleRecord.ADAPTER)
                    records.chunked(IMPORT_BATCH).forEach { store.appendAll(vehicleId, kind, it) }
                }.isSuccess
            if (imported) file.renameTo(File(dir, file.name + IMPORTED_SUFFIX))
        }
    }

    companion object {
        const val IMPORTED_SUFFIX = ".imported"
        private const val IMPORT_BATCH = 500
    }
}
