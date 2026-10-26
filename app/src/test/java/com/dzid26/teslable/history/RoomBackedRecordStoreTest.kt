// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.ConnectionEvent
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

class RoomBackedRecordStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: HistoryDatabase
    private val now = NOW

    @Before
    fun open() {
        db = testDatabase(folder.root)
    }

    @After
    fun close() = db.close()

    private fun store(
        origin: String = "phone-a",
        maxBytes: Long = RoomBackedRecordStore.MAX_BYTES,
    ) = RoomBackedRecordStore(db, origin, maxBytes = maxBytes, clock = { now })

    private fun charge(
        carSeconds: Long,
        level: Int = 50,
        phoneSeconds: Long = carSeconds,
    ) = BleRecord(
        device_timestamp = Instant.ofEpochSecond(phoneSeconds),
        charge_state = ChargeState(timestamp = Instant.ofEpochSecond(carSeconds), battery_level = level),
    )

    private fun command(seconds: Long) =
        BleRecord(device_timestamp = Instant.ofEpochSecond(seconds), command = Command(reason = Command.Reason.USER_WAKE))

    @Test
    fun `records read back per vehicle, oldest first by the car's timestamp`() {
        val store = store()
        store.append("carA", RecordKind.CHARGE, charge(NOW_S - 10))
        store.append("carA", RecordKind.CHARGE, charge(NOW_S - 30))
        store.append("carB", RecordKind.CHARGE, charge(NOW_S - 20))
        store.append("carA", RecordKind.COMMAND, command(NOW_S - 5))

        val read = store.readRecent(RecordKind.CHARGE, 100)
        assertEquals(setOf("carA", "carB"), read.keys)
        assertEquals(listOf(charge(NOW_S - 30), charge(NOW_S - 10)), read.getValue("carA"))
        assertEquals(listOf(command(NOW_S - 5)), store.readRecent(RecordKind.COMMAND, 100).getValue("carA"))
        assertEquals(emptyMap<String, List<BleRecord>>(), store.readRecent(RecordKind.DRIVE, 100))
    }

    @Test
    fun `readRecent keeps the newest records of a vehicle`() {
        val store = store()
        store.appendAll("car", RecordKind.CHARGE, (1L..10L).map { charge(NOW_S - 100 + it) })

        assertEquals(
            listOf(charge(NOW_S - 92), charge(NOW_S - 91), charge(NOW_S - 90)),
            store.readRecent(RecordKind.CHARGE, 3).getValue("car"),
        )
    }

    @Test
    fun `a record without a car timestamp sits on the phone's time`() {
        val store = store()
        store.append("car", RecordKind.COMMAND, command(NOW_S - 20))
        store.append("car", RecordKind.COMMAND, command(NOW_S - 40))

        assertEquals(listOf(command(NOW_S - 40), command(NOW_S - 20)), store.readRecent(RecordKind.COMMAND, 10).getValue("car"))
    }

    @Test
    fun `the same record from the same origin is stored once, another origin keeps its own`() {
        store("phone-a").append("car", RecordKind.CHARGE, charge(NOW_S - 10))
        store("phone-a").append("car", RecordKind.CHARGE, charge(NOW_S - 10))
        assertEquals(1, store().rowCount())

        store("phone-b").append("car", RecordKind.CHARGE, charge(NOW_S - 10))
        assertEquals(2, store().rowCount())
    }

    @Test
    fun `a record without a car timestamp is stored once per phone time and origin`() {
        store("phone-a").append("car", RecordKind.COMMAND, command(NOW_S - 10))
        store("phone-a").append("car", RecordKind.COMMAND, command(NOW_S - 10))
        store("phone-a").append("car", RecordKind.COMMAND, command(NOW_S - 11))
        assertEquals(2, store().rowCount())

        store("phone-b").append("car", RecordKind.COMMAND, command(NOW_S - 10))
        assertEquals(3, store().rowCount())
    }

    @Test
    fun `the odometer column is filled from drive records and stays null otherwise`() {
        val store = store()
        val drive =
            BleRecord(
                device_timestamp = Instant.ofEpochSecond(NOW_S - 5),
                drive_state = DriveState(timestamp = Instant.ofEpochSecond(NOW_S - 5), odometer_in_hundredths_of_a_mile = 1_234_567),
            )
        store.append("car", RecordKind.DRIVE, drive)
        store.append("car", RecordKind.CHARGE, charge(NOW_S - 4))

        val odometers =
            listOf(RecordKind.DRIVE, RecordKind.CHARGE).map {
                db
                    .records()
                    .newest("car", it.id, 1)
                    .single()
                    .odometer
            }
        assertEquals(listOf(1_234_567L, null), odometers)
    }

    @Test
    fun `the database file reopens with its records and its unique key`() {
        val context = TestContext(folder.root)
        val driver = BundledSQLiteDriver()
        val first = HistoryDatabase.open(context, driver)
        RoomBackedRecordStore(first, "phone-a").append("car", RecordKind.COMMAND, command(NOW_S))
        first.close()

        val second = HistoryDatabase.open(context, driver)
        val reopened = RoomBackedRecordStore(second, "phone-a")
        val rows = reopened.rowCount()
        reopened.append("car", RecordKind.COMMAND, command(NOW_S))
        val rowsAfterDuplicate = reopened.rowCount()
        second.close()

        assertEquals(1, rows)
        assertEquals(1, rowsAfterDuplicate)
    }

    @Test
    fun `unknown fields survive the blob`() {
        val store = store()
        val withUnknown = BleRecord.ADAPTER.decode(charge(NOW_S - 5).encode() + byteArrayOf(0x98.toByte(), 0x06, 0x2A))
        store.append("car", RecordKind.CHARGE, withUnknown)

        val read = store.readRecent(RecordKind.CHARGE, 10).getValue("car").single()
        assertTrue(read.unknownFields.size > 0)
        assertEquals(withUnknown, read)
    }

    @Test
    fun `old records are kept because there is no age retention`() {
        val store = store()
        val ancient = NOW_S - Duration.ofDays(3 * 365).seconds
        store.append("car", RecordKind.CHARGE, charge(ancient))

        store.trim()

        assertEquals(listOf(charge(ancient)), store.readRecent(RecordKind.CHARGE, 10).getValue("car"))
    }

    @Test
    fun `phone-side events expire after 90 days and car records do not`() {
        val store = store()
        val old = NOW_S - Duration.ofDays(91).seconds
        val recent = NOW_S - Duration.ofDays(89).seconds
        store.append("car", RecordKind.COMMAND, command(old))
        store.append("car", RecordKind.COMMAND, command(recent))
        store.append("", RecordKind.APP, BleRecord(device_timestamp = Instant.ofEpochSecond(old), app_state = AppState()))
        store.append("car", RecordKind.CHARGE, charge(old))

        store.trim()

        assertEquals(listOf(command(recent)), store.readRecent(RecordKind.COMMAND, 10).getValue("car"))
        assertEquals(emptyMap<String, List<BleRecord>>(), store.readRecent(RecordKind.APP, 10))
        assertEquals(listOf(charge(old)), store.readRecent(RecordKind.CHARGE, 10).getValue("car"))
    }

    @Test
    fun `the car cap drops the oldest car records and spares the events`() {
        val store = store(maxBytes = 4_000)
        store.appendAll("car", RecordKind.CHARGE, (1L..20L).map { charge(NOW_S - 100 + it) })
        store.append("car", RecordKind.COMMAND, command(NOW_S - 50))

        store.trim()

        val charges = store.readRecent(RecordKind.CHARGE, 100).getValue("car")
        assertTrue(charges.size in 1..19)
        assertEquals((20L - charges.size + 1..20L).map { charge(NOW_S - 100 + it) }, charges)
        assertEquals(listOf(command(NOW_S - 50)), store.readRecent(RecordKind.COMMAND, 10).getValue("car"))
    }

    @Test
    fun `the event cap drops the oldest events and spares the car records`() {
        val store = store(maxBytes = 4_000)
        store.appendAll("car", RecordKind.CHARGE, listOf(charge(NOW_S - 100), charge(NOW_S - 99)))
        store.appendAll("car", RecordKind.COMMAND, (1L..8L).map { command(NOW_S - 100 + it) })

        store.trim()

        val commands = store.readRecent(RecordKind.COMMAND, 100).getValue("car")
        assertTrue(commands.size in 1..7)
        assertEquals((8L - commands.size + 1..8L).map { command(NOW_S - 100 + it) }, commands)
        assertEquals(2, store.readRecent(RecordKind.CHARGE, 100).getValue("car").size)
    }

    @Test
    fun `two different events in the same millisecond are both stored and an exact duplicate once`() {
        val store = store()
        val wake = command(NOW_S - 5)
        val handshake = BleRecord(device_timestamp = wake.device_timestamp, command = Command(reason = Command.Reason.SESSION_HANDSHAKE))
        val connected =
            BleRecord(device_timestamp = wake.device_timestamp, connection_event = ConnectionEvent(state = ConnectionEvent.State.CONNECTED))
        val disconnected =
            BleRecord(
                device_timestamp = wake.device_timestamp,
                connection_event = ConnectionEvent(state = ConnectionEvent.State.DISCONNECTED),
            )

        store.appendAll("car", RecordKind.COMMAND, listOf(wake, handshake, wake))
        store.appendAll("car", RecordKind.CONNECTION, listOf(connected, disconnected))

        assertEquals(2, store.readRecent(RecordKind.COMMAND, 10).getValue("car").size)
        assertEquals(setOf(connected, disconnected), store.readRecent(RecordKind.CONNECTION, 10).getValue("car").toSet())
        assertEquals(4, store.rowCount())
    }

    @Test
    fun `app events belong to no car and are exported as app pblog`() {
        val store = store()
        val screenOn = BleRecord(device_timestamp = Instant.ofEpochSecond(NOW_S), app_state = AppState(event = AppState.Event.SCREEN_ON))
        val tracking =
            BleRecord(device_timestamp = Instant.ofEpochSecond(NOW_S), app_state = AppState(event = AppState.Event.TRACKING_STARTED))
        store.appendAll("", RecordKind.APP, listOf(tracking, screenOn))

        assertEquals(listOf("" to RecordKind.APP), store.series())
        assertEquals(setOf(tracking, screenOn), store.readRecent(RecordKind.APP, 10).getValue("").toSet())
    }

    @Test
    fun `a database that does not open is kept aside as corrupt, not deleted`() {
        // Windows keeps the failed connection's file handle open, so the rename cannot happen there.
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val context = TestContext(folder.root)
        val damaged = "not a database, but the owner's only copy".toByteArray()
        context.getDatabasePath(HistoryDatabase.FILE_NAME).writeBytes(damaged)

        val db = HistoryDatabase.open(context, BundledSQLiteDriver())
        val fresh = RoomBackedRecordStore(db, "phone-a")
        fresh.append("car", RecordKind.COMMAND, command(NOW_S))
        val rows = fresh.rowCount()
        db.close()

        val kept = folder.root.listFiles { file -> file.name.startsWith("${HistoryDatabase.FILE_NAME}.corrupt-") }!!.single()
        assertEquals(damaged.toList(), kept.readBytes().toList())
        assertEquals(1, rows)
    }

    @Test
    fun `the series list names every vehicle and kind stored`() {
        val store = store()
        store.append("carA", RecordKind.CHARGE, charge(NOW_S - 1))
        store.append("", RecordKind.APP, BleRecord(device_timestamp = Instant.ofEpochSecond(NOW_S), app_state = AppState()))

        assertEquals(setOf("carA" to RecordKind.CHARGE, "" to RecordKind.APP), store.series().toSet())
    }

    private companion object {
        const val NOW_S = 1_800_000_000L
        const val NOW = NOW_S * 1000
    }
}
