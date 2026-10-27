// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.ShiftState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** [SessionBuilder] after [DerivedBuilder], over in-memory databases. */
class SessionBuilderTest {
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

    private fun build() {
        DerivedBuilder(store, dbs.derived).build()
        SessionBuilder(dbs.derived).build()
    }

    private fun at(minutes: Long): Instant = Instant.ofEpochSecond(START + minutes * 60)

    private fun charge(
        minutes: Long,
        level: Int,
        charging: Boolean,
        energy: Float? = null,
    ) = BleRecord(
        device_timestamp = at(minutes),
        charge_state =
            ChargeState(
                timestamp = at(minutes),
                battery_level = level,
                charging_state =
                    if (charging) {
                        ChargeState.ChargingState(
                            Charging = Void(),
                        )
                    } else {
                        ChargeState.ChargingState(Disconnected = Void())
                    },
                charge_energy_added = energy,
            ),
    )

    private fun drive(
        minutes: Long,
        inGear: Boolean,
        odometer: Int,
    ) = BleRecord(
        device_timestamp = at(minutes),
        drive_state =
            DriveState(
                timestamp = at(minutes),
                shift_state = if (inGear) ShiftState(D = Void()) else ShiftState(P = Void()),
                odometer_in_hundredths_of_a_mile = odometer,
            ),
    )

    private fun charges() = dbs.derived.sessions().charges()

    private fun minutes(time: Long) = (time / 1000 - START) / 60

    @Test
    fun `charge and drive sessions are cut from the stored samples`() {
        store.appendAll(
            "carA",
            RecordKind.CHARGE,
            listOf(charge(0, 20, false), charge(10, 20, true, 0f), charge(70, 40, true, 13f), charge(80, 40, false)),
        )
        store.appendAll(
            "carA",
            RecordKind.DRIVE,
            listOf(drive(100, false, 1000), drive(101, true, 1000), drive(120, true, 2000), drive(121, false, 2000)),
        )

        build()

        val charge = charges().single()
        assertEquals(10L to 70L, minutes(charge.startTime) to minutes(charge.endTime))
        assertEquals(13f, charge.energyAdded)
        val drive =
            dbs.derived
                .sessions()
                .drives()
                .single()
        assertEquals(1000 to 2000, drive.startOdometer to drive.endOdometer)
        assertTrue(
            dbs.derived
                .sessionMarks()
                .dirty()
                .isEmpty(),
        )
    }

    @Test
    fun `a session still going is re-cut, not stored twice, when its next readings arrive`() {
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(0, 20, false), charge(10, 20, true, 0f), charge(40, 30, true, 6f)))
        build()
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(70, 40, true, 13f), charge(80, 40, false)))

        build()

        val charge = charges().single()
        assertEquals(10L to 70L, minutes(charge.startTime) to minutes(charge.endTime))
        assertEquals(13f, charge.energyAdded)
    }

    @Test
    fun `a charge that resumes after hours without reads stays one session`() {
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(0, 20, false), charge(10, 20, true, 0f), charge(40, 30, true, 6f)))
        build()
        // The phone was away for hours while the car kept charging: nothing in the reads ends the session.
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(240, 60, true, 25f), charge(250, 60, false)))

        build()

        val charge = charges().single()
        assertEquals(10L to 240L, minutes(charge.startTime) to minutes(charge.endTime))
        assertEquals(25f, charge.energyAdded)
    }

    @Test
    fun `a drive the phone missed after hours parked is stored`() {
        store.appendAll("carA", RecordKind.DRIVE, listOf(drive(0, false, 1000)))
        build()
        // Hours later the car is parked again with more miles: it was driven while the phone was away.
        store.appendAll("carA", RecordKind.DRIVE, listOf(drive(300, false, 3000)))

        build()

        val drive =
            dbs.derived
                .sessions()
                .drives()
                .single()
        assertEquals(1000 to 3000, drive.startOdometer to drive.endOdometer)
        assertTrue(drive.unseen)
    }

    @Test
    fun `a late reply re-cuts the sessions from its time`() {
        store.appendAll(
            "carA",
            RecordKind.CHARGE,
            listOf(charge(0, 20, false), charge(10, 20, true, 0f), charge(30, 25, true, 3f), charge(40, 25, false)),
        )
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(500, 25, false)))
        build()
        // A reply from earlier arrives late: a second charge the phone heard about only now.
        store.appendAll("carA", RecordKind.CHARGE, listOf(charge(200, 25, true, 0f), charge(260, 45, true, 13f), charge(270, 45, false)))

        build()

        assertEquals(listOf(10L, 200L), charges().map { minutes(it.startTime) })
    }

    private companion object {
        const val NOW_S = 1_800_000_000L
        const val START = NOW_S - 86_400L
    }
}
