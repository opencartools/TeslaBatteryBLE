// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.infotainment

/**
 * Who is waiting for the Infotainment session, and what a due read or a queued charge action does
 * about it (ADR-0009).
 *
 * The session is requested only on demand: when [InfotainmentPollPolicy] finds a read due or the
 * owner queues a charge action, and VCSEC says the car is awake. A sleeping car cannot answer, and
 * the request might wake it. A read and an action share the one request: whichever asks first starts
 * the handshake, and the other waits for that same one instead of asking again.
 *
 * Every wait ends in exactly one of three ways: the session is established
 * ([onSessionEstablished]), the action goes away ([onActionReleased]), or the wait is abandoned
 * ([onGaveUp], [onAsleep], [onLinkDropped], [onNotStartable]). A wait that is never ended would
 * leave the caller thinking a handshake is still in flight, so it would never start another one; a
 * real car logged VCSEC status for a whole drive without a single read that way.
 *
 * Pure, so it ports to other platforms; one instance per vehicle link, not thread-safe.
 */
class InfotainmentSessionWait {
    /** What is waiting for the session. */
    enum class Demand {
        /** A read that [InfotainmentPollPolicy] found due. */
        READ,

        /** A charge action the owner queued. */
        ACTION,
    }

    /** What a caller may do about the session. */
    enum class Step {
        /** A session exists: send the request now. */
        SEND,

        /** No session and none requested yet: request it; the request follows on success. */
        START_HANDSHAKE,

        /** A request is already in flight: do not send another; the wait follows on success. */
        WAIT_FOR_HANDSHAKE,

        /** The car is not awake: do nothing. */
        SKIP,
    }

    /** Who waits right now; a non-empty set also means a handshake is in flight. */
    private val waiting = mutableSetOf<Demand>()

    /** A read is waiting for the handshake to finish. */
    val readWaiting: Boolean get() = Demand.READ in waiting

    /** A queued charge action is waiting for the handshake to finish. */
    val actionWaiting: Boolean get() = Demand.ACTION in waiting

    /** Someone is waiting for the session. */
    val hasWaiting: Boolean get() = waiting.isNotEmpty()

    /** Whether the Infotainment session may be requested now (retries included). */
    fun mayRequest(awake: Boolean): Boolean = awake && waiting.isNotEmpty()

    /** Decides what a due read does. [awake] is the latest VCSEC verdict. */
    fun onReadDue(
        awake: Boolean,
        hasSession: Boolean,
    ): Step = decide(Demand.READ, awake, hasSession)

    /** Decides what a queued charge action does. */
    fun onActionDue(
        awake: Boolean,
        hasSession: Boolean,
    ): Step = decide(Demand.ACTION, awake, hasSession)

    /** The Infotainment session is established. Returns who was waiting for it. */
    fun onSessionEstablished(): Set<Demand> {
        val served = waiting.toSet()
        waiting.clear()
        return served
    }

    /** The queued action is gone (sent, failed or dropped): it no longer waits for the session. */
    fun onActionReleased() {
        waiting -= Demand.ACTION
    }

    /** The handshake retries ran out. */
    fun onGaveUp() = clear()

    /** VCSEC reports the car asleep: no handshake can finish. */
    fun onAsleep() = clear()

    /** The BLE link dropped or was closed: the handshake went down with it. */
    fun onLinkDropped() = clear()

    /** The handshake could not even be sent (no key, no valid VIN). */
    fun onNotStartable() = clear()

    private fun clear() {
        waiting.clear()
    }

    private fun decide(
        demand: Demand,
        awake: Boolean,
        hasSession: Boolean,
    ): Step =
        when {
            !awake -> {
                waiting -= demand
                Step.SKIP
            }

            hasSession -> {
                waiting -= demand
                Step.SEND
            }

            waiting.isNotEmpty() -> {
                waiting += demand
                Step.WAIT_FOR_HANDSHAKE
            }

            else -> {
                waiting += demand
                Step.START_HANDSHAKE
            }
        }
}
