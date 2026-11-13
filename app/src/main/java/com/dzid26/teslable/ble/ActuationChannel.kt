// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.infotainment.InfotainmentSessionWait
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response

/** The car's reason for an idempotent set it already had: the value needs no change, so it is not a refusal. */
private const val ALREADY_SET = "already_set"

/** The car's reason for a start it is already doing. */
private const val ALREADY_CHARGING = "is_charging"

/** The car's reason for a stop it already did. */
private const val NOT_CHARGING = "not_charging"

/**
 * One control the app can actuate. The channel keeps at most one request in flight and one queued
 * value per key, so repeated taps on a stepper send only the value the owner settled on.
 */
enum class ActuationKey {
    CHARGE_LIMIT,
    CHARGING_AMPS,
    CHARGE_START,
    CHARGE_STOP,
}

/** How a control ended: the car accepted it, refused it, never answered, or it could not be sent. */
enum class ActuationResult {
    ACCEPTED,
    REFUSED,
    NO_ANSWER,
    FAILED,
}

/** The channel's terminal result for one [key]. */
data class ActuationFeedback(
    val key: ActuationKey,
    val result: ActuationResult,
)

/** What the channel needs from the vehicle link; the link owns the transport and the sessions. */
internal interface ActuationHost {
    fun isAwake(): Boolean

    fun hasSession(): Boolean

    /** True while a read is in flight; a queued action waits for it so the two do not cross. */
    fun isReadRunning(): Boolean

    /** What the queued action does about the Infotainment session, whose request the reads share. */
    fun sessionStep(): InfotainmentSessionWait.Step

    /** The queued action is gone: it no longer waits for the session. */
    fun releaseSessionWait()

    /** Asks the car for sessions; false when nothing went out. */
    fun startSession(): Boolean

    /** Sends one authenticated action; false when nothing went out. */
    fun send(
        payload: ByteArray,
        key: ActuationKey,
    ): Boolean

    fun wake(): Unit

    /** Forgets a wake that will never go out. */
    fun forgetWake(): Unit

    /** Publishes the keys still queued or in flight, and the last terminal result when there is one. */
    fun publish(
        pending: Set<ActuationKey>,
        feedback: ActuationFeedback? = null,
    )

    /** Resumes a read that waited behind the action, or reads the charge state back after it. */
    fun resumeRead(
        key: ActuationKey,
        accepted: Boolean,
    ): Unit
}

/**
 * The car screen's charge controls: one request in flight, the queued values, and the feedback both
 * produce. It owns no BLE state of its own; everything it needs from the link goes through
 * [ActuationHost]. A sleeping car is woken first, and an awake car without an Infotainment session
 * gets one requested; the queued action goes out as soon as the session exists. The session request
 * is shared with the reads ([InfotainmentSessionWait]), so the channel asks the link what a queued
 * action does about it rather than keeping a request flag of its own.
 */
