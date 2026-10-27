// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.ConnectionEvent
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.LocationState
import com.tesla.generated.carserver.vehicle.TirePressureState
import com.tesla.generated.vcsec.VehicleStatus
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

    private lateinit var dbs: TestDatabases

    @Before
    fun open() {
        dbs = TestDatabases(folder.root)
    }

    @After
    fun close() = dbs.close()

    private fun store(
        origin: String = "phone-a",
        maxBytes: Long = RoomBackedRecordStore.MAX_BYTES,
        eventMaxBytes: Long = RoomBackedRecordStore.MAX_BYTES,
    ) = dbs.store(origin, maxBytes, eventMaxBytes, clock = { NOW })

    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

    private fun charge(
        carSeconds: Long,
        level: Int = 50,
        phoneSeconds: Long = carSeconds,
    ) = BleRecord(
        device_timestamp = at(phoneSeconds),
        charge_state = ChargeState(timestamp = at(carSeconds), battery_level = level),
    )

    private fun command(seconds: Long) = BleRecord(device_timestamp = at(seconds), command = Command(reason = Command.Reason.USER_WAKE))

    private fun series(
        vehicleId: String,
        kind: RecordKind,
    ) = SeriesRef(vehicleId, kind)

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
    fun `readNewest keeps the newest by time and readRange bounds the time`() {
        val store = store()
        // Inserted out of time order: the readers go by time, never by row id.
        store.appendAll("car", RecordKind.CHARGE, listOf(5L, 1L, 4L, 2L, 3L).map { charge(NOW_S - 100 + it) })
        val car = series("car", RecordKind.CHARGE)

        assertEquals(listOf(3L, 4L, 5L).map { charge(NOW_S - 100 + it) }, store.readNewest(car, 3))
        val from = (NOW_S - 100 + 2) * 1000
        val to = (NOW_S - 100 + 4) * 1000
        assertEquals(listOf(2L, 3L, 4L).map { charge(NOW_S - 100 + it) }, store.readRange(car, from, to))
        assertEquals(emptyList<BleRecord>(), store.readRange(car, 0, 1))
    }

    @Test
    fun `every car-clock kind is timed by its own timestamp and a reply without one by the phone`() {
        val store = store()
        val stamp = at(NOW_S - 50)
        val phone = at(NOW_S - 10)
        val replies =
            mapOf(
                RecordKind.CHARGE to BleRecord(device_timestamp = phone, charge_state = ChargeState(timestamp = stamp)),
                RecordKind.CLIMATE to BleRecord(device_timestamp = phone, climate_state = ClimateState(timestamp = stamp)),
                RecordKind.CLOSURES to BleRecord(device_timestamp = phone, closures_state = ClosuresState(timestamp = stamp)),
                RecordKind.DRIVE to BleRecord(device_timestamp = phone, drive_state = DriveState(timestamp = stamp)),
                RecordKind.LOCATION to BleRecord(device_timestamp = phone, location_state = LocationState(timestamp = stamp)),
                RecordKind.TIRE_PRESSURE to BleRecord(device_timestamp = phone, tire_pressure_state = TirePressureState(timestamp = stamp)),
            )
        for ((kind, reply) in replies) store.append("car", kind, reply)
        val unstamped = BleRecord(device_timestamp = phone, charge_state = ChargeState(battery_level = 40))
        store.append("car", RecordKind.CHARGE, unstamped)
        store.append("car", RecordKind.VCSEC, BleRecord(device_timestamp = phone, vehicle_status = VehicleStatus()))

        val stampMs = stamp.toEpochMilli()
        for (kind in replies.keys) {
            val times = timesOf(series("car", kind))
            assertEquals("$kind", if (kind == RecordKind.CHARGE) listOf(stampMs, phone.toEpochMilli()) else listOf(stampMs), times.sorted())
        }
        // VCSEC replies carry no time: the phone's arrival time is the series' clock.
        assertEquals(listOf(phone.toEpochMilli()), timesOf(series("car", RecordKind.VCSEC)))
        assertEquals(2, store.readNewest(series("car", RecordKind.CHARGE), 10).size)
    }

    private fun timesOf(series: SeriesRef): List<Long> =
        dbs.history.records().newest(checkNotNull(dbs.history.meta().seriesId(series.vehicleId, series.kind.id)), 10).map {
            it.time
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
    fun `events are stored once per series, phone time, origin and content`() {
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
                device_timestamp = at(NOW_S - 5),
                drive_state = DriveState(timestamp = at(NOW_S - 5), odometer_in_hundredths_of_a_mile = 1_234_567),
            )
        store.append("car", RecordKind.DRIVE, drive)
        store.append("car", RecordKind.CHARGE, charge(NOW_S - 4))

        val odometers =
            listOf(RecordKind.DRIVE, RecordKind.CHARGE).map {
                dbs.history
                    .records()
                    .newest(checkNotNull(dbs.history.meta().seriesId("car", it.id)), 1)
                    .single()
                    .odometer
            }
        assertEquals(listOf(1_234_567L, null), odometers)
    }

    @Test
    fun `the database files reopen with their records and their unique keys`() {
        val context = TestContext(folder.root)
        val driver = BundledSQLiteDriver()
        val first = HistoryDatabase.open(context, driver, log = {})
        val firstEvents = EventsDatabase.open(context, driver, log = {})
        RoomBackedRecordStore(first, firstEvents, "phone-a").run {
            append("car", RecordKind.CHARGE, charge(NOW_S))
            append("car", RecordKind.COMMAND, command(NOW_S))
        }
        first.close()
        firstEvents.close()

        val second = HistoryDatabase.open(context, driver, log = {})
        val secondEvents = EventsDatabase.open(context, driver, log = {})
        val reopened = RoomBackedRecordStore(second, secondEvents, "phone-a")
        val rows = reopened.rowCount()
        reopened.append("car", RecordKind.CHARGE, charge(NOW_S))
        reopened.append("car", RecordKind.COMMAND, command(NOW_S))
        val rowsAfterDuplicates = reopened.rowCount()
        second.close()
        secondEvents.close()

        assertEquals(2, rows)
        assertEquals(2, rowsAfterDuplicates)
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
    fun `phone-side events expire after 90 days and car records do not`() {
        val store = store()
        val old = NOW_S - Duration.ofDays(91).seconds
        val recent = NOW_S - Duration.ofDays(89).seconds
        store.append("car", RecordKind.COMMAND, command(old))
        store.append("car", RecordKind.COMMAND, command(recent))
        store.append("", RecordKind.APP, BleRecord(device_timestamp = at(old), app_state = AppState()))
        store.append("car", RecordKind.CHARGE, charge(old))

        store.trim()

        assertEquals(listOf(command(recent)), store.readRecent(RecordKind.COMMAND, 10).getValue("car"))
        assertEquals(emptyMap<String, List<BleRecord>>(), store.readRecent(RecordKind.APP, 10))
        assertEquals(listOf(charge(old)), store.readRecent(RecordKind.CHARGE, 10).getValue("car"))
    }

    @Test
    fun `nothing is dropped while the file is under its cap`() {
        val store = store()
        store.appendAll("car", RecordKind.CHARGE, (1L..20L).map { charge(NOW_S - 100 + it) })

        store.trim()

        assertEquals(20, store.readNewest(series("car", RecordKind.CHARGE), 100).size)
    }

    @Test
    fun `merging closures and climate logs keeps both kinds for one car`() {
        val merged = mergeByVehicle(mapOf("car" to listOf(command(1))), mapOf("car" to listOf(command(2)), "other" to listOf(command(3))))

        assertEquals(listOf(command(1), command(2)), merged.getValue("car"))
        assertEquals(listOf(command(3)), merged.getValue("other"))
    }

    @Test
    fun `a far-future timestamp does not stretch the span the cap deletes from`() {
        val log = RecordLog(dbs.history, Lookups(dbs.history.meta(), "phone-a"))
        val car = series("car", RecordKind.CHARGE)
        val farFuture = charge(NOW_S + Duration.ofDays(3650).seconds)
        log.append(car, (1L..20L).map { charge(NOW_S - 100 + it) } + farFuture, NOW)

        log.trimToCap(maxBytes = 1, nowMillis = NOW, maxPasses = 1)

        // One pass takes the oldest tenth of the real span (two rows); nothing else goes, the stray row included.
        assertEquals((3L..20L).map { charge(NOW_S - 100 + it) } + farFuture, log.readNewest(car, 100))
    }

    @Test
    fun `reads of an unknown series create nothing`() {
        val store = store()
        val ghostCar = series("ghost", RecordKind.CHARGE)
        val ghostEvents = series("ghost", RecordKind.COMMAND)

        assertEquals(emptyList<BleRecord>(), store.readNewest(ghostCar, 5))
        assertEquals(emptyList<BleRecord>(), store.readRange(ghostCar, 0, Long.MAX_VALUE))
        assertEquals(emptyList<StoredRecord>(), store.stream(ghostCar, 0, 5))
        assertEquals(emptyList<BleRecord>(), store.readNewest(ghostEvents, 5))
        assertEquals(emptyMap<String, List<BleRecord>>(), store.readRecent(RecordKind.DRIVE, 5))

        assertEquals(0, dbs.history.meta().allSeries().size)
        assertEquals(0, dbs.events.meta().allSeries().size)
    }

    @Test
    fun `the events file has a smaller cap than the car records`() {
        assertEquals(256_000_000L, RoomBackedRecordStore.EVENT_MAX_BYTES)
        assertTrue(RoomBackedRecordStore.EVENT_MAX_BYTES < RoomBackedRecordStore.MAX_BYTES)
    }

    @Test
    fun `the car cap drops the oldest of the time span across series and leaves the events file alone`() {
        val store = store(maxBytes = 1)
        store.appendAll("carA", RecordKind.CHARGE, (1L..20L).map { charge(NOW_S - 100 + it) })
        store.appendAll("carB", RecordKind.CHARGE, (1L..20L).map { charge(NOW_S - 100 + it) })
        store.append("car", RecordKind.COMMAND, command(NOW_S - 50))

        store.trim()

        for (car in listOf("carA", "carB")) {
            val charges = store.readNewest(series(car, RecordKind.CHARGE), 100)
            assertTrue(charges.size in 1..19)
            assertEquals((20L - charges.size + 1..20L).map { charge(NOW_S - 100 + it) }, charges)
        }
        assertEquals(listOf(command(NOW_S - 50)), store.readRecent(RecordKind.COMMAND, 10).getValue("car"))
    }

    @Test
    fun `the event cap drops the oldest events and leaves the car records alone`() {
        val store = store(eventMaxBytes = 1)
        store.appendAll("car", RecordKind.CHARGE, listOf(charge(NOW_S - 100), charge(NOW_S - 99)))
        store.appendAll("car", RecordKind.COMMAND, (1L..8L).map { command(NOW_S - 100 + it) })

        store.trim()

        val commands = store.readNewest(series("car", RecordKind.COMMAND), 100)
        assertTrue(commands.size in 1..7)
        assertEquals((8L - commands.size + 1..8L).map { command(NOW_S - 100 + it) }, commands)
        assertEquals(2, store.readNewest(series("car", RecordKind.CHARGE), 100).size)
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

        assertEquals(2, store.readNewest(series("car", RecordKind.COMMAND), 10).size)
        assertEquals(setOf(connected, disconnected), store.readNewest(series("car", RecordKind.CONNECTION), 10).toSet())
        assertEquals(4, store.rowCount())
    }

    @Test
    fun `app events belong to no car and every series of both files is listed`() {
        val store = store()
        val screenOn = BleRecord(device_timestamp = at(NOW_S), app_state = AppState(event = AppState.Event.SCREEN_ON))
        val tracking = BleRecord(device_timestamp = at(NOW_S), app_state = AppState(event = AppState.Event.TRACKING_STARTED))
        store.appendAll("", RecordKind.APP, listOf(tracking, screenOn))
        store.append("carA", RecordKind.CHARGE, charge(NOW_S - 1))

        assertEquals(setOf(series("", RecordKind.APP), series("carA", RecordKind.CHARGE)), store.series().toSet())
        assertEquals(setOf(tracking, screenOn), store.readNewest(series("", RecordKind.APP), 10).toSet())
    }

    @Test
    fun `the exporter's stream pages a series in storage order`() {
        val store = store()
        store.appendAll("car", RecordKind.CHARGE, (1L..5L).map { charge(NOW_S - 100 + it) })
        val car = series("car", RecordKind.CHARGE)

        val first = store.stream(car, 0, 2)
        val second = store.stream(car, first.last().id, 10)

        assertEquals(2, first.size)
        assertEquals(3, second.size)
        assertEquals((NOW_S - 100 + 3) * 1000, second.first().time)
        assertEquals(emptyList<StoredRecord>(), store.stream(car, second.last().id, 10).toList())
    }

    @Test
    fun `databases that do not open are kept aside as corrupt, not deleted`() {
        // Windows keeps the failed connection's file handle open, so the rename cannot happen there.
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val context = TestContext(folder.root)
        val damaged = "not a database, but the owner's only copy".toByteArray()
        context.getDatabasePath(HistoryDatabase.FILE_NAME).writeBytes(damaged)
        context.getDatabasePath(EventsDatabase.FILE_NAME).writeBytes(damaged)

        val history = HistoryDatabase.open(context, BundledSQLiteDriver(), log = {})
        val events = EventsDatabase.open(context, BundledSQLiteDriver(), log = {})
        val fresh = RoomBackedRecordStore(history, events, "phone-a")
        fresh.append("car", RecordKind.CHARGE, charge(NOW_S))
        fresh.append("car", RecordKind.COMMAND, command(NOW_S))
        val rows = fresh.rowCount()
        history.close()
        events.close()

        for (name in listOf(HistoryDatabase.FILE_NAME, EventsDatabase.FILE_NAME)) {
            val kept = folder.root.listFiles { file -> file.name.startsWith("$name.corrupt-") }!!.single()
            assertEquals(damaged.toList(), kept.readBytes().toList())
        }
        assertEquals(2, rows)
    }

    private companion object {
        const val NOW_S = 1_800_000_000L
        const val NOW = NOW_S * 1000
    }
}
