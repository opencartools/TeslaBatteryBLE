// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.ProtoLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One append-only log file of [BleRecord]s with the cap every kind shares: at most
 * [MAX_RECORDS_PER_FILE] records, trimmed [TRIM_SLACK] below it when the cap is hit, so a full
 * file costs one rewrite per thousand appends instead of one per append. Callers hold the
 * store's mutex.
 */
internal class CappedLog(
    private val file: File,
) {
    private var count = 0

    fun append(record: BleRecord) {
        file.parentFile?.mkdirs()
        val next = count + 1
        if (next > MAX_RECORDS_PER_FILE) {
            val kept =
                (ProtoLog.decode(file.readBytes(), BleRecord.ADAPTER) + record)
                    .takeLast(MAX_RECORDS_PER_FILE - TRIM_SLACK)
            writeAtomically(kept)
            count = kept.size
        } else {
            file.appendBytes(ProtoLog.encodeFrame(record))
            count = next
        }
    }

    /** Decodes the file (none yet reads as empty) and refreshes the record count the trim goes by. */
    fun readAll(): List<BleRecord> {
        if (!file.isFile) return emptyList()
        return ProtoLog.decode(file.readBytes(), BleRecord.ADAPTER).also { count = it.size }
    }

    private fun writeAtomically(records: List<BleRecord>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(ProtoLog.encode(records))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val MAX_RECORDS_PER_FILE = 20_000
        const val TRIM_SLACK = 1_000
    }
}

/**
 * One kind of per-vehicle log: `<vehicleId><suffix>` files in [dir], holding
 * [BleRecord]s. Each kind (charge, VCSEC status, drive, closures, climate, connection, command) is one
 * instance, and all share the same cap and trim. Callers hold the store's mutex.
 */
internal class VehicleLogs(
    private val dir: File,
    private val suffix: String,
) {
    private val logs = mutableMapOf<String, CappedLog>()

    fun append(
        vehicleId: String,
        record: BleRecord,
    ) {
        logs.getOrPut(vehicleId) { CappedLog(fileFor(vehicleId)) }.append(record)
    }

    /** Decodes every vehicle file of this kind and refreshes the record counts. */
    fun readAll(): Map<String, List<BleRecord>> {
        val files = dir.listFiles() ?: return emptyMap()
        val recordsByVehicle = mutableMapOf<String, List<BleRecord>>()
        for (file in files) {
            val vehicleId = vehicleIdOf(file) ?: continue
            val log = logs.getOrPut(vehicleId) { CappedLog(file) }
            recordsByVehicle[vehicleId] = log.readAll()
        }
        return recordsByVehicle
    }

    private fun fileFor(vehicleId: String): File = File(dir, "${vehicleId.ifEmpty { LEGACY_VEHICLE_STEM }}$suffix")

    /**
     * The vehicle a file of this kind belongs to, or null for any other file.
     * Each kind has its own suffix and none ends with another's, so a reader
     * never picks up another kind's file, nor an older raw `<vehicleId>.pblog`
     * charge file, nor the app-state log [APP_LOG_FILE_NAME], which stay on
     * disk unread by every vehicle kind.
     */
    private fun vehicleIdOf(file: File): String? {
        if (!file.isFile || !file.name.endsWith(suffix)) return null
        val stem = file.name.removeSuffix(suffix)
        if (stem.isEmpty()) return null
        return if (stem == LEGACY_VEHICLE_STEM) "" else stem
    }

    private companion object {
        /** File stem for rows with a pre-ADR-0004 empty vehicle id. */
        const val LEGACY_VEHICLE_STEM = "legacy"
    }
}

/** Joins per-car record maps of different kinds, keeping every kind's records for a car; `Map + Map` would drop one. */
internal fun mergeByVehicle(vararg logs: Map<String, List<BleRecord>>): Map<String, List<BleRecord>> =
    logs.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() }

/**
 * The app-state log: `app.pblog` in [dir], one file for the whole app (screen, foreground and
 * tracking service are not per car), with the same cap and trim as the vehicle logs. Its name is
 * not `<id><suffix>` of any vehicle kind, so no [VehicleLogs] reads it. Callers hold the store's mutex.
 */
internal class AppLog(
    dir: File,
) {
    private val log = CappedLog(File(dir, APP_LOG_FILE_NAME))

    fun append(record: BleRecord) = log.append(record)

    fun readAll(): List<BleRecord> = log.readAll()
}

/** `app.pblog`: the app-state records (ADR-0008). */
internal const val APP_LOG_FILE_NAME = "app.pblog"