internal class ActuationChannel(
    private val host: ActuationHost,
) {
    /** The queued values by key; a newer value replaces the one waiting for the same control. */
    private val pending = LinkedHashMap<ActuationKey, ByteArray>()

    /** The key whose request is out, waiting for the car's reply. */
    private var inFlight: ActuationKey? = null

    /** True while the queued action is waiting for the car to wake. */
    private var wakeRequested = false

    /** True while a value is queued or a request is out. */
    val hasPending: Boolean get() = inFlight != null || pending.isNotEmpty()

    /** True while the request is out and the car's reply is due; the link keeps itself free for it. */
    val isInFlight: Boolean get() = inFlight != null

    /** Queues one action, replacing the value already waiting for the same control. */
    fun submit(
        key: ActuationKey,
        payload: ByteArray,
    ) {
        // Start and stop are one decision: the newer tap replaces the other while it is still queued.
        if (key == ActuationKey.CHARGE_START) pending.remove(ActuationKey.CHARGE_STOP)
        if (key == ActuationKey.CHARGE_STOP) pending.remove(ActuationKey.CHARGE_START)
        pending[key] = payload
        if (host.isAwake()) {
            sendNext()
        } else if (!wakeRequested) {
            wakeRequested = true
            host.wake()
        }
        host.publish(pending.keys.toSet() + listOfNotNull(inFlight))
    }

    /** The car reports awake: the queued action may request its session and go out. */
    fun onAwake() {
        if (!host.isAwake()) return
        wakeRequested = false
        sendNext()
    }

    /** The Infotainment session is up: an action that waited for it goes out. Returns whether one did. */
    fun onSessionEstablished(): Boolean = sendNext()

    /** The in-flight read is over: an action that waited behind it may go out now. */
    fun onReadFinished() {
        sendNext()
    }

    /** The car answered [key]; null means the answer could not be used, and [fault] marks a signed rejection. */
    fun onReply(
        key: ActuationKey,
        plaintext: ByteArray?,
        fault: Boolean = false,
    ) {
        if (inFlight != key) return
        val answered = plaintext?.let { runCatching { Response.ADAPTER.decode(it).actionStatus }.getOrNull() }
        val reason = answered?.result_reason?.plain_text
        // The car reports a value or a state that is already at the target as an ActionStatus error.
        val accepted =
            answered?.result == OperationStatus_E.OPERATIONSTATUS_OK ||
                reason == ALREADY_SET ||
                (key == ActuationKey.CHARGE_START && reason == ALREADY_CHARGING) ||
                (key == ActuationKey.CHARGE_STOP && reason == NOT_CHARGING)
        val result =
            when {
                fault -> ActuationResult.REFUSED
                plaintext == null -> ActuationResult.NO_ANSWER
                accepted -> ActuationResult.ACCEPTED
                else -> ActuationResult.REFUSED
            }
        inFlight = null
        host.publish(pending.keys.toSet(), ActuationFeedback(key, result))
        host.resumeRead(key, result == ActuationResult.ACCEPTED)
        sendNext()
    }

    /** The car never answered [key]: the queued values are dropped with it. */
    fun onExpired(key: ActuationKey) {
        if (inFlight != key) return
        inFlight = null
        host.publish(pending.keys.toSet(), ActuationFeedback(key, ActuationResult.NO_ANSWER))
        failAll(forgetWake = true)
    }

    /** The link dropped or the wake gave up: nothing queued can go out now. */
    fun onStopped() {
        wakeRequested = false
        failAll(forgetWake = true)
    }

    /** The car fell asleep or the session handshake gave up; a wake already asked for is left to finish. */
    fun onInterrupted() {
        if (wakeRequested) return
        failAll(forgetWake = false)
    }

    /**
     * Sends the next queued action when nothing holds it back. Returns whether one went out, so the
     * link knows a read waiting for the session must follow the action's answer instead.
     */
    private fun sendNext(): Boolean {
        val ready = inFlight == null && pending.isNotEmpty() && host.isAwake() && !host.isReadRunning()
        return when {
            !ready -> false
            host.hasSession() -> dispatchFirst()
            else -> {
                when (host.sessionStep()) {
                    InfotainmentSessionWait.Step.START_HANDSHAKE ->
                        if (!host.startSession()) failAll(forgetWake = false)

                    // A session request is already out (the read may have asked for it), the car is
                    // asleep, or the session exists and the next tick sends on it.
                    InfotainmentSessionWait.Step.WAIT_FOR_HANDSHAKE,
                    InfotainmentSessionWait.Step.SKIP,
                    InfotainmentSessionWait.Step.SEND,
                    -> Unit
                }
                false
            }
        }
    }

    private fun dispatchFirst(): Boolean {
        val (key, payload) = pending.entries.first()
        pending.remove(key)
        if (!host.send(payload, key)) {
            host.publish(pending.keys.toSet(), ActuationFeedback(key, ActuationResult.FAILED))
            failAll(forgetWake = false)
            return false
        }
        inFlight = key
        host.publish(pending.keys.toSet() + listOfNotNull(inFlight))
        return true
    }

    /**
     * Drops every queued value and reports each one failed, in queue order. The screen keeps one
     * terminal result ([TeslaConnection.lastAction]), so when two controls fail together only the last
     * one is visible there; the host still observes both, so a later reader of the log or another
     * consumer does not lose one. [forgetWake] cancels a wake that has no action left.
     */
    private fun failAll(forgetWake: Boolean) {
        val failed = pending.keys + listOfNotNull(inFlight)
        pending.clear()
        inFlight = null
        wakeRequested = false
        // Nothing queued waits for a session any more.
        host.releaseSessionWait()
        if (forgetWake) host.forgetWake()
        failed.forEach { host.publish(emptySet(), ActuationFeedback(it, ActuationResult.FAILED)) }
        host.publish(emptySet())
    }
}
