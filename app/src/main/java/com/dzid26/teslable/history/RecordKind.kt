// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

/** Whose clock times a series' records (ADR-0014). */
internal enum class Clock(
    val id: String,
) {
    /** The car's own timestamp inside its reply (the CarServer kinds). */
    CAR("car"),

    /** The phone's clock when the record arrived (VCSEC replies, and the phone-side events). */
    PHONE("phone"),
}

/**
 * The kinds of raw record the history keeps (ADR-0008, ADR-0014). [id] is the `kind` column and the
 * middle of the `<vehicleId>.<kind>.pblog` names of imported and exported files. [clock] says whose
 * clock times the series. [isEvent] marks the phone's own logs, which live in `events.db`.
 */
internal enum class RecordKind(
    val id: String,
    val clock: Clock,
    val isEvent: Boolean = false,
) {
    /** The app's own state; belongs to no car, so its vehicle id is empty. */
    APP("app", Clock.PHONE, isEvent = true),
    CHARGE("charge", Clock.CAR),
    CLIMATE("climate", Clock.CAR),
    CLOSURES("closures", Clock.CAR),
    COMMAND("command", Clock.PHONE, isEvent = true),
    CONNECTION("connection", Clock.PHONE, isEvent = true),
    DRIVE("drive", Clock.CAR),
    LOCATION("location", Clock.CAR),
    TIRE_PRESSURE("tire-pressure", Clock.CAR),
    VCSEC("vcsec", Clock.PHONE),
}

/** File naming shared by the one-time `.pblog` import and the log export. */
internal object PblogNames {
    /** `app.pblog`: the app-state records, one file for the whole app. */
    const val APP_FILE_NAME = "app.pblog"
    private const val SUFFIX = ".pblog"

    /** File stem for rows with a pre-ADR-0004 empty vehicle id. */
    private const val LEGACY_VEHICLE_STEM = "legacy"

    fun fileName(
        vehicleId: String,
        kind: RecordKind,
    ): String =
        if (kind == RecordKind.APP) {
            APP_FILE_NAME
        } else {
            "${vehicleId.ifEmpty { LEGACY_VEHICLE_STEM }}.${kind.id}$SUFFIX"
        }

    /**
     * The vehicle and kind a file name stands for, or null for any other file (the older raw
     * `<vehicleId>.pblog` charge files, temp files, files already renamed after import).
     */
    fun parse(fileName: String): Pair<String, RecordKind>? {
        if (fileName == APP_FILE_NAME) return "" to RecordKind.APP
        val kind = RecordKind.entries.firstOrNull { it != RecordKind.APP && fileName.endsWith(".${it.id}$SUFFIX") }
        val stem = kind?.let { fileName.removeSuffix(".${it.id}$SUFFIX") }.orEmpty()
        return if (kind == null || stem.isEmpty()) null else (if (stem == LEGACY_VEHICLE_STEM) "" else stem) to kind
    }
}
