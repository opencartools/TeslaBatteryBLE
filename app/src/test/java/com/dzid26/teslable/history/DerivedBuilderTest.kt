// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.protocol.LastPaidCharge
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.ShiftState
import com.tesla.generated.vcsec.VehicleStatus
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** [DerivedBuilder] over in-memory `history.db`, `events.db` and `derived.db`. */
class DerivedBuilderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dbs: TestDatabases
    private lateinit var store: RoomBackedRecordStore

    @Before
    fun open() {
        dbs = TestDatabases(folder.root)
        store = dbs.store(clock = { NOW_S * 1000 })
    }

    @After
    fun close() = dbs.close()

    private fun builder() = DerivedBuilder(store, dbs.derived)

    private val dao get() = dbs.derived.derived()

    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

    private fun charge(
        seconds: Long,
        level: Int = 50,
    ) = BleRecord(device_timestamp = at(seconds), charge_state = ChargeState(timestamp = at(seconds), battery_level = level))

    private fun batteryTimes() =
        dbs.derived
            .reads()
            .batteryBefore(Long.MAX_VALUE)
            .map { it.time / 1000 - START }

    /** A reading that carries the car's last paid charge in its undefined fields 183, 184 and 190. */
    private fun paidCharge(
        seconds: Long,
        paidAt: Long,
    ): BleRecord {
        val fields = Buffer()
        val writer = ProtoWriter(fields)
        ProtoAdapter.FLOAT.encodeWithTag(writer, 183, 4.22f)
        ProtoAdapter.STRING.encodeWithTag(writer, 184, "EUR")
        ProtoAdapter.INSTANT.encodeWithTag(writer, 190, at(paidAt))
        val state = ChargeState(timestamp = at(seconds), battery_level = 50, unknownFields = fields.readByteString())
        return BleRecord(device_timestamp = at(seconds), charge_state = state)
    }

    @Test
    fun `each paid charge the car repeats is kept once, even from readings the thinning drops`() {
        store.appendAll("carA", RecordKind.CHARGE, (10L..100L step 10).map { paidCharge(START + it, paidAt = START - 3600) })
        store.appendAll("carA", RecordKind.CHARGE, listOf(paidCharge(START + 110, paidAt = START + 105)))

        builder().build()

        assertEquals(
            listOf(LastPaidCharge((START - 3600) * 1000, 4.22f, "EUR", null), LastPaidCharge((START + 105) * 1000, 4.22f, "EUR", null)),
            dbs.derived
                .reads()
                .paidCharges()
                .map { it.toPaidCharge() },
        )
    }

    @Test
    fun `parked charge readings are thinned to one each five minutes and both sides of a step`() {
        store.appendAll("carA", RecordKind.CHARGE, (0L..1200L step 10).map { charge(START + it, level = if (it < 610) 50 else 49) })

        assertEquals(121, builder().build())

        assertEquals(listOf(0L, 300, 600, 610, 910), batteryTimes())
    }

    @Test
    fun `a second build reads only the new records and continues the thinning`() {
        store.appendAll("carA", RecordKind.CHARGE, (0L..250L step 10).map { charge(START + it) })
        builder().build()
        store.appendAll("carA", RecordKind.CHARGE, (260L..700L step 10).map { charge(START + it) })

        assertEquals(45, builder().build())

        assertEquals(listOf(0L, 300, 600), batteryTimes())
        assertEquals(0, builder().build())
    }

    @Test
    fun `a mark that no longer matches its raw row builds the series again`() {
        store.appendAll("carA", RecordKind.CHARGE, (0L..100L step 10).map { charge(START + it) })
        builder().build()
        val mark = dao.progress("carA", RecordKind.CHARGE.id)!!
        dao.setProgress(ProgressRow("carA", RecordKind.CHARGE.id, mark.lastRecordId, mark.lastTime + 1))

        assertEquals(11, builder().build())

        assertEquals(listOf(0L), batteryTimes())
    }

    @Test
    fun `drive and status records reach their tables`() {
        val drive =
            BleRecord(
                device_timestamp = at(START),
                drive_state =
                    DriveState(
                        timestamp = at(START),
                        shift_state = ShiftState(D = Void()),
                        odometer_in_hundredths_of_a_mile = 500,
                    ),
            )
        val status = BleRecord(device_timestamp = at(START + 5), vehicle_status = VehicleStatus())
        store.append("carA", RecordKind.DRIVE, drive)
        store.append("carA", RecordKind.VCSEC, status)

        builder().build()

        assertEquals(
            listOf(500),
            dbs.derived
                .reads()
                .driveBefore(Long.MAX_VALUE)
                .map { it.odometer },
        )
        assertEquals(
            "D",
            dbs.derived
                .reads()
                .driveBefore(Long.MAX_VALUE)
                .single()
                .shiftState,
        )
        assertEquals(
            listOf((START + 5) * 1000),
            dbs.derived
                .reads()
                .statusBefore(Long.MAX_VALUE)
                .map { it.time },
        )
    }

    private companion object {
        const val NOW_S = 1_800_000_000L
        const val START = NOW_S - 86_400L
    }
}
