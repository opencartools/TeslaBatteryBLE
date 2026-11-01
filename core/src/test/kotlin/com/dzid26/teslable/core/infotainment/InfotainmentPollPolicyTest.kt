// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.infotainment

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfotainmentPollPolicyTest {
    private val policy = InfotainmentPollPolicy()
    private var now = 1_000_000L

    private fun status(
        locked: Boolean = true,
        asleep: Boolean = false,
        userPresent: Boolean = false,
        closures: ClosureStatuses? = null,
    ) = VehicleStatus(
        vehicleLockState = if (locked) VehicleLockState_E.VEHICLELOCKSTATE_LOCKED else VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
        vehicleSleepStatus =
            if (asleep) VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP else VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
        userPresence =
            if (userPresent) UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT else UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        closureStatuses = closures,
    )

    private val doorOpen = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN)
    private val doorClosed = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_CLOSED)

    /** Reads at ticks 10 s, 20 s, ... [count] ticks after the change tick (the change tick itself reads too). */
    private fun ticks(count: Int) = (1..count).map { it * TICK }

    /** Advances the clock by one 10 s VCSEC tick and feeds the status; returns whether a read is due. */
    private fun tick(
        status: VehicleStatus = status(),
        sessionReady: Boolean = true,
    ): Boolean {
        now += TICK
        return policy.onStatus(status, sessionReady, now)
    }

    /** Ticks for [ms] and returns the times (ms since the start) at which a read was due. */
    private fun readsOver(
        ms: Long,
        status: VehicleStatus = status(),
    ): List<Long> {
        val start = now
        val reads = mutableListOf<Long>()
        while (now - start < ms) if (tick(status)) reads += now - start
        return reads
    }

    private fun freshStartAndFirstRead(status: VehicleStatus = status()) {
        policy.onFreshStart(now)
        assertTrue(policy.onStatus(status, true, now), "first awake status after a fresh start reads")
    }

    /** Lets the short and the long hold run out with nothing changing, so the car is idle. */
    private fun goIdle(status: VehicleStatus = status()) {
        readsOver(HOLD_PRESENT + 2 * TICK, status)
        assertFalse(tick(status), "idle")
    }

    /** After a change tick that read: the hold keeps reading every 10 s until it ends, then stops. */
    private fun assertAbsentHoldThenSilence(status: VehicleStatus = status()) {
        assertEquals(ticks(5), readsOver(15 * 60_000L, status))
    }

    @Test
    fun chargingReadsEveryTenSeconds() {
        freshStartAndFirstRead()
        goIdle()
        policy.onChargeReading(ChargingStateKind.Charging)
        repeat(200) { assertTrue(tick(), "tick $it") }
    }

    @Test
    fun startingCountsAsActive() {
        freshStartAndFirstRead()
        goIdle()
        policy.onChargeReading(ChargingStateKind.Starting)
        assertTrue(tick())
        assertTrue(tick())
    }

    @Test
    fun drivingInDriveReverseOrNeutralReadsEveryTenSeconds() {
        for (shift in listOf(ShiftStateKind.D, ShiftStateKind.R, ShiftStateKind.N)) {
            freshStartAndFirstRead()
            goIdle()
            policy.onDriveReading(shift)
            repeat(30) { assertTrue(tick(), "$shift $it") }
            policy.onDriveReading(ShiftStateKind.P)
            assertFalse(tick(), "back in park")
        }
    }

    @Test
    fun parkedAndNonChargingStatesAreNotActive() {
        val idle = ChargingStateKind.entries.filter { it != ChargingStateKind.Charging && it != ChargingStateKind.Starting }
        for (kind in listOf(null) + idle) {
            freshStartAndFirstRead()
            goIdle()
            policy.onChargeReading(kind)
            policy.onDriveReading(ShiftStateKind.P)
            assertFalse(tick(), "$kind")
        }
        policy.onDriveReading(ShiftStateKind.Invalid)
        assertFalse(tick())
        policy.onDriveReading(ShiftStateKind.SNA)
        assertFalse(tick())
    }

    @Test
    fun readsStopRightAwayWhenActivityEndsAfterTheHold() {
        freshStartAndFirstRead()
        goIdle()
        policy.onChargeReading(ChargingStateKind.Charging)
        assertTrue(tick())
        policy.onChargeReading(ChargingStateKind.Complete)
        assertFalse(tick())
        assertTrue(readsOver(15 * 60_000L).isEmpty())
    }

    @Test
    fun sentryModeReadsEveryTenSeconds() {
        freshStartAndFirstRead()
        goIdle()
        policy.onClosuresReading(sentryOn = true)
        repeat(200) { assertTrue(tick(), "tick $it") }
    }

    @Test
    fun climateOnReadsEveryTenSeconds() {
        freshStartAndFirstRead()
        goIdle()
        policy.onClimateReading(climateOn = true)
        repeat(200) { assertTrue(tick(), "tick $it") }
    }

    @Test
    fun turningSentryOffEndsActivityRightAway() {
        freshStartAndFirstRead()
        goIdle()
        policy.onClosuresReading(sentryOn = true)
        assertTrue(tick())
        policy.onClosuresReading(sentryOn = false)
        assertFalse(tick())
        assertTrue(readsOver(15 * 60_000L).isEmpty())
    }

    @Test
    fun turningClimateOffEndsActivityRightAway() {
        freshStartAndFirstRead()
        goIdle()
        policy.onClimateReading(climateOn = true)
        assertTrue(tick())
        policy.onClimateReading(climateOn = false)
        assertFalse(tick())
        assertTrue(readsOver(15 * 60_000L).isEmpty())
    }

    @Test
    fun sentryOrClimateKeepsReadingUntilBothAreOff() {
        freshStartAndFirstRead()
        goIdle()
        policy.onClosuresReading(sentryOn = true)
        policy.onClimateReading(climateOn = true)
        policy.onClosuresReading(sentryOn = false)
        assertTrue(tick(), "climate alone is still active")
        policy.onClimateReading(climateOn = false)
        assertFalse(tick(), "both off")
    }

    @Test
    fun sleepEndsSentryAndClimateActivity() {
        freshStartAndFirstRead()
        policy.onClosuresReading(sentryOn = true)
        policy.onClimateReading(climateOn = true)
        assertFalse(tick(status(asleep = true)))
        // After waking, the stale flags must not keep reads going: only the hold is left.
        assertTrue(tick(), "woke up, the hold starts")
        assertEquals(ticks(5), readsOver(15 * 60_000L))
    }

    @Test
    fun aFreshStartForgetsSentryAndClimate() {
        freshStartAndFirstRead()
        goIdle()
        policy.onClosuresReading(sentryOn = true)
        policy.onClimateReading(climateOn = true)
        policy.onFreshStart(now)
        assertTrue(policy.onStatus(status(), true, now))
        assertAbsentHoldThenSilence()
    }

    @Test
    fun aFreshStartHoldsForOneMinuteWhenNobodyIsPresent() {
        freshStartAndFirstRead()
        assertAbsentHoldThenSilence()
    }

    @Test
    fun aFreshStartHoldsForTenMinutesWhenSomeoneIsPresent() {
        val present = status(userPresent = true)
        freshStartAndFirstRead(present)
        assertEquals((1 until HOLD_PRESENT / TICK).map { it * TICK }, readsOver(HOLD_PRESENT, present))
    }

    @Test
    fun presenceIsReEvaluatedOnEveryStatus() {
        val present = status(userPresent = true)
        freshStartAndFirstRead(present)
        readsOver(5 * 60_000L, present)
        // The person leaves: five minutes after the last change, the short hold is long over.
        assertFalse(tick(status()))
        assertTrue(readsOver(15 * 60_000L).isEmpty())
    }

    @Test
    fun someoneArrivingLengthensTheHoldFromTheLastChange() {
        freshStartAndFirstRead()
        assertEquals(ticks(5), readsOver(60_000L))
        assertFalse(tick(), "the one minute hold is over")
        // The long hold applies from the same last change, so it is still running.
        assertTrue(tick(status(userPresent = true)))
        assertTrue(tick(status(userPresent = true)))
    }

    @Test
    fun aPresenceChangeAloneDoesNotStartTheHold() {
        freshStartAndFirstRead()
        goIdle()
        assertFalse(tick(status(userPresent = true)))
        assertTrue(readsOver(5 * 60_000L, status(userPresent = true)).isEmpty())
        assertTrue(readsOver(2 * 60_000L, status(userPresent = false)).isEmpty())
    }

    @Test
    fun anyLockChangeStartsTheHoldIncludingLocking() {
        freshStartAndFirstRead()
        goIdle()
        assertTrue(tick(status(locked = false)), "unlock")
        assertAbsentHoldThenSilence(status(locked = false))
        assertTrue(tick(status(locked = true)), "lock, for example driving away")
        assertAbsentHoldThenSilence(status(locked = true))
    }

    @Test
    fun stayingLockedOrUnlockedDoesNotRestartTheHold() {
        freshStartAndFirstRead()
        tick(status(locked = false))
        goIdle(status(locked = false))
        assertTrue(readsOver(8 * 60_000L, status(locked = false)).isEmpty())
    }

    @Test
    fun everyClosureChangingStartsTheHoldOpeningOrClosing() {
        val all =
            listOf(
                ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(frontPassengerDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(rearDriverDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(rearPassengerDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(rearTrunk = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(frontTrunk = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(chargePort = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(tonneau = ClosureState_E.CLOSURESTATE_OPEN),
            )
        for (closures in all) {
            freshStartAndFirstRead()
            goIdle()
            assertTrue(tick(status(closures = closures)), "opening $closures")
            assertAbsentHoldThenSilence(status(closures = closures))
            assertTrue(tick(status(closures = ClosureStatuses())), "closing $closures")
            assertAbsentHoldThenSilence(status(closures = ClosureStatuses()))
        }
    }

    @Test
    fun aClosureGoingAjarCountsAsOpening() {
        freshStartAndFirstRead()
        goIdle()
        assertTrue(tick(status(closures = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_AJAR))))
    }

    @Test
    fun anUnknownClosureIsNotOpen() {
        freshStartAndFirstRead()
        goIdle()
        assertFalse(tick(status(closures = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_UNKNOWN))))
    }

    @Test
    fun aSecondClosureChangingWhileOneIsOpenStartsTheHold() {
        freshStartAndFirstRead()
        tick(status(closures = doorOpen))
        goIdle(status(closures = doorOpen))
        val both = doorOpen.copy(chargePort = ClosureState_E.CLOSURESTATE_OPEN)
        assertTrue(tick(status(closures = both)))
    }

    @Test
    fun aClosureAlreadyOpenAtAFreshStartIsNotAChange() {
        policy.onFreshStart(now)
        assertTrue(policy.onStatus(status(closures = doorOpen), true, now))
        assertAbsentHoldThenSilence(status(closures = doorOpen))
    }

    @Test
    fun anAllClosedStatusAfterOneWithoutClosuresIsNotAChange() {
        freshStartAndFirstRead()
        goIdle()
        assertTrue(readsOver(10 * 60_000L, status(closures = ClosureStatuses())).isEmpty())
    }

    @Test
    fun wakeFromSleepStartsTheHold() {
        freshStartAndFirstRead()
        goIdle()
        readsOver(5 * TICK, status(asleep = true))
        assertTrue(tick(), "read right away on wake")
        assertAbsentHoldThenSilence()
    }

    @Test
    fun wakeReadShowingActivityTakesOver() {
        freshStartAndFirstRead()
        goIdle()
        readsOver(3 * TICK, status(asleep = true))
        assertTrue(tick())
        policy.onChargeReading(ChargingStateKind.Charging)
        repeat(20) { assertTrue(tick()) }
    }

    @Test
    fun wakeReadWaitsForTheSessionWhileTheHoldRuns() {
        freshStartAndFirstRead()
        goIdle()
        readsOver(3 * TICK, status(asleep = true))
        assertFalse(tick(sessionReady = false))
        assertTrue(tick(sessionReady = true))
    }

    @Test
    fun anAwakeIdleCarGetsOneSafetyReadEveryTwentyMinutes() {
        freshStartAndFirstRead()
        val lastHoldRead = 5 * TICK
        val reads = readsOver(70 * 60_000L)
        assertEquals(ticks(5) + listOf(1, 2, 3).map { lastHoldRead + it * SAFETY }, reads)
    }

    @Test
    fun noSafetyReadBeforeTwentyMinutesSinceTheLastRead() {
        freshStartAndFirstRead()
        readsOver(HOLD_PRESENT)
        val sinceLastRead = HOLD_PRESENT - 5 * TICK
        assertTrue(readsOver(SAFETY - sinceLastRead - 2 * TICK).isEmpty())
        assertFalse(tick())
        assertTrue(tick(), "due 20 min after the last read")
    }

    @Test
    fun theSafetyReadDoesNotStartAHold() {
        freshStartAndFirstRead()
        goIdle()
        readsOver(SAFETY)
        // Nothing but the safety reads: one in 20 min, never a run of 10 s reads after one.
        val reads = readsOver(2 * SAFETY)
        assertTrue(reads.zipWithNext { a, b -> b - a }.all { it == SAFETY }, "$reads")
        assertEquals(2, reads.size)
    }

    @Test
    fun theSafetyReadKeepsTheTwentyMinuteCadenceWhileTheCarStaysAwake() {
        freshStartAndFirstRead()
        goIdle()
        val reads = readsOver(5 * SAFETY)
        assertEquals(5, reads.size)
        assertTrue(reads.zipWithNext { a, b -> b - a }.all { it == SAFETY })
    }

    @Test
    fun theSafetyReadWaitsForTheSession() {
        freshStartAndFirstRead()
        goIdle()
        readsOver(SAFETY, status(asleep = false)) // reads happen
        now += SAFETY
        assertFalse(policy.onStatus(status(), false, now))
        assertTrue(policy.onStatus(status(), true, now + TICK))
    }

    @Test
    fun noReadsWhileAsleep() {
        freshStartAndFirstRead()
        policy.onChargeReading(ChargingStateKind.Charging)
        assertTrue(readsOver(2 * 60 * 60_000L, status(asleep = true)).isEmpty())
    }

    @Test
    fun asleepDropsTheHold() {
        policy.onFreshStart(now)
        // Asleep at the first status: the fresh-start hold is gone; the wake later starts a new one.
        assertFalse(policy.onStatus(status(asleep = true), true, now))
        readsOver(5 * 60_000L, status(asleep = true))
        assertTrue(tick(), "wake")
        assertAbsentHoldThenSilence()
    }

    @Test
    fun readingsFromBeforeSleepDoNotCountAsActiveAfterWake() {
        freshStartAndFirstRead()
        policy.onChargeReading(ChargingStateKind.Charging)
        readsOver(3 * TICK, status(asleep = true))
        assertTrue(tick(), "wake read")
        readsOver(HOLD_PRESENT)
        assertFalse(tick(), "a stale charging reading must not keep the 10 s cadence")
    }

    @Test
    fun noReadsWithoutASession() {
        policy.onFreshStart(now)
        assertFalse(tick(sessionReady = false))
        assertFalse(tick(sessionReady = false))
        assertTrue(tick(sessionReady = true), "reads as soon as the session exists, inside the hold")
    }

    @Test
    fun userRequestStartsTheHoldAndCountsAsTheRead() {
        freshStartAndFirstRead()
        goIdle()
        policy.onUserRequest(now)
        assertEquals(ticks(5), readsOver(15 * 60_000L))
    }

    @Test
    fun onlyTheFirstLinkReadyIsAFreshStart() {
        policy.onLinkReady(now)
        assertTrue(policy.onStatus(status(), true, now), "first READY reads")
        goIdle()
        policy.onLinkReady(now) // a reconnect: nothing to read
        assertTrue(readsOver(5 * 60_000L).isEmpty())
    }

    @Test
    fun aReconnectKeepsTheTransitionMemory() {
        freshStartAndFirstRead()
        goIdle()
        now += 5 * 60_000L // link down; nothing changed
        assertFalse(policy.onStatus(status(), true, now))
        assertTrue(readsOver(2 * 60_000L).isEmpty())
    }

    @Test
    fun aWakeWhileTheLinkWasDownStartsTheHoldAfterTheReconnect() {
        freshStartAndFirstRead()
        readsOver(3 * TICK, status(asleep = true))
        now += 5 * 60_000L
        assertTrue(policy.onStatus(status(), true, now))
    }

    @Test
    fun aLockOrClosureChangeWhileTheLinkWasDownStartsTheHoldAfterTheReconnect() {
        freshStartAndFirstRead()
        goIdle()
        now += 5 * 60_000L
        assertTrue(policy.onStatus(status(locked = false), true, now))
        goIdle(status(locked = false))
        now += 5 * 60_000L
        assertTrue(policy.onStatus(status(locked = false, closures = doorOpen), true, now))
    }

    @Test
    fun aFreshStartForgetsTheTransitionMemory() {
        freshStartAndFirstRead()
        goIdle()
        now += 5 * 60_000L
        policy.onFreshStart(now)
        val changed = status(locked = false, closures = doorOpen)
        assertTrue(policy.onStatus(changed, true, now))
        assertAbsentHoldThenSilence(changed)
    }

    @Test
    fun closingAfterAFreshStartWithTheDoorOpenIsAChange() {
        policy.onFreshStart(now)
        policy.onStatus(status(closures = doorOpen), true, now)
        goIdle(status(closures = doorOpen))
        assertTrue(tick(status(closures = doorClosed)))
    }

    @Test
    fun noReasonBeforeTheFirstRead() {
        assertNull(policy.lastReadReason)
    }

    @Test
    fun theFirstReadAfterAFreshStartSaysSo() {
        freshStartAndFirstRead()
        assertEquals(InfotainmentPollPolicy.Reason.FRESH_START, policy.lastReadReason)
    }

    @Test
    fun readsWithinTheHoldAreHoldReads() {
        freshStartAndFirstRead()
        assertTrue(tick())
        assertEquals(InfotainmentPollPolicy.Reason.HOLD, policy.lastReadReason)
    }

    @Test
    fun readsWhileActiveAreActiveReadsEvenInsideTheHold() {
        freshStartAndFirstRead()
        policy.onChargeReading(ChargingStateKind.Charging)
        assertTrue(tick())
        assertEquals(InfotainmentPollPolicy.Reason.ACTIVE, policy.lastReadReason)
    }

    @Test
    fun theIdleSafetyReadSaysSo() {
        freshStartAndFirstRead()
        goIdle()
        assertTrue(readsOver(SAFETY).isNotEmpty(), "the safety read comes due")
        assertEquals(InfotainmentPollPolicy.Reason.SAFETY, policy.lastReadReason)
    }

    @Test
    fun aRefusedTickKeepsTheLastReason() {
        freshStartAndFirstRead()
        assertFalse(policy.onStatus(status(), false, now + TICK))
        assertEquals(InfotainmentPollPolicy.Reason.FRESH_START, policy.lastReadReason)
    }

    @Test
    fun locationIsWantedOnlyWhileDriving() {
        freshStartAndFirstRead()
        assertFalse(policy.wantsLocation(now))
        policy.onDriveReading(ShiftStateKind.D, 100)
        assertTrue(policy.wantsLocation(now))
        policy.onLocationRequested(now)
        assertFalse(policy.wantsLocation(now + TICK), "odometer standing still")
        policy.onDriveReading(ShiftStateKind.D, 101)
        assertTrue(policy.wantsLocation(now + TICK), "odometer advanced")
        policy.onLocationRequested(now + TICK)
        assertFalse(policy.wantsLocation(now + 2 * TICK))
        assertTrue(policy.wantsLocation(now + TICK + InfotainmentPollPolicy.LOCATION_MAX_INTERVAL_MS), "five minutes at most")
        policy.onDriveReading(ShiftStateKind.P, 102)
        assertFalse(policy.wantsLocation(now + 2 * TICK))
    }

    @Test
    fun tirePressureIsWantedAfterAFreshStartThenEveryTenMinutes() {
        policy.onFreshStart(now)
        assertTrue(policy.wantsTirePressure(now))
        policy.onTirePressureRequested(now)
        assertFalse(policy.wantsTirePressure(now + TIRES - TICK))
        assertTrue(policy.wantsTirePressure(now + TIRES))
        policy.onFreshStart(now + 1)
        assertTrue(policy.wantsTirePressure(now + 1), "a fresh start reads it again")
    }

    @Test
    fun tirePressureIsReadAgainOnceAfterAbout200mOfDriving() {
        policy.onFreshStart(now)
        policy.onDriveReading(ShiftStateKind.D, 10_000)
        policy.onTirePressureRequested(now)
        policy.onDriveReading(ShiftStateKind.D, 10_012)
        assertFalse(policy.wantsTirePressure(now + TICK))
        policy.onDriveReading(ShiftStateKind.D, 10_013)
        assertTrue(policy.wantsTirePressure(now + TICK))
        policy.onTirePressureRequested(now + TICK)
        policy.onDriveReading(ShiftStateKind.D, 10_100)
        assertFalse(policy.wantsTirePressure(now + 2 * TICK), "only once per fresh start")
    }

    @Test
    fun thePauseEndsAtTheNextSafetyRead() {
        freshStartAndFirstRead()
        goIdle()
        val due = policy.pausedUntilMillis(now)
        assertNotNull(due, "idle after the hold")
        assertEquals(due, policy.pausedUntilMillis(now + TICK), "the pause does not move until its read")
        assertFalse(policy.onStatus(status(), true, due - 1), "a millisecond early is still paused")
        assertTrue(policy.onStatus(status(), true, due), "the safety read is due at the pause's end")
        assertEquals(
            due + SAFETY - InfotainmentPollPolicy.DUE_SLACK_MS,
            policy.pausedUntilMillis(due),
            "the pause restarts after the read",
        )
    }

    @Test
    fun aSleepingCarHasNoPause() {
        policy.onFreshStart(now)
        assertFalse(policy.onStatus(status(asleep = true), true, now))
        assertNull(policy.pausedUntilMillis(now), "a sleeping car is not waiting for a read")
    }

    @Test
    fun anActiveCarHasNoPause() {
        freshStartAndFirstRead()
        goIdle()
        policy.onChargeReading(ChargingStateKind.Charging)
        assertNull(policy.pausedUntilMillis(now), "charging reads every ten seconds")
    }

    @Test
    fun aUserRequestLeavesThePause() {
        freshStartAndFirstRead()
        goIdle()
        assertNotNull(policy.pausedUntilMillis(now))
        policy.onUserRequest(now)
        assertNull(policy.pausedUntilMillis(now), "the refresh starts a hold")
    }

    private companion object {
        const val TIRES = InfotainmentPollPolicy.TIRE_PRESSURE_INTERVAL_MS
        const val TICK = 10_000L
        const val HOLD_PRESENT = InfotainmentPollPolicy.HOLD_PRESENT_MS
        const val SAFETY = InfotainmentPollPolicy.IDLE_SAFETY_INTERVAL_MS
    }
}
