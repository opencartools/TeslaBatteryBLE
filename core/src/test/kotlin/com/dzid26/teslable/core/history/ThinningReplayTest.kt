// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.health.ChargeSession
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
        val fullDrives = driveSessions(drives, battery, scale)
        val thinDrives2 = driveSessions(thinDrives, thinBattery, scale)
        assertEquals(fullDrives.map { it.ends() }, thinDrives2.map { it.ends() }, "drive sessions")
        fullDrives.zip(thinDrives2).forEach { (full, thin) ->
            assertTrue(abs((full.socEndPercent ?: 0f) - (thin.socEndPercent ?: 0f)) <= SOC_TOLERANCE, "drive end level: $full vs $thin")
        }
        // derived.db re-cuts its sessions after each build; however often it builds, it must end with the cut of all
        // the thinned samples, and joined with the live tail at any build it must give the cut of every reading.
        val thinCharges = chargeSessions(thinBattery)
        val thinDriveSessions = driveSessions(thinDrives, thinBattery)
        val fullCharges = chargeSessions(battery)
        BUILD_INTERVALS.forEach { interval ->
            val store = ListStore(thinBattery, thinDrives)
            store.buildEvery(interval) { built ->
                if (interval == BUILD_INTERVALS.last()) {
                    val joined =
                        withLiveTail(store.charges.toList()) { from ->
                            chargeSessions(battery.fromTime(from) { it.timestampMillis })
                        }
                    assertEquals(fullCharges, joined, "charge sessions joined at $built")
                }
            }
            assertEquals(thinCharges, store.charges, "charge sessions built every ${interval / 60_000}min")
            assertEquals(thinDriveSessions, store.storedDrives, "drive sessions built every ${interval / 60_000}min")
        }
        val used = drivePoints(battery, drives, scale).totalDrop { it.socPercent }
        val thinUsed = drivePoints(thinBattery, thinDrives, scale).totalDrop { it.socPercent }
        assertTrue(abs(used - thinUsed) <= used * DRIVE_TOLERANCE, "drive chart percent used: $used vs $thinUsed")
    }

    /** One car's sessions as `derived.db` keeps them, over the thinned samples kept so far. */
    private class ListStore(
        private val battery: List<BatterySample>,
        private val drives: List<DriveSample>,
    ) : SessionStore {
        private var keptBattery = 0
        private var keptDrives = 0
        val charges = mutableListOf<ChargeSession>()
        val storedDrives = mutableListOf<DriveSession>()

        /** Keeps the samples up to each multiple of [interval] in turn, re-cutting like a build after each. */
        fun buildEvery(
            interval: Long,
            built: (Long) -> Unit,
        ) {
            val first = minOf(battery.first().timestampMillis, drives.firstOrNull()?.timestampMillis ?: Long.MAX_VALUE)
            val last = maxOf(battery.last().timestampMillis, drives.lastOrNull()?.timestampMillis ?: Long.MIN_VALUE)
            var boundary = first
            while (boundary <= last) {
                boundary += interval
                val newBattery = battery.size - battery.fromTime(boundary) { it.timestampMillis }.size
                val newDrives = drives.size - drives.fromTime(boundary) { it.timestampMillis }.size
                val from =
                    listOfNotNull(
                        battery[keptBattery].timestampMillis.takeIf { newBattery > keptBattery },
                        drives.getOrNull(keptDrives)?.timestampMillis?.takeIf { newDrives > keptDrives },
                    ).minOrNull()
                keptBattery = newBattery
                keptDrives = newDrives
                if (from != null) {
                    recutSessions(this, from)
                    built(boundary)
                }
            }
        }

        override fun newestChargeStartBefore(millis: Long) = charges.lastOrNull { it.startMillis < millis }?.startMillis

        override fun newestDriveStartBefore(millis: Long) = storedDrives.lastOrNull { it.startMillis < millis }?.startMillis

        override fun driveReadingBefore(millis: Long): Long? {
            val kept = drives.subList(0, keptDrives)
            val before = kept.subList(0, kept.size - kept.fromTime(millis) { it.timestampMillis }.size)
            return before.lastOrNull { it.odometerInHundredthsOfAMile != null }?.timestampMillis
        }

        override fun batteryFrom(millis: Long) = battery.subList(0, keptBattery).fromTime(millis) { it.timestampMillis }

        override fun drivesFrom(millis: Long) = drives.subList(0, keptDrives).fromTime(millis) { it.timestampMillis }

        override fun replaceCharges(
            millis: Long,
            sessions: List<ChargeSession>,
        ) {
            charges.removeAll { it.startMillis >= millis }
            charges += sessions
        }

        override fun replaceDrives(
            millis: Long,
            sessions: List<DriveSession>,
        ) {
            storedDrives.removeAll { it.startMillis >= millis }
            storedDrives += sessions
        }
    }

    private fun DriveSession.ends() = listOf(startMillis, endMillis, startOdometer.toLong(), endOdometer.toLong())

    private fun <T> List<T>.oldestFirst(time: (T) -> Long) = distinctBy(time).sortedBy(time)

    private companion object {
        const val LOGS_ENV = "TESLABLE_REPLAY_LOGS"

        /** The drive chart's ends of each trip may fall on other readings; within 1% of the percent used. */
        const val DRIVE_TOLERANCE = 0.01f

        /** A drive's end levels may come from another parked reading of the same stop: within 0.3 points. */
        const val SOC_TOLERANCE = 0.3f

        /** How often the replayed derived.db builds, in car time: every 5min, every hour, every 10h. */
        val BUILD_INTERVALS = listOf(5 * 60_000L, 60 * 60_000L, 600 * 60_000L)
    }
}
