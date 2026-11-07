// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.protocol.ChargingStateKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** How far back [recutSessions] reads: only to where a stored session could still grow. */
class SessionStoreTest {
    private class Recording(
        val charge: StoredSpan? = null,
        val chargeBreak: BatterySample? = null,
        val drive: StoredSpan? = null,
        val driveReadings: List<Long> = emptyList(),
    ) : SessionStore {
        var batteryFrom: Long? = null
        var drivesFrom: Long? = null
        var chargesReplacedFrom: Long? = null
        var drivesReplacedFrom: Long? = null

        override fun newestChargeBefore(millis: Long) = charge

        override fun newestDriveBefore(millis: Long) = drive

        override fun chargeBreakBefore(millis: Long) = chargeBreak

        override fun driveReadingBefore(millis: Long) = driveReadings.lastOrNull { it < millis }

        override fun batteryFrom(millis: Long): List<BatterySample> = emptyList<BatterySample>().also { batteryFrom = millis }

        override fun drivesFrom(millis: Long): List<DriveSample> = emptyList<DriveSample>().also { drivesFrom = millis }

        override fun replaceCharges(
            millis: Long,
            sessions: List<ChargeSession>,
        ) {
            chargesReplacedFrom = millis
        }

        override fun replaceDrives(
            millis: Long,
            sessions: List<DriveSession>,
        ) {
            drivesReplacedFrom = millis
        }
    }

    private fun battery(
        minutes: Long,
        state: ChargingStateKind,
    ) = BatterySample(minutes * MINUTE, 50, state, 80)

    @Test
    fun `a charge closed by a later disconnect is not read again`() {
        val store =
            Recording(
                charge = StoredSpan(0, 60 * MINUTE),
                chargeBreak = battery(70, ChargingStateKind.Disconnected),
                driveReadings = listOf(DAY),
            )

        recutSessions(store, DAY + MINUTE)

        assertEquals(70 * MINUTE, store.chargesReplacedFrom)
    }

    @Test
    fun `a charge with only a hole in the reads since is cut again from its start`() {
        val store = Recording(charge = StoredSpan(0, 60 * MINUTE), driveReadings = listOf(DAY))

        recutSessions(store, DAY)

        assertEquals(0L, store.chargesReplacedFrom)
        assertEquals(-SESSION_CUT_LEAD_MILLIS, store.batteryFrom)
    }

    @Test
    fun `a short pause keeps the charge open, a long one closes it`() {
        val paused = battery(65, ChargingStateKind.Stopped)
        val soon = Recording(charge = StoredSpan(0, 60 * MINUTE), chargeBreak = paused, driveReadings = listOf(DAY))
        recutSessions(soon, 66 * MINUTE)
        assertEquals(0L, soon.chargesReplacedFrom)

        val late = Recording(charge = StoredSpan(0, 60 * MINUTE), chargeBreak = paused, driveReadings = listOf(DAY))
        recutSessions(late, 80 * MINUTE)
        assertEquals(65 * MINUTE, late.chargesReplacedFrom)
    }

    @Test
    fun `a drive followed by a parked reading long after is not read again`() {
        val store = Recording(drive = StoredSpan(0, 30 * MINUTE), driveReadings = listOf(0, 30 * MINUTE, 31 * MINUTE, 50 * MINUTE))

        recutSessions(store, DAY)

        assertEquals(50 * MINUTE, store.drivesReplacedFrom)
        assertEquals(50 * MINUTE, store.drivesFrom)
    }

    @Test
    fun `a drive that may still go on is cut again from the reading before it`() {
        val store =
            Recording(
                drive = StoredSpan(10 * MINUTE, 30 * MINUTE),
                driveReadings =
                    listOf(
                        5 * MINUTE,
                        10 * MINUTE,
                        30 * MINUTE,
                        32 * MINUTE,
                    ),
            )

        recutSessions(store, 35 * MINUTE)

        assertEquals(10 * MINUTE, store.drivesReplacedFrom)
        assertEquals(5 * MINUTE, store.drivesFrom)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
