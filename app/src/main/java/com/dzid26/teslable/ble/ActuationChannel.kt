// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response

/** The car's reason for an idempotent set it already had: the value needs no change, so it is not a refusal. */
private const val ALREADY_SET = "already_set"

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

    /** True while a read already waits for the Infotainment handshake. */
    fun readWaiting(): Boolean

    /** True while an Infotainment read sequence is in flight. */
    fun readActive(): Boolean

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
    fun resumeRead(accepted: Boolean): Unit
}

/**
 * The car screen's charge controls: one request in flight, the queued values, and the feedback both
 * produce. It owns no BLE state of its own; everything it needs from the link goes through
 * [ActuationHost]. A sleeping car is woken first, and an awake car without an Infotainment session
 * gets one requested; the queued action goes out as soon as the session exists.
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

    /** True while the session request the action needs is in flight, so the next status tick does not ask again. */
    private var sessionRequested = false

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
        publishPending()
    }

    /** The car reports awake: the queued action may request its session and go out. */
    fun onAwake() {
        if (!host.isAwake()) return
        wakeRequested = false
        sendNext()
    }

    /** The Infotainment session is up: the action that waited for it goes out. */
    fun onSessionEstablished() {
        sessionRequested = false
        sendNext()
    }

    /** The in-flight read is over: an action that waited behind it may go out now. */
    fun onReadFinished() = sendNext()

    /** The car answered [key]; null means the answer could not be used, and [fault] marks a signed rejection. */
    fun onReply(
        key: ActuationKey,
        plaintext: ByteArray?,
        fault: Boolean = false,
    ): Boolean {
        if (inFlight != key) return false
        val answered = plaintext?.let { runCatching { Response.ADAPTER.decode(it).actionStatus }.getOrNull() }
        // The car reports a value that is already at the target as an ActionStatus error with this reason.
        val accepted =
            answered?.result == OperationStatus_E.OPERATIONSTATUS_OK ||
                answered?.result_reason?.plain_text == ALREADY_SET
        val result =
            when {
                fault -> ActuationResult.REFUSED
                plaintext == null -> ActuationResult.NO_ANSWER
                accepted -> ActuationResult.ACCEPTED
                else -> ActuationResult.REFUSED
            }
        inFlight = null
        host.publish(pending.keys.toSet(), ActuationFeedback(key, result))
        host.resumeRead(result == ActuationResult.ACCEPTED)
        sendNext()
        return result == ActuationResult.ACCEPTED
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
        sessionRequested = false
        failAll(forgetWake = true)
    }

    /** The car fell asleep or the session handshake gave up; a wake already asked for is left to finish. */
    fun onInterrupted() {
        sessionRequested = false
        if (wakeRequested) return
        failAll(forgetWake = false)
    }

    private fun sendNext() {
        val ready = pending.isNotEmpty() && inFlight == null && host.isAwake() && !host.readActive()
        if (!ready) return
        if (!host.hasSession()) {
            // A read already asked for the session; the action follows when it arrives. Otherwise ask
            // once and let the handshake's retries run.
            if (!host.readWaiting() && !sessionRequested) {
                sessionRequested = true
                if (!host.startSession()) {
                    sessionRequested = false
                    failAll(forgetWake = false)
                }
            }
            return
        }
        val (key, payload) = pending.entries.first()
        pending.remove(key)
        if (host.send(payload, key)) {
            inFlight = key
            publishPending()
        } else {
            host.publish(pending.keys.toSet(), ActuationFeedback(key, ActuationResult.FAILED))
            failAll(forgetWake = false)
        }
    }

    /** Drops every queued value and reports each one failed; [forgetWake] cancels a wake that has no action left. */
    private fun failAll(forgetWake: Boolean) {
        val failed = pending.keys + listOfNotNull(inFlight)
        pending.clear()
        inFlight = null
        wakeRequested = false
        if (forgetWake) host.forgetWake()
        failed.forEach { host.publish(emptySet(), ActuationFeedback(it, ActuationResult.FAILED)) }
        host.publish(emptySet())
    }

    private fun publishPending() {
        host.publish(pending.keys.toSet() + listOfNotNull(inFlight))
    }
}
