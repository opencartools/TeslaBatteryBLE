// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.ProtoLog
import com.squareup.wire.ofEpochSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BleLogsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val vehicleSuffixes =
        listOf(
            ".charge.pblog",
            ".vcsec.pblog",
            ".drive.pblog",
            ".closures.pblog",
            ".climate.pblog",
            ".location.pblog",
            ".tire-pressure.pblog",
            ".connection.pblog",
            ".command.pblog",
        )

    private fun command(seconds: Long) =
        BleRecord(
            device_timestamp = ofEpochSecond(seconds, 0),
            rssi = -60,
            command = Command(reason = Command.Reason.USER_WAKE),
        )

    private fun appState(
        seconds: Long,
        event: AppState.Event = AppState.Event.SCREEN_ON,
    ) = BleRecord(device_timestamp = ofEpochSecond(seconds, 0), app_state = AppState(event = event))

    @Test
    fun `app pblog is not read as any kind of vehicle log`() {
        val dir = folder.root
        AppLog(dir).append(appState(1))
        assertTrue(File(dir, "app.pblog").isFile)
        for (suffix in vehicleSuffixes) {
            assertEquals("app.pblog under $suffix", emptyMap<String, List<BleRecord>>(), VehicleLogs(dir, suffix).readAll())
        }
    }

    @Test
    fun `a vehicle's files are not read as the app log and the legacy raw files stay unread`() {
        val dir = folder.root
        val vehicles = VehicleLogs(dir, ".command.pblog")
        vehicles.append("Sabc123C", command(1))
        // The older raw ChargeState log of ADR-0006: `<vehicleId>.pblog`.
        File(dir, "Sabc123C.pblog").writeBytes(ProtoLog.encode(listOf(command(2))))

        assertEquals(emptyList<BleRecord>(), AppLog(dir).readAll())
        for (suffix in vehicleSuffixes - ".command.pblog") {
            assertEquals(emptyMap<String, List<BleRecord>>(), VehicleLogs(dir, suffix).readAll())
        }
        assertEquals(setOf("Sabc123C"), VehicleLogs(dir, ".command.pblog").readAll().keys)
    }

    @Test
    fun `the app log and the command logs are separate files in one directory`() {
        val dir = folder.root
        val app = AppLog(dir)
        val vehicles = VehicleLogs(dir, ".command.pblog")
        app.append(appState(1, AppState.Event.SCREEN_OFF))
        vehicles.append("carA", command(2))
        vehicles.append("carB", command(3))
        app.append(appState(4, AppState.Event.SCREEN_ON))

        assertEquals(listOf(AppState.Event.SCREEN_OFF, AppState.Event.SCREEN_ON), AppLog(dir).readAll().map { it.app_state?.event })
        val read = VehicleLogs(dir, ".command.pblog").readAll()
        assertEquals(setOf("carA", "carB"), read.keys)
        assertEquals(command(2), read.getValue("carA").single())
        assertEquals(setOf("app.pblog", "carA.command.pblog", "carB.command.pblog"), dir.list()!!.toSet())
    }

    @Test
    fun `the app log keeps the same cap and trim as the vehicle logs`() {
        val dir = folder.root
        val app = AppLog(dir)
        repeat(CappedLog.MAX_RECORDS_PER_FILE) { app.append(appState(it.toLong())) }
        assertEquals(CappedLog.MAX_RECORDS_PER_FILE, AppLog(dir).readAll().size)

        // The append that goes over the cap trims to the slack below it and keeps the newest.
        app.append(appState(CappedLog.MAX_RECORDS_PER_FILE.toLong()))
        val kept = AppLog(dir).readAll()
        assertEquals(CappedLog.MAX_RECORDS_PER_FILE - CappedLog.TRIM_SLACK, kept.size)
        assertEquals(CappedLog.MAX_RECORDS_PER_FILE.toLong(), kept.last().device_timestamp?.epochSecond)
        assertFalse(File(dir, "app.pblog.tmp").exists())
    }

    @Test
    fun `a reloaded command log trims by the count of the file it read`() {
        val dir = folder.root
        val first = VehicleLogs(dir, ".command.pblog")
        repeat(CappedLog.MAX_RECORDS_PER_FILE) { first.append("car", command(it.toLong())) }

        val reopened = VehicleLogs(dir, ".command.pblog")
        assertEquals(CappedLog.MAX_RECORDS_PER_FILE, reopened.readAll().getValue("car").size)
        reopened.append("car", command(-1))
        assertEquals(
            CappedLog.MAX_RECORDS_PER_FILE - CappedLog.TRIM_SLACK,
            VehicleLogs(dir, ".command.pblog").readAll().getValue("car").size,
        )
    }

    @Test
    fun mergingClosuresAndClimateLogsKeepsBothKindsForOneCar() {
        val dir = folder.root
        val closures = VehicleLogs(dir, ".closures.pblog")
        val climate = VehicleLogs(dir, ".climate.pblog")
        closures.append("car", command(1))
        climate.append("car", command(2))
        climate.append("other", command(3))

        val merged = mergeByVehicle(closures.readAll(), climate.readAll())

        assertEquals(listOf(command(1), command(2)), merged.getValue("car"))
        assertEquals(listOf(command(3)), merged.getValue("other"))
    }
}
