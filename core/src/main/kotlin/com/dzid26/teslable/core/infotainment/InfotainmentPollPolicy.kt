// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.infotainment

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import com.dzid26.teslable.protocol.asleep
import com.dzid26.teslable.protocol.locked
import com.dzid26.teslable.protocol.userPresent
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.VehicleStatus

/**
 * When to ask the car for its Infotainment state (charge, drive, closures and climate; plus location
 * while driving and tire pressure every [TIRE_PRESSURE_INTERVAL_MS], see [wantsLocation] and
 * [wantsTirePressure]).
 *
 * Any Infotainment request restarts the car's own sleep countdown (about 10 minutes), so polling it
 * while the car is merely awake keeps it awake (ADR-0009, issue #112). VCSEC status does not, and
 * keeps the BLE link alive, so it stays on its own 10 s cadence outside this class. The rules:
 *
 * 1. Never while asleep (asleep also drops the hold), and never to wake the car: reads only when
 *    VCSEC says awake and a session exists.
 * 2. A read every [READ_INTERVAL_MS] while either
 *    - the car is active (see [isActive]: charging, driving, Sentry mode, climate on), or
 *    - the hold is running: [HOLD_PRESENT_MS] after the last status change if the latest status shows
 *      user presence, otherwise [HOLD_ABSENT_MS]. Presence is re-evaluated on every status, so a
 *      person leaving cuts the remaining hold to the short one.
 * 3. Idle safety read: awake, not active and not holding, one read once [IDLE_SAFETY_INTERVAL_MS] has
 *    passed since the last read. It catches an event the status misses (remote climate, Sentry, a
 *    remote wake) when the car stays awake anyway. The interval is deliberately longer than the car's
 *    own sleep countdown (about 10 to 15 minutes): a car that would sleep is already asleep when the
 *    read comes due, so it is skipped and its sleep is never extended. The value is tuned from the
 *    real-car check. The safety read does not start a hold. [pausedUntilMillis] says when the next
 *    one is due, so the screen can show Paused while the poll waits.
 * 4. Status changes start or restart the hold: asleep to awake, any lock-state change (locking too,
 *    which catches drive-away locking), any closure changing between open and not open (a closure
 *    that is neither CLOSED nor UNKNOWN counts as open, so ajar is open), a fresh start (see
 *    [onFreshStart]) and a user request. A change in user presence alone is not a change: it flips
 *    with phone-key range and would keep resetting the car's sleep countdown.
 *
 * Reconnects: the transition memory (previous asleep, locked and closure state) survives a dropped
 * link, so a wake, lock change or door change that happened while the link was down still starts the
 * hold on the first status after it. The caller therefore does nothing at a plain reconnect.
 *
 * Pure and clock-free (the caller passes `nowMillis`) so it ports to other platforms; one instance
 * per vehicle link, not thread-safe.
 */
class InfotainmentPollPolicy {
    /** Why a read is due; the command log keeps it with the request (ADR-0008). */
    enum class Reason {
        /** The first read since the app or tracking started, or since pairing. */
        FRESH_START,

        /** The car is charging, driving, in Sentry or running its climate. */
        ACTIVE,

        /** Within the hold after a status change. */
        HOLD,

        /** The idle safety read. */
        SAFETY,
    }

    /** The reason of the read the latest [onStatus] answered true for, or null before the first one. */
    var lastReadReason: Reason? = null
        private set

    private var lastReadAtMs: Long? = null
    private var lastChangeAtMs: Long? = null
    private var lastTirePressureAtMs: Long? = null
    private var startOdometer: Int? = null
    private var odometer: Int? = null
    private var locationOdometer: Int? = null
    private var lastLocationAtMs: Long? = null
    private var moved = false
    private var tireReadAfterMove = false
    private var userPresent = false
    private var started = false
    private var charging = false
    private var driving = false
    private var sentry = false
    private var climate = false
    private var previousAsleep: Boolean? = null
    private var previousLocked: Boolean? = null
    private var previousOpenClosures: Int? = null

    /**
     * Whether the car is doing something that warrants a read every [READ_INTERVAL_MS] on its own:
     * charging, driving, Sentry mode or the climate running. In the last two the car stays awake and
     * draws hundreds of watts anyway, so reading costs nothing that matters.
     */
    private fun isActive(): Boolean = charging || driving || sentry || climate

