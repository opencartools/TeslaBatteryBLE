// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.ProtoLog
import java.io.File
import java.io.OutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The "Export logs" routine (ADR-0014): writes every series of the [RecordStore] as a framed
 * `<vehicleId>.<kind>.pblog` file (the [ProtoLog] encoding, plus `app.pblog`) into one zip, so the
 * decode tooling that read the app's old files reads an export. The database is never handed out.
 */
internal object LogExporter {
    /** Writes the zip to [out] and returns the number of records exported. */
    fun export(
        store: RecordStore,
        out: OutputStream,
    ): Int {
        var exported = 0
        ZipOutputStream(out).use { zip ->
            for ((vehicleId, kind) in store.series().sortedBy { PblogNames.fileName(it.first, it.second) }) {
                zip.putNextEntry(ZipEntry(PblogNames.fileName(vehicleId, kind)))
                store.streamSeries(vehicleId, kind) { batch ->
                    zip.write(ProtoLog.encode(batch))
                    exported += batch.size
                }
                zip.closeEntry()
            }
        }
        return exported
    }

    /**
     * Exports into `<time>.zip` in [dir] (UTC time) and returns it. Only the newest [KEEP_EXPORTS] zips
     * are kept, so repeated pulls do not fill the phone.
     */
    fun exportToDirectory(
        store: RecordStore,
        dir: File,
        nowMillis: Long,
    ): File {
        dir.mkdirs()
        val name = NAME_FORMAT.format(java.time.Instant.ofEpochMilli(nowMillis)) + ".zip"
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { export(store, it) }
        val zip = File(dir, name)
        check(tmp.renameTo(zip)) { "could not move the export into place" }
        dir
            .listFiles { file -> file.name.endsWith(".zip") }
            ?.sortedBy { it.name }
            ?.dropLast(KEEP_EXPORTS)
            ?.forEach { it.delete() }
        return zip
    }

    private const val KEEP_EXPORTS = 3
    private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
}
