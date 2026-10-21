// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable

import android.content.Context
import com.dzid26.teslable.ble.DemoMode
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.ProtoLog
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.TeslaNames
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.ShiftState
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/**
 * DEBUG-ONLY: seeds two days of plausible battery history for the simulated
 * car so demo and screenshot builds open with a populated graph and realistic
 * projections. A DriveState record sits next to every charge record, as on a
 * real car, with the odometer moving only across the drives, so the
 * parked-drain projection can tell them apart. Every raw field is filled the way the car reports it, so the
 * chart, stats, and both projections read like real data. Runs once, only for
 * the demo car, and only when no history file exists (nor an imported one); it never reaches a
 * release build (debug source set plus [DemoMode]).
 */
internal object DemoHistory {
    // Mirrors HistoryStore's log layout; the demo seeds it directly.
    private const val HISTORY_DIR_NAME = "battery-history"
    private const val LOG_SUFFIX = ".charge.pblog"
    private const val DRIVE_LOG_SUFFIX = ".drive.pblog"

    /** FakeCarProtocol's live odometer, where the seeded drives end, so live reads continue the history. */
    private const val ODOMETER_HUNDREDTHS_OF_A_MILE = 1_234_567

    /** Odometer gain between two drive samples: a quarter-hour step at about 40 mph. */
    private const val DRIVE_STEP_HUNDREDTHS_OF_A_MILE = 1_000
    private const val CHARGE_LIMIT = 85
    private const val RATED_MILES_PER_PERCENT = 3.0f
    private const val IDEAL_MILES_PER_PERCENT = 3.2f
    private const val KWH_PER_PERCENT = 0.75f
    private const val MINUTE = 60_000L

    fun seed(context: Context) {
        val vehicleId =
            runCatching { TeslaNames.bleName(DemoMode.DEMO_VIN) }.getOrNull() ?: return
        val dir = File(context.filesDir, HISTORY_DIR_NAME)
        val file = File(dir, "$vehicleId$LOG_SUFFIX")
        // The history imports and renames the file on its first start, so seed only once.
        if (file.exists() || File(dir, file.name + ".imported").exists()) return
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val points = timeline()
        file.writeBytes(ProtoLog.encode(points.map { point -> chargeRecord(now, point) }))
        File(dir, "$vehicleId$DRIVE_LOG_SUFFIX").writeBytes(ProtoLog.encode(driveRecords(now, points)))
    }

    /** One reading in the seeded timeline; [chargeStartPercent] marks a session. */
    private data class Point(
        val minutesAgo: Long,
        val percent: Int,
        val state: ChargingStateKind,
        val chargeStartPercent: Int? = null,
        /** The car is on a drive at this reading. */
        val driving: Boolean = false,
    )

    private fun timeline(): List<Point> {
        val points =
            buildList {
                // 48 h ago: parked at 64%, then a morning drive to 55.
                addAll(segment(2880, 2700, 64, 63, 60, ChargingStateKind.Disconnected))
                addAll(segment(2700, 2580, 63, 55, 15, ChargingStateKind.Disconnected, driving = true))
                // Parked: 55 -> 54.
                addAll(segment(2580, 2460, 55, 54, 60, ChargingStateKind.Disconnected))
                // First charge of the window: a one-hour AC session to 60.
                addAll(segment(2460, 2400, 54, 60, 10, ChargingStateKind.Charging))
                // Drive to work: 60 -> 52.
                addAll(segment(2400, 2280, 60, 52, 15, ChargingStateKind.Disconnected, driving = true))
                // Parked: 52 -> 51.
                addAll(segment(2280, 2160, 52, 51, 60, ChargingStateKind.Disconnected))
                // Second charge: a two-hour session to 63.
                addAll(segment(2160, 2040, 51, 63, 10, ChargingStateKind.Charging))
                // Drive home: 63 -> 54.
                addAll(segment(2040, 1920, 63, 54, 15, ChargingStateKind.Disconnected, driving = true))
                // Parked overnight: 54 -> 52.
                addAll(segment(1920, 1560, 54, 52, 60, ChargingStateKind.Disconnected))
                // Third charge: the five-hour AC session to the limit.
                addAll(segment(1560, 1260, 52, 85, 10, ChargingStateKind.Charging))
                // Complete at the limit, then the slow parked drain to 83%.
                add(Point(1260, 85, ChargingStateKind.Complete))
                addAll(segment(1200, 400, 84, 83, 60, ChargingStateKind.Disconnected))
                // A drive after the last charge, so the drive card has stats and
                // the live reads continue from where it ends.
                addAll(segment(400, 280, 83, 79, 15, ChargingStateKind.Disconnected, driving = true))
                addAll(segment(280, 10, 79, 78, 60, ChargingStateKind.Disconnected))
            }
        // A segment's end and the next segment's start share a timestamp; keep
        // the newer one so a charge session starts on its own first sample.
        return points
            .reversed()
            .distinctBy { it.minutesAgo }
            .reversed()
    }