    /**
     * A fresh start: the first READY link since the app process or tracking started (app start,
     * auto-start after boot or update, tracking switched on), or the first READY link right after
     * pairing or key enrollment. Forgets everything, including the transition memory, and starts the
     * hold. Not for a reconnect after a dropped link.
     */
    fun onFreshStart(nowMillis: Long) {
        started = true
        lastReadAtMs = null
        lastTirePressureAtMs = null
        startOdometer = null
        odometer = null
        locationOdometer = null
        lastLocationAtMs = null
        moved = false
        tireReadAfterMove = false
        charging = false
        driving = false
        sentry = false
        climate = false
        previousAsleep = null
        previousLocked = null
        previousOpenClosures = null
        lastChangeAtMs = nowMillis
    }

    /**
     * A link became READY. The first one since this policy was created (app or tracking start, as
     * the controller makes one policy per link) is a [fresh start][onFreshStart]; later ones are
     * plain reconnects and change nothing.
     */
    fun onLinkReady(nowMillis: Long) {
        if (!started) onFreshStart(nowMillis)
    }

    /**
     * The user asked for a reading (refresh button). The caller sends the read immediately; this
     * counts it as the latest read and starts the hold.
     */
    fun onUserRequest(nowMillis: Long) {
        lastReadAtMs = nowMillis
        lastChangeAtMs = nowMillis
    }

    /** The latest charge reading. Charging or Starting is activity. */
    fun onChargeReading(chargingState: ChargingStateKind?) {
        charging = chargingState == ChargingStateKind.Charging || chargingState == ChargingStateKind.Starting
    }

    /** The latest drive reading. Shift state D, R or N is activity. */
    fun onDriveReading(
        shiftState: ShiftStateKind?,
        odometerHundredthsMile: Int? = null,
    ) {
        if (odometerHundredthsMile != null) {
            odometer = odometerHundredthsMile
            val start = startOdometer ?: odometerHundredthsMile.also { startOdometer = it }
            moved = odometerHundredthsMile - start >= MOVED_HUNDREDTHS_MILE
        }
        driving = shiftState == ShiftStateKind.D || shiftState == ShiftStateKind.R || shiftState == ShiftStateKind.N
    }

    /** The latest closures reading (the category that holds Sentry mode). Sentry on is activity. */
    fun onClosuresReading(sentryOn: Boolean) {
        sentry = sentryOn
    }

    /** The latest climate reading. Climate on, or a climate keeper mode, is activity. */
    fun onClimateReading(climateOn: Boolean) {
        climate = climateOn
    }

    /**
     * Whether the location read follows the climate reply: while driving, when the odometer has
     * advanced since the last location read, and at the latest every [LOCATION_MAX_INTERVAL_MS]
     * (a car stopped in gear). Reads come at most once per VCSEC tick, so never faster than 10 s.
     */
    fun wantsLocation(nowMillis: Long): Boolean {
        if (!driving) return false
        val last = lastLocationAtMs
        val advanced = odometer != null && odometer != locationOdometer
        return last == null || advanced || nowMillis - last >= LOCATION_MAX_INTERVAL_MS - DUE_SLACK_MS
    }

    /** The location read went out at [nowMillis]. */
    fun onLocationRequested(nowMillis: Long) {
        lastLocationAtMs = nowMillis
        locationOdometer = odometer
    }

    /**
     * Whether the tire pressure read follows now: the first one after a fresh start, one more once
     * the car has moved about 200 m since then (the sensors only report while rolling), then once
     * [TIRE_PRESSURE_INTERVAL_MS] has passed since the last one. Pressures change slowly, so most
     * reads skip it.
     */
    fun wantsTirePressure(nowMillis: Long): Boolean {
        val last = lastTirePressureAtMs
        val intervalPassed = last == null || nowMillis - last >= TIRE_PRESSURE_INTERVAL_MS - DUE_SLACK_MS
        return intervalPassed || (moved && !tireReadAfterMove)
    }

    /** The tire pressure read went out at [nowMillis]. */
    fun onTirePressureRequested(nowMillis: Long) {
        lastTirePressureAtMs = nowMillis
        if (moved) tireReadAfterMove = true
    }

