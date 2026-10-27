// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.ProtoLog
import com.tesla.generated.carserver.vehicle.ChargeState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream

/** The `.pblog` import and the log export around [RoomBackedRecordStore] (ADR-0014). */
class PblogMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: HistoryDatabase
    private lateinit var store: RoomBackedRecordStore

    @Before
    fun open() {
        db = testDatabase(folder.root)
        store = RoomBackedRecordStore(db, "phone-a", clock = { NOW })
    }

    @After
    fun close() = db.close()

    private fun charge(seconds: Long) =
        BleRecord(
            device_timestamp = Instant.ofEpochSecond(seconds),
            charge_state = ChargeState(timestamp = Instant.ofEpochSecond(seconds), battery_level = 60),
        )

    private fun command(seconds: Long) =
        BleRecord(device_timestamp = Instant.ofEpochSecond(seconds), command = Command(reason = Command.Reason.USER_WAKE))

    private fun appState(seconds: Long) = BleRecord(device_timestamp = Instant.ofEpochSecond(seconds), app_state = AppState())

    private fun write(
        name: String,
        vararg records: BleRecord,
    ) = File(folder.root, name).also { it.writeBytes(ProtoLog.encode(records.toList())) }

    @Test
    fun `the file names map to a vehicle and kind and back`() {
        assertEquals("Sabc123C" to RecordKind.CHARGE, PblogNames.parse("Sabc123C.charge.pblog"))
        assertEquals("Sabc123C" to RecordKind.VCSEC, PblogNames.parse("Sabc123C.vcsec.pblog"))
        assertEquals("" to RecordKind.COMMAND, PblogNames.parse("legacy.command.pblog"))
        assertEquals("Sabc123C" to RecordKind.LOCATION, PblogNames.parse("Sabc123C.location.pblog"))
        assertEquals("Sabc123C" to RecordKind.TIRE_PRESSURE, PblogNames.parse("Sabc123C.tire-pressure.pblog"))
        assertEquals("" to RecordKind.APP, PblogNames.parse("app.pblog"))
        // The older raw charge file, a temp file and an imported file are none of a reader's business.
        assertNull(PblogNames.parse("Sabc123C.pblog"))
        assertNull(PblogNames.parse("Sabc123C.charge.pblog.tmp"))
        assertNull(PblogNames.parse("Sabc123C.charge.pblog.imported"))
        assertNull(PblogNames.parse(".charge.pblog"))
        for (kind in RecordKind.entries) {
            val name = PblogNames.fileName("carA", kind)
            assertEquals(if (kind == RecordKind.APP) "" to kind else "carA" to kind, PblogNames.parse(name))
        }
        assertEquals("legacy.drive.pblog", PblogNames.fileName("", RecordKind.DRIVE))
    }

    @Test
    fun `import loads every kind of file, renames it and ignores the rest`() {
        write("carA.charge.pblog", charge(100), charge(200))
        write("carA.command.pblog", command(150))
        write("legacy.drive.pblog", charge(300))
        write("app.pblog", appState(50))
        val rawOld = write("carA.pblog", charge(1))

        PblogImporter(folder.root, store).importAll()

        assertEquals(listOf(charge(100), charge(200)), store.readRecent(RecordKind.CHARGE, 10).getValue("carA"))
        assertEquals(listOf(command(150)), store.readRecent(RecordKind.COMMAND, 10).getValue("carA"))
        assertEquals(setOf(""), store.readRecent(RecordKind.DRIVE, 10).keys)
        assertEquals(listOf(appState(50)), store.readRecent(RecordKind.APP, 10).getValue(""))
        assertEquals(5, store.rowCount())
        assertFalse(File(folder.root, "carA.charge.pblog").exists())
        assertTrue(File(folder.root, "carA.charge.pblog.imported").isFile)
        assertTrue(rawOld.isFile)
    }

    @Test
    fun `importing the same file again adds nothing`() {
        val file = write("carA.charge.pblog", charge(100), charge(200))
        val copy = file.readBytes()
        PblogImporter(folder.root, store).importAll()
        // A crash before the rename would leave the file where it was.
        File(folder.root, "carA.charge.pblog.imported").renameTo(file)
        assertEquals(copy.toList(), file.readBytes().toList())

        PblogImporter(folder.root, store).importAll()

        assertEquals(2, store.rowCount())
    }

    @Test
    fun `an export holds a framed pblog per series that decodes to the stored records`() {
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(100), charge(200)))
        store.append("carA", RecordKind.COMMAND, command(150))
        store.append("", RecordKind.DRIVE, charge(300))
        store.append("", RecordKind.APP, appState(50))

        val out = ByteArrayOutputStream()
        val exported = LogExporter.export(store, out)

        assertEquals(5, exported)
        val entries = unzip(out.toByteArray())
        assertEquals(setOf("app.pblog", "carA.charge.pblog", "carA.command.pblog", "legacy.drive.pblog"), entries.keys)
        assertEquals(listOf(charge(100), charge(200)), ProtoLog.decode(entries.getValue("carA.charge.pblog"), BleRecord.ADAPTER))
        assertEquals(listOf(appState(50)), ProtoLog.decode(entries.getValue("app.pblog"), BleRecord.ADAPTER))
    }

    @Test
    fun `exporting to a directory names the zip by UTC time and keeps the newest three`() {
        store.append("carA", RecordKind.CHARGE, charge(100))
        val dir = File(folder.root, "exports")

        val zips = (1..4).map { LogExporter.exportToDirectory(store, dir, it * 60_000L) }

        assertEquals("19700101-000100.zip", zips.first().name)
        assertEquals(zips.drop(1).map { it.name }.toSet(), dir.list()!!.toSet())
        assertEquals(setOf("carA.charge.pblog"), unzip(zips.last().readBytes()).keys)
    }

    @Test
    fun `an export imports back into an empty store unchanged`() {
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(100), charge(200)))
        val out = ByteArrayOutputStream()
        LogExporter.export(store, out)
        unzip(out.toByteArray()).forEach { (name, bytes) -> File(folder.root, name).writeBytes(bytes) }

        db.close()
        db = testDatabase(folder.root)
        store = RoomBackedRecordStore(db, "phone-a", clock = { NOW })
        PblogImporter(folder.root, store).importAll()

        assertEquals(listOf(charge(100), charge(200)), store.readRecent(RecordKind.CHARGE, 10).getValue("carA"))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return entries
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