    private fun chargeRecord(
        now: Long,
        point: Point,
    ): BleRecord {
        val charge = sample(now, point)
        // The simulated phone acquires each reply the moment the car stamps it.
        return BleRecord(device_timestamp = charge.timestamp, charge_state = charge)
    }

    /**
     * One DriveState per reading, stamped like the charge reply. The odometer
     * steps up at every driving reading and at the first one after a drive,
     * and ends at the simulated car's live odometer.
     */
    private fun driveRecords(
        now: Long,
        points: List<Point>,
    ): List<BleRecord> {
        val moved = points.mapIndexed { index, point -> index > 0 && (point.driving || points[index - 1].driving) }
        var odometer = ODOMETER_HUNDREDTHS_OF_A_MILE - moved.count { it } * DRIVE_STEP_HUNDREDTHS_OF_A_MILE
        return points.mapIndexed { index, point ->
            if (moved[index]) odometer += DRIVE_STEP_HUNDREDTHS_OF_A_MILE
            val time = Instant.ofEpochMilli(now - point.minutesAgo * MINUTE)
            val drive =
                DriveState(
                    shift_state = if (point.driving) ShiftState(D = Void()) else ShiftState(P = Void()),
                    odometer_in_hundredths_of_a_mile = odometer,
                    timestamp = time,
                )
            BleRecord(device_timestamp = time, drive_state = drive)
        }
    }

    /** Samples a straight run from [fromAgo] to [toAgo], inclusive of the start. */
    private fun segment(
        fromAgo: Long,
        toAgo: Long,
        fromPercent: Int,
        toPercent: Int,
        stepMin: Long,
        state: ChargingStateKind,
        driving: Boolean = false,
    ): List<Point> {
        val span = (fromAgo - toAgo).coerceAtLeast(1L)
        val points = mutableListOf<Point>()
        var ago = fromAgo
        while (ago >= toAgo) {
            val fraction = (fromAgo - ago).toDouble() / span
            val percent = (fromPercent + (toPercent - fromPercent) * fraction).roundToInt()
            points +=
                Point(
                    minutesAgo = ago,
                    percent = percent,
                    state = state,
                    chargeStartPercent = if (state == ChargingStateKind.Charging) fromPercent else null,
                    driving = driving,
                )
            ago -= stepMin
        }
        // Always land exactly on the segment end, even when the step overshoots.
        if (points.last().minutesAgo != toAgo) {
            points +=
                Point(
                    minutesAgo = toAgo,
                    percent = toPercent,
                    state = state,
                    chargeStartPercent = if (state == ChargingStateKind.Charging) fromPercent else null,
                    driving = driving,
                )
        }
        return points
    }

    private fun sample(
        now: Long,
        point: Point,
    ): ChargeState {
        val rated = point.percent * RATED_MILES_PER_PERCENT
        val added = point.chargeStartPercent?.let { point.percent - it }
        return ChargeState(
            charging_state = chargingState(point.state),
            charge_limit_soc = CHARGE_LIMIT,
            battery_range = rated,
            ideal_battery_range = point.percent * IDEAL_MILES_PER_PERCENT,
            battery_level = point.percent,
            usable_battery_level = point.percent,
            charge_energy_added = added?.let { it * KWH_PER_PERCENT } ?: 0f,
            charge_miles_added_rated = added?.let { it * RATED_MILES_PER_PERCENT } ?: 0f,
            charge_miles_added_ideal = added?.let { it * IDEAL_MILES_PER_PERCENT } ?: 0f,
            timestamp = Instant.ofEpochMilli(now - point.minutesAgo * MINUTE),
        )
    }

    private fun chargingState(state: ChargingStateKind): ChargeState.ChargingState? =
        when (state) {
            ChargingStateKind.Charging -> ChargeState.ChargingState(Charging = Void())
            ChargingStateKind.Complete -> ChargeState.ChargingState(Complete = Void())
            ChargingStateKind.Disconnected -> ChargeState.ChargingState(Disconnected = Void())
            else -> null
        }
}
