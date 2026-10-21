// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.Command
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        max: Int = RoomBackedRecordStore.MAX_ROWS_PER_SERIES,
    ) = RoomBackedRecordStore(db, origin, maxRowsPerSeries = max, clock = { now })

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
    fun `age retention deletes records acquired more than 90 days ago`() {
        val store = store()
        val old = NOW_S - Duration.ofDays(91).seconds
        val recent = NOW_S - Duration.ofDays(89).seconds
        store.append("car", RecordKind.CHARGE, charge(old))
        store.append("car", RecordKind.CHARGE, charge(recent))
        store.append("", RecordKind.APP, BleRecord(device_timestamp = Instant.ofEpochSecond(old), app_state = AppState()))

        store.trim()

        assertEquals(listOf(charge(recent)), store.readRecent(RecordKind.CHARGE, 10).getValue("car"))
        assertEquals(1, store.rowCount())
    }

    @Test
    fun `the row backstop drops the oldest records of a series and spares the others`() {
        val store = store(max = 5)
        store.appendAll("car", RecordKind.CHARGE, (1L..8L).map { charge(NOW_S - 100 + it) })
        store.appendAll("car", RecordKind.COMMAND, (1L..3L).map { command(NOW_S - 100 + it) })

        store.trim()

        assertEquals((4L..8L).map { charge(NOW_S - 100 + it) }, store.readRecent(RecordKind.CHARGE, 10).getValue("car"))
        assertEquals(3, store.readRecent(RecordKind.COMMAND, 10).getValue("car").size)
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
