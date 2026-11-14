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
 * The read that waits is kept here with its caller's payload ([T]), not only in the read's own
 * progress model: a read whose progress ring timed out still belongs to the handshake that was asked
 * for it, and goes out when that handshake arrives.
 *
 * Every wait ends in exactly one of three ways: the session is established
 * ([onSessionEstablished], the waiter then sends its request), the action goes away
 * ([onActionReleased]), or the wait is abandoned ([onGaveUp], [onAsleep], [onLinkDropped],
 * [onNotStartable]). A wait that is never ended would leave the caller thinking a handshake is still
 * in flight, so it would never start another one; a real car logged VCSEC status for a whole drive
 * without a single read that way.
 *
 * Pure, so it ports to other platforms; one instance per vehicle link, not thread-safe.
 */
class InfotainmentSessionWait<T> {
    /** What is waiting for the session. */
    enum class Demand {
        /** A read that [InfotainmentPollPolicy] found due. */
        READ,

        /** A charge action the owner queued. */
        ACTION,
    }

    /** What a read may do about the session. */
    enum class Step {
        /** A session exists: send the read now. */
        SEND,

        /** No session and none requested yet: request it; the read follows on success. */
        START_HANDSHAKE,

        /** A request is already in flight: do not send another; the read follows on success. */
        WAIT_FOR_HANDSHAKE,

        /** The car is not awake: do nothing. */
        SKIP,
    }

    /** Who waits right now. */
    private val waiting = mutableSetOf<Demand>()

    /** The read that waits for the session, with the caller's payload, until it is sent or dropped. */
    private var pendingRead: T? = null

    /** True while a read waits for the session, whether its progress ring is still up or timed out. */
    val hasWaitingRead: Boolean get() = Demand.READ in waiting || pendingRead != null

    /** Whether the Infotainment session may be requested now (retries included). */
    fun mayRequest(awake: Boolean): Boolean = awake && waiting.isNotEmpty()

    /**
     * Decides what a due read does. [awake] is the latest VCSEC verdict; [request] is what to send
     * when the session arrives, held until [takeReadRequest] or the next due read replaces it.
     */
    fun onReadDue(
        awake: Boolean,
        hasSession: Boolean,
        request: T,
    ): Step =
        when {
            !awake -> {
                waiting -= Demand.READ
                pendingRead = null
                Step.SKIP
            }

            hasSession -> {
                waiting -= Demand.READ
                pendingRead = null
                Step.SEND
            }

            waiting.isNotEmpty() -> {
                waiting += Demand.READ
                pendingRead = request
                Step.WAIT_FOR_HANDSHAKE
            }

            else -> {
                waiting += Demand.READ
                pendingRead = request
                Step.START_HANDSHAKE
            }
        }

    /**
     * Asks for the queued action's place in the session wait. True when the caller must start the
     * handshake now; false when one is already in flight or the car cannot answer.
     */
    fun requestForAction(
        awake: Boolean,
        hasSession: Boolean,
    ): Boolean {
        val start =
            when {
                !awake || hasSession -> false
                waiting.isNotEmpty() -> false
                else -> true
            }
        if (awake && !hasSession) {
            waiting += Demand.ACTION
        } else {
            waiting -= Demand.ACTION
        }
        return start
    }

    /** The read that waited for the session, with its payload; null when none does. Clears it. */
    fun takeReadRequest(): T? {
        val request = pendingRead
        pendingRead = null
        return request
    }

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
        pendingRead = null
    }
}
