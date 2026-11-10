// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/**
 * The bounded retry behind one pull-to-wake.
 *
 * A wake is one RKE command on the VCSEC session, and the BLE transport gives no delivery
 * guarantee: on a weak link the frame is simply lost, the command log records a timeout, and
 * nothing sent it again, so the owner had to pull a second time. A sleeping car may not answer
 * status polls either, so a poll cannot tell a lost wake from a slow one. Only a VCSEC status
 * that says awake confirms it; until that arrives, send the wake again on the session already
 * established, and give up once the bound is spent so a car out of range does not keep the
 * retry alive for the life of the link.
 *
 * The retry also owns whether a wake is in flight. A link reset cancels the timer, so the flag
 * must not survive it: a wake nobody is waiting for must not fire on a reconnect hours later.
 *
 * Pure and clock-free; one instance per vehicle link, not thread-safe.
 */
internal class WakeRetry(
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    /** What the retry timer should do on this tick. */
    enum class Action {
        /** The car confirmed it is awake: stop. */
        CONFIRMED,

        /** The wake was cancelled; a later reconnect must not resume it. */
        CANCELLED,

        /** Nothing to send yet: the VCSEC handshake has not produced a session. */
        WAIT,

        /** Send the wake (again) on the existing VCSEC session. */
        SEND,

        /** The bound is spent: stop and say so. */
        GIVE_UP,
    }

    private var attempts = 0

    /** True between [start] and a confirmation, a cancellation, or the bound. */
    var isPending = false
        private set

    /** The number of wake frames sent since the last [start]. */
    val sent: Int get() = attempts

    /** Starts a new pull-to-wake. */
    fun start() {
        isPending = true
        attempts = 0
    }

    /** Ends the wake without a confirmation; a later reconnect must not resume it. */
    fun cancel() {
        isPending = false
    }

    /**
     * Decides the next step from [awake] (the latest VCSEC verdict) and whether a VCSEC session
     * exists. A tick without a session does not spend an attempt: the handshake owns that wait
     * and has its own bound.
     */
    fun onTick(
        awake: Boolean,
        hasVcsecSession: Boolean,
    ): Action =
        when {
            !isPending -> Action.CANCELLED
            awake -> {
                isPending = false
                Action.CONFIRMED
            }
            attempts >= maxAttempts -> {
                isPending = false
                Action.GIVE_UP
            }
            !hasVcsecSession -> Action.WAIT
            else -> {
                attempts++
                Action.SEND
            }
        }

    companion object {
        /**
         * Wake sends per pull, the first included: the first one, then five retries 5 s apart, so
         * the bound is the same 30 s the status-only refresh used to wait. Design choice.
         */
        const val MAX_ATTEMPTS = 6
    }
}
