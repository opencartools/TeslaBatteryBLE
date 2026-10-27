// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.protocol.ChargingStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargeSessionsTest {
    private fun sample(
        minutes: Long,
        level: Int,
        state: ChargingStateKind? = ChargingStateKind.Charging,
        energyAddedKwh: Float? = null,
        milesAddedRated: Float? = null,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = level,
        chargingState = state,
        chargeLimit = null,
        chargeEnergyAdded = energyAddedKwh,
        chargeMilesAddedRated = milesAddedRated,
    )

    @Test
    fun `a session first seen mid-charge counts only what was added since the first sample`() {
        // Plugged in at 20%, but the app first saw the car at 50% with 22.5 kWh already added.
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Disconnected),
                sample(300, 50, energyAddedKwh = 22.5f, milesAddedRated = 100f),
                sample(330, 65, energyAddedKwh = 33.75f, milesAddedRated = 150f),
                sample(360, 80, energyAddedKwh = 45f, milesAddedRated = 200f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(50f, session.startPercent)
        assertEquals(80f, session.endPercent)
        assertEquals(22.5f, session.energyAddedKwh)
        assertEquals(100f, session.milesAddedRated)

        // 22.5 kWh over the 30% swing is 75 kWh; the running total over that swing would say 150.
        val estimate = EnergyDeltaEstimator.estimate(session.energyAddedKwh!!, session.startPercent, session.endPercent)!!
        assertEquals(75f, estimate.usableCapacityKwh, 0.001f)
        // The rated backbone agrees: 0.225 kWh per mile over a 333 mile full range.
    }

    @Test
    fun `a counter that went down was restarted, so the last value is what the session added`() {
        // Starting still shows the previous session's totals until the car restarts them.
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Starting, energyAddedKwh = 38.4f, milesAddedRated = 170f),
                sample(1, 20, energyAddedKwh = 0f, milesAddedRated = 0f),
                sample(60, 50, energyAddedKwh = 22.5f, milesAddedRated = 100f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(22.5f, session.energyAddedKwh)
        assertEquals(100f, session.milesAddedRated)
    }

    @Test
    fun `a restart that climbs back above the first value is still a restart`() {
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Starting, energyAddedKwh = 5f),
                sample(1, 20, energyAddedKwh = 0f),
                sample(60, 50, energyAddedKwh = 22.5f),
            )
        // Subtracting the stale 5 kWh would report 17.5.
        assertEquals(22.5f, chargeSessions(samples).single().energyAddedKwh)
    }

    @Test
    fun `a total missing from the first sample counts as zero there`() {
        val samples =
            listOf(
                sample(0, 20, energyAddedKwh = 0f),
                sample(20, 40, energyAddedKwh = 13.125f, milesAddedRated = 60f),
                sample(30, 60, energyAddedKwh = 26.25f, milesAddedRated = 120f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(26.25f, session.energyAddedKwh)
        assertEquals(120f, session.milesAddedRated)
    }

    @Test
    fun `a total that is unreported or did not grow stays null`() {
        // One sample cannot say what was added while it was being watched.
        val single = chargeSessions(listOf(sample(0, 50, energyAddedKwh = 7.5f, milesAddedRated = 34f))).single()
        assertNull(single.energyAddedKwh)
        assertNull(single.milesAddedRated)

        val unreportedAtEnd = chargeSessions(listOf(sample(0, 50, energyAddedKwh = 5f), sample(10, 55))).single()
        assertNull(unreportedAtEnd.energyAddedKwh)

        val flat = chargeSessions(listOf(sample(0, 50, energyAddedKwh = 5f), sample(10, 55, energyAddedKwh = 5f))).single()
        assertNull(flat.energyAddedKwh)
    }

    @Test
    fun `Starting then Charging is one session that begins at Starting`() {
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Starting, energyAddedKwh = 0f),
                sample(1, 21, energyAddedKwh = 0.2f),
                sample(30, 35, energyAddedKwh = 11.25f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(20f, session.startPercent)
        assertEquals(35f, session.endPercent)
        assertEquals(11.25f, session.energyAddedKwh)
    }

    @Test
    fun `a short Stopped or NoPower blip does not split a session`() {
        for (pause in listOf(ChargingStateKind.Stopped, ChargingStateKind.NoPower)) {
            val samples =
                listOf(
                    sample(0, 40, energyAddedKwh = 10f),
                    sample(5, 40, pause, energyAddedKwh = 10f),
                    sample(9, 41, energyAddedKwh = 10.5f),
                    sample(60, 60, energyAddedKwh = 20f),
                )
            val sessions = chargeSessions(samples)
            assertEquals(1, sessions.size, pause.name)
            assertEquals(40f, sessions.single().startPercent, pause.name)
            assertEquals(60f, sessions.single().endPercent, pause.name)
            assertEquals(10f, sessions.single().energyAddedKwh, pause.name)
        }
    }

    @Test
    fun `a pause that resumes through Starting stays in the session`() {
        val samples =
            listOf(
                sample(0, 40, energyAddedKwh = 10f),
                sample(5, 40, ChargingStateKind.Stopped, energyAddedKwh = 10f),
                sample(7, 40, ChargingStateKind.Starting, energyAddedKwh = 10f),
                sample(8, 41, energyAddedKwh = 10.2f),
            )
        assertEquals(1, chargeSessions(samples).size)
    }

    @Test
    fun `a pause shorter than ten minutes is bridged and ten minutes is not`() {
        val nineMinutes = listOf(sample(0, 40), sample(1, 40, ChargingStateKind.Stopped), sample(9, 41))
        assertEquals(1, chargeSessions(nineMinutes).size)

        val tenMinutes = listOf(sample(0, 40), sample(1, 40, ChargingStateKind.Stopped), sample(10, 41))
        assertEquals(2, chargeSessions(tenMinutes).size)
    }

    @Test
    fun `a long Stopped gap makes two sessions that each carry their own deltas`() {
        val samples =
            listOf(
                sample(0, 30, energyAddedKwh = 0f),
                sample(40, 50, energyAddedKwh = 15f),
                sample(45, 50, ChargingStateKind.Stopped, energyAddedKwh = 15f),
                sample(120, 50, ChargingStateKind.Stopped, energyAddedKwh = 15f),
                sample(125, 50, ChargingStateKind.Starting, energyAddedKwh = 15f),
                sample(160, 70, energyAddedKwh = 30f),
            )
        val sessions = chargeSessions(samples)
        assertEquals(2, sessions.size)
        assertEquals(30f, sessions[0].startPercent)
        assertEquals(50f, sessions[0].endPercent)
        assertEquals(15f, sessions[0].energyAddedKwh)
        assertEquals(50f, sessions[1].startPercent)
        assertEquals(70f, sessions[1].endPercent)
        assertEquals(15f, sessions[1].energyAddedKwh)
    }

    @Test
    fun `a pause is counted from the last charging sample, not from the session start`() {
        val samples =
            listOf(
                sample(0, 40),
                sample(8, 41),
                sample(9, 41, ChargingStateKind.Stopped),
                sample(15, 42),
                sample(16, 42, ChargingStateKind.NoPower),
                sample(30, 43),
            )
        // The first pause lasts 7 minutes and the second 15, whatever the session's age.
        assertEquals(2, chargeSessions(samples).size)
    }

    @Test
    fun `Complete ends a session even when charging resumes minutes later`() {
        val samples =
            listOf(
                sample(0, 60, energyAddedKwh = 0f),
                sample(30, 84, energyAddedKwh = 18f),
                sample(32, 85, ChargingStateKind.Complete, energyAddedKwh = 18.75f),
                // The limit was raised and the car started again.
                sample(35, 85, ChargingStateKind.Starting, energyAddedKwh = 18.75f),
                sample(60, 95, energyAddedKwh = 26.25f),
            )
        val sessions = chargeSessions(samples)
        assertEquals(2, sessions.size)
        // The Complete sample belongs to neither session.
        assertEquals(84f, sessions[0].endPercent)
        assertEquals(18f, sessions[0].energyAddedKwh)
        assertEquals(85f, sessions[1].startPercent)
        assertEquals(7.5f, sessions[1].energyAddedKwh)
    }

    @Test
    fun `Disconnected ends a session`() {
        val samples =
            listOf(
                sample(0, 40, energyAddedKwh = 5f),
                sample(20, 50, energyAddedKwh = 12.5f),
                sample(25, 50, ChargingStateKind.Disconnected),
                sample(30, 50, ChargingStateKind.Starting, energyAddedKwh = 0f),
                sample(60, 60, energyAddedKwh = 7.5f),
            )
        val sessions = chargeSessions(samples)
        assertEquals(2, sessions.size)
        assertEquals(7.5f, sessions[0].energyAddedKwh)
        assertEquals(7.5f, sessions[1].energyAddedKwh)
    }

    @Test
    fun `a missing or unrecognised state ends a session`() {
        for (state in listOf(null, ChargingStateKind.Unknown, ChargingStateKind.Calibrating)) {
            val samples = listOf(sample(0, 40), sample(5, 41, state), sample(6, 42))
            assertEquals(2, chargeSessions(samples).size, state.toString())
        }
    }

    @Test
    fun `a dropped link does not end a session`() {
        val samples =
            listOf(
                sample(0, 40, energyAddedKwh = 10f),
                // The app was out of range for three hours; the car's totals kept running.
                sample(180, 80, energyAddedKwh = 40f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(30f, session.energyAddedKwh)
        assertEquals(75f, EnergyDeltaEstimator.estimate(30f, session.startPercent, session.endPercent)!!.usableCapacityKwh, 0.001f)
    }

    @Test
    fun `history without charging has no sessions`() {
        assertTrue(chargeSessions(emptyList()).isEmpty())
        val idle =
            listOf(
                sample(0, 80, ChargingStateKind.Disconnected),
                sample(5, 80, ChargingStateKind.Stopped),
                sample(10, 79, ChargingStateKind.NoPower),
            )
        assertTrue(chargeSessions(idle).isEmpty())
    }

    @Test
    fun `the rated constant needs miles`() {
        val session = ChargeSession(0L, 3_600_000L, startPercent = 20f, endPercent = 60f, energyAddedKwh = 26.25f, milesAddedRated = 120f)
        assertEquals(0.21875f, session.kwhPerMile!!, 0.0001f)

        val noMiles = session.copy(milesAddedRated = null)
        assertNull(noMiles.kwhPerMile)
    }

    @Test
    fun `a session stays open while charging and through a short pause`() {
        val last = 100 * 60_000L
        assertTrue(chargeSessionOpen(ChargingStateKind.Charging, last, last + 3_600_000L))
        assertTrue(chargeSessionOpen(ChargingStateKind.Starting, null, last))
        assertTrue(chargeSessionOpen(ChargingStateKind.Stopped, last, last + 9 * 60_000L))
        assertTrue(chargeSessionOpen(ChargingStateKind.NoPower, last, last + 9 * 60_000L))
    }

    @Test
    fun `a session is over after a long pause or once the car is not charging`() {
        val last = 100 * 60_000L
        assertFalse(chargeSessionOpen(ChargingStateKind.Stopped, last, last + 10 * 60_000L))
        assertFalse(chargeSessionOpen(ChargingStateKind.Stopped, null, last))
        assertFalse(chargeSessionOpen(ChargingStateKind.Complete, last, last + 1))
        assertFalse(chargeSessionOpen(ChargingStateKind.Disconnected, last, last + 1))
        assertFalse(chargeSessionOpen(null, last, last + 1))
    }

    @Test
    fun `after a charge the energy is shown on exactly one of the hero and the last charge line`() {
        for (end in listOf(ChargingStateKind.Complete, ChargingStateKind.Disconnected)) {
            val samples =
                listOf(
                    sample(0, 80, ChargingStateKind.Charging),
                    sample(30, 85, ChargingStateKind.Charging),
                    sample(31, 85, end),
                )
            for (minutesAfter in listOf(1L, 6L, 11L)) {
                // The hero reads the live state, the history line the newest sample; both say the session is over.
                val now = (30 + minutesAfter) * 60_000L
                assertFalse(chargeSessionOpen(samples, now))
                assertFalse(chargeSessionOpen(end, 30 * 60_000L, now))
            }
        }
    }

    @Test
    fun `a paused session hands over after the ten minute margin`() {
        val samples =
            listOf(
                sample(0, 80, ChargingStateKind.Charging),
                sample(30, 85, ChargingStateKind.Charging),
                sample(31, 85, ChargingStateKind.Stopped),
            )
        assertTrue(chargeSessionOpen(samples, 36 * 60_000L))
        assertFalse(chargeSessionOpen(samples, 41 * 60_000L))
    }

    @Test
    fun `while a new session runs the last finished charge stays and then gets swapped`() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, energyAddedKwh = 10f),
                sample(30, 70, ChargingStateKind.Charging, energyAddedKwh = 31.3f),
                sample(60, 69, ChargingStateKind.Disconnected),
                sample(600, 20, ChargingStateKind.Charging, energyAddedKwh = 5f),
            )
        val during = lastFinishedChargeStats(samples, 601 * 60_000L)!!
        assertEquals(30 * 60_000L, during.sinceMillis)
        assertEquals(31.3f, during.energyKwh)

        val ended = samples + sample(640, 55, ChargingStateKind.Complete)
        val after = lastFinishedChargeStats(ended, 700 * 60_000L)!!
        assertEquals(600 * 60_000L, after.sinceMillis)
        assertEquals(5f, after.energyKwh)
    }

    @Test
    fun `there is nothing to show while the first charge is still running`() {
        val samples = listOf(sample(0, 50, ChargingStateKind.Disconnected), sample(10, 52, ChargingStateKind.Charging))
        assertNull(lastFinishedChargeStats(samples, 11 * 60_000L))
    }

    @Test
    fun `a session knows when it began and ended and its average power`() {
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Disconnected),
                sample(10, 20, energyAddedKwh = 0f),
                sample(70, 50, energyAddedKwh = 11f),
                sample(130, 78, energyAddedKwh = 22f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(10 * 60_000L, session.startMillis)
        assertEquals(130 * 60_000L, session.endMillis)
        assertEquals(2 * 3_600_000L, session.durationMillis)
        assertEquals(11f, session.averageKw!!, 0.001f)
    }

    @Test
    fun `no average power without energy or with under five minutes of data`() {
        assertNull(chargeSessions(listOf(sample(0, 20), sample(30, 40))).single().averageKw)
        val brief = chargeSessions(listOf(sample(0, 20, energyAddedKwh = 0f), sample(4, 21, energyAddedKwh = 1f))).single()
        assertNull(brief.averageKw)
        val longer = chargeSessions(listOf(sample(0, 20, energyAddedKwh = 0f), sample(6, 21, energyAddedKwh = 1f))).single()
        assertEquals(10f, longer.averageKw!!, 0.001f)
    }

    @Test
    fun `only the newest session is in progress, and only while the car is still charging`() {
        val samples =
            listOf(
                sample(0, 20),
                sample(30, 60),
                sample(40, 60, ChargingStateKind.Complete),
                sample(600, 30),
                sample(610, 35),
            )
        val live = chargeSessions(samples, nowMillis = 611 * 60_000L)
        assertEquals(listOf(false, true), live.map { it.inProgress })
        val idle = chargeSessions(samples + sample(620, 40, ChargingStateKind.Disconnected), nowMillis = 700 * 60_000L)
        assertEquals(listOf(false, false), idle.map { it.inProgress })
    }
}
