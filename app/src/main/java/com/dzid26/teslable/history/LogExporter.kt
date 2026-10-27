// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.ProtoLog
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The "Export logs" routine (ADR-0014): writes every series of the [RecordStore] as a framed
 * `<vehicleId>.<kind>.pblog` file (the [ProtoLog] encoding, plus `app.pblog`) into one zip, so the
 * decode tooling that read the app's old files reads an export, and a `manifest.json` that says what the
 * zip holds. The databases are never handed out.
 */
internal object LogExporter {
    /** What one exported file holds, for the manifest. */
    private class FileInfo(
        val name: String,
        val records: Int,
        val firstTime: Long?,
        val lastTime: Long?,
    )

    /** Writes the zip to [out] and returns the number of records exported. */
    fun export(
        store: RecordStore,
        out: OutputStream,
        appVersion: String,
    ): Int {
        val files = mutableListOf<FileInfo>()
        ZipOutputStream(out).use { zip ->
            for (series in store.series().sortedBy { PblogNames.fileName(it.vehicleId, it.kind) }) {
                val name = PblogNames.fileName(series.vehicleId, series.kind)
                zip.putNextEntry(ZipEntry(name))
                files += writeSeries(store, series, zip, name)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifest(store, files, appVersion).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return files.sumOf { it.records }
    }

    private fun writeSeries(
        store: RecordStore,
        series: SeriesRef,
        zip: ZipOutputStream,
        name: String,
    ): FileInfo {
        var count = 0
        var first: Long? = null
        var last: Long? = null
        var afterId = 0L
        while (true) {
            val page = store.stream(series, afterId, PAGE)
            if (page.isEmpty()) break
            zip.write(ProtoLog.encode(page.map { it.record }))
            count += page.size
            first = minOf(first ?: Long.MAX_VALUE, page.minOf { it.time })
            last = maxOf(last ?: Long.MIN_VALUE, page.maxOf { it.time })
            afterId = page.last().id
        }
        return FileInfo(name, count, first, last)
    }

    private fun manifest(
        store: RecordStore,
        files: List<FileInfo>,
        appVersion: String,
    ): String {
        val vehicles = store.series().map { it.vehicleId }.filter { it.isNotEmpty() }.distinct().sorted()
        val fileJson =
            files.joinToString(",") {
                "{\"name\":${quote(it.name)},\"records\":${it.records},\"firstTime\":${it.firstTime},\"lastTime\":${it.lastTime}}"
            }
        return "{\"formatVersion\":$FORMAT_VERSION,\"originUuid\":${quote(store.originUuid)},\"appVersion\":${quote(appVersion)}," +
            "\"vehicleIds\":[${vehicles.joinToString(",") { quote(it) }}],\"files\":[$fileJson]}"
    }

    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Exports into `<time>.zip` in [dir] (UTC time) and returns it. Only the newest [KEEP_EXPORTS] zips
     * are kept, so repeated pulls do not fill the phone.
     */
    fun exportToDirectory(
        store: RecordStore,
        dir: File,
        nowMillis: Long,
        appVersion: String,
    ): File {
        dir.mkdirs()
        val name = NAME_FORMAT.format(Instant.ofEpochMilli(nowMillis)) + ".zip"
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { export(store, it, appVersion) }
        val zip = File(dir, name)
        check(tmp.renameTo(zip)) { "could not move the export into place" }
        dir
            .listFiles { file -> file.name.endsWith(".zip") }
            ?.sortedBy { it.name }
            ?.dropLast(KEEP_EXPORTS)
            ?.forEach { it.delete() }
        return zip
    }

    /** The name of the manifest entry in an export. */
    const val MANIFEST_NAME = "manifest.json"
    private const val FORMAT_VERSION = 1
    private const val PAGE = 1_000
    private const val KEEP_EXPORTS = 3
    private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
}
