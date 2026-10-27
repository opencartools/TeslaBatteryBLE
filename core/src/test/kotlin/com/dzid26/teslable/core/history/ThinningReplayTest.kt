// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.health.chargeSessions
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays real logs through [batteryThinner] and [driveThinner] and checks that the figures the long-term history
 * feeds come out as from every reading (ADR-0014). Runs only with `TESLABLE_REPLAY_LOGS` set to a directory of
 * `.pblog` files, such as the `files/battery-history` of a phone's log pull or the `.pblog` files of an export zip;
 * skipped otherwise:
 *
 *     TESLABLE_REPLAY_LOGS=/path/to/battery-history ./gradlew :core:test --tests '*ThinningReplayTest*'
 */
class ThinningReplayTest {
    @Test
    fun `thinned real logs give the same figures as every reading`() {
        val dir = System.getenv(LOGS_ENV)?.let(::File)
        assumeTrue("$LOGS_ENV is not set", dir?.isDirectory == true)
        val vehicles =
            dir!!
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter {
                    it.endsWith(
                        ".charge.pblog",
                    )
                }.map { it.removeSuffix(".charge.pblog") }
        assumeTrue("no <vehicle>.charge.pblog in $dir", vehicles.isNotEmpty())
        vehicles.forEach { replay(dir, it) }
    }

    private fun replay(
        dir: File,
        id: String,
    ) {
        fun read(vararg names: String) =
            names.map { File(dir, it) }.filter { it.isFile }.flatMap { ProtoLog.decode(it.readBytes(), BleRecord.ADAPTER) }
        // Logs from before the per-kind files kept every kind in `<vehicle>.pblog`.
        val battery = read("$id.pblog", "$id.charge.pblog").mapNotNull { it.toBatterySample(id) }.oldestFirst { it.timestampMillis }
        val drives = read("$id.pblog", "$id.drive.pblog").mapNotNull { it.toDriveSample(id) }.oldestFirst { it.timestampMillis }
        val statuses = read("$id.pblog", "$id.vcsec.pblog").mapNotNull { it.toStatusSample(id) }.sortedBy { it.timestampMillis }
        val activityThinner = ActivityThinner()
        val activity =
            read("$id.closures.pblog", "$id.climate.pblog")
                .mapNotNull { it.toActivitySample(id) }
                .sortedBy { it.timestampMillis }
                .flatMap { activityThinner.accept(it) }
        val thinBattery = battery.thinnedBy(batteryThinner())
        val thinDrives = drives.thinnedBy(driveThinner())
        println("$id: battery ${battery.size} -> ${thinBattery.size}, drive ${drives.size} -> ${thinDrives.size}")

        val scale = FullRangeScaleEstimator.estimate(battery)
        assertEquals(scale, FullRangeScaleEstimator.estimate(thinBattery), "full-range fit")
        assertEquals(chargeSessions(battery), chargeSessions(thinBattery), "charge sessions")
        val context = ProjectionContext(scale, statuses, activity)
        assertEquals(restStats(battery, drives, context), restStats(thinBattery, thinDrives, context), "rest stats")
        assertEquals(deepSleeps(battery, drives, statuses, scale), deepSleeps(thinBattery, thinDrives, statuses, scale), "deep sleeps")
        val used = drivePoints(battery, drives, scale).totalDrop { it.socPercent }
        val thinUsed = drivePoints(thinBattery, thinDrives, scale).totalDrop { it.socPercent }
        assertTrue(abs(used - thinUsed) <= used * DRIVE_TOLERANCE, "drive chart percent used: $used vs $thinUsed")
    }

    private fun <T> List<T>.oldestFirst(time: (T) -> Long) = distinctBy(time).sortedBy(time)

    private companion object {
        const val LOGS_ENV = "TESLABLE_REPLAY_LOGS"

        /** The drive chart's ends of each trip may fall on other readings; within 1% of the percent used. */
        const val DRIVE_TOLERANCE = 0.01f
    }
}