    /**
     * Feeds one VCSEC status and answers whether an Infotainment read is due now. Returns true at
     * most once per due read: it records the read, so the caller must then send it. With
     * [sessionReady] false (no Infotainment session or VIN yet) nothing is recorded and the answer
     * is false; the hold keeps running, so a read still follows once the session exists.
     */
    fun onStatus(
        status: VehicleStatus,
        sessionReady: Boolean,
        nowMillis: Long,
    ): Boolean {
        observe(status, nowMillis)
        if (status.asleep) {
            lastChangeAtMs = null
            charging = false
            driving = false
            sentry = false
            climate = false
            return false
        }
        if (!sessionReady) return false
        val reason =
            when {
                isActive() -> Reason.ACTIVE
                holding(nowMillis) -> Reason.HOLD
                else -> Reason.SAFETY
            }
        val interval = if (reason == Reason.SAFETY) IDLE_SAFETY_INTERVAL_MS else READ_INTERVAL_MS
        val last = lastReadAtMs
        if (last != null && nowMillis - last < interval - DUE_SLACK_MS) return false
        lastReadAtMs = nowMillis
        lastReadReason = if (last == null) Reason.FRESH_START else reason
        return true
    }

    /**
     * When the next read is due while the poll waits out [IDLE_SAFETY_INTERVAL_MS], or null when it is
     * not waiting: a read is active or on the hold cadence, the car is asleep or has not reported yet,
     * or no read has gone out (the first one is due at once). It is the moment [onStatus] next answers
     * true, so the status pill's Paused state and the read it promises cannot disagree (ADR-0009).
     */
    fun pausedUntilMillis(nowMillis: Long): Long? {
        if (previousAsleep != false || isActive() || holding(nowMillis)) return null
        return lastReadAtMs?.plus(IDLE_SAFETY_INTERVAL_MS - DUE_SLACK_MS)
    }

    private fun holding(nowMillis: Long): Boolean {
        val changedAt = lastChangeAtMs ?: return false
        return nowMillis - changedAt < if (userPresent) HOLD_PRESENT_MS else HOLD_ABSENT_MS
    }

    private fun observe(
        status: VehicleStatus,
        nowMillis: Long,
    ) {
        val openClosures = openClosureMask(status.closureStatuses)
        val wokeUp = previousAsleep == true && !status.asleep
        val lockChanged = previousLocked?.let { it != status.locked } == true
        val closureChanged = previousOpenClosures?.let { it != openClosures } == true
        if (wokeUp || lockChanged || closureChanged) lastChangeAtMs = nowMillis
        userPresent = status.userPresent
        previousAsleep = status.asleep
        previousLocked = status.locked
        previousOpenClosures = openClosures
    }

    private fun openClosureMask(closures: ClosureStatuses?): Int {
        if (closures == null) return 0
        val states =
            listOf(
                closures.frontDriverDoor,
                closures.frontPassengerDoor,
                closures.rearDriverDoor,
                closures.rearPassengerDoor,
                closures.rearTrunk,
                closures.frontTrunk,
                closures.chargePort,
                closures.tonneau,
            )
        return states.foldIndexed(0) { index, mask, state ->
            if (state.isOpen()) mask or (1 shl index) else mask
        }
    }

    private fun ClosureState_E.isOpen() = this != ClosureState_E.CLOSURESTATE_CLOSED && this != ClosureState_E.CLOSURESTATE_UNKNOWN

    companion object {
        /** Reads while active or holding, matching the VCSEC status cadence. */
        const val READ_INTERVAL_MS = 10_000L

        /** How long after a status change reads continue while the person is in or near the car. */
        const val HOLD_PRESENT_MS = 600_000L

        /** How long after a status change reads continue when nobody is present. */
        const val HOLD_ABSENT_MS = 60_000L

        /**
         * The idle safety read: longer than the car's own sleep countdown (about 10 to 15 minutes), so a
         * car that would sleep is asleep by then and is never kept awake by it. Tuned from the real-car check.
         */
        const val IDLE_SAFETY_INTERVAL_MS = 1_200_000L

        /** A location read is at most this far apart while driving, even when the odometer stands still. */
        const val LOCATION_MAX_INTERVAL_MS = 300_000L

        /** About 200 m (0.124 mi) in the odometer's hundredths of a mile. */
        const val MOVED_HUNDREDTHS_MILE = 13

        /** Between tire pressure reads: they follow a read only when this much time has passed. */
        const val TIRE_PRESSURE_INTERVAL_MS = 600_000L

        /**
         * VCSEC statuses arrive about every 10 s give or take a few ms, so a read is due slightly
         * early rather than slipping a whole tick.
         */
        const val DUE_SLACK_MS = 1_000L
    }
}
