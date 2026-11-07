// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.CommandRecords
import com.dzid26.teslable.protocol.TeslaCommands

/** What a queued charge-port action needs before it can go out, once the car's sleep status is known. */
internal enum class QuickActionStep {
    /** The car is asleep (it wakes first) or no action is queued. */
    NOTHING,

    /** An Infotainment session already exists: send now, never wait for one that is not coming. */
    SEND,

    /** A read's handshake is already running; the action follows when the session arrives. */
    WAIT_FOR_SESSION,

    /** Awake with no session and no handshake in flight: ask for one. */
    START_SESSION,
}

/**
 * The one decision the link makes for a queued quick action. An existing session is not a handshake in
 * flight: treating the two alike stranded the action for as long as the session outlived the car's sleep.
 */
internal fun nextQuickActionStep(
    asleep: Boolean,
    hasPending: Boolean,
    hasSession: Boolean,
    readWaiting: Boolean,
): QuickActionStep =
    when {
        asleep || !hasPending -> QuickActionStep.NOTHING
        hasSession -> QuickActionStep.SEND
        readWaiting -> QuickActionStep.WAIT_FOR_SESSION
        else -> QuickActionStep.START_SESSION
    }

/** What the sender needs from the vehicle link it speaks for; a seam the link stays out of the way of. */
internal interface QuickActionHost {
    /** The car's name for log lines. */
    val carName: String

    fun log(message: String): Unit

    fun isAwake(): Boolean

    fun hasInfotainmentSession(): Boolean

    /** True while a read already waits for the Infotainment handshake. */
    fun readWaiting(): Boolean

    /** Asks the car for sessions; false when nothing went out. */
    fun startSession(): Boolean

    /** Runs the read that waited behind an action, or once the session arrives. */
    fun resumeWaitingRead(): Unit

    /** Wakes the car for the queued action. */
    fun wakeForAction(): Unit

    /** Forgets a wake that will never go out. */
    fun forgetQueuedWake(): Unit

    /** Sends one authenticated action; returns its request id, or null when nothing went out. */
    fun sendActuation(payload: ByteArray): String?

    fun publish(feedback: QuickActionFeedback): Unit
}

/**
 * The car screen's charge-port state machine: one queued action, the actions whose reply is
 * outstanding, and the snackbar feedback both produce. It owns no BLE state of its own; everything it
 * needs from the link goes through [QuickActionHost]. Waking is not here: it keeps the link's own
 * machinery and reports through the same toast as a pull.
 */
internal class QuickActionSender(
    private val host: QuickActionHost,
) {
    /** The one action waiting for the car to wake or the Infotainment session. */
    private var pending = false

    /** The actions whose reply is outstanding, by request uuid: two taps can be in flight at once. */
    private val sentByRequest = mutableSetOf<String>()

    val hasPending: Boolean get() = pending

    /** Queues the charge port: it goes out once the car is awake and the Infotainment session exists. */
    fun openChargePort() {
        if (!host.isAwake()) {
            pending = true
            publish(QuickActionOutcome.WAKING)
            host.wakeForAction()
        } else {
            pending = true
            startOrWait()
        }
    }

    private fun startOrWait() {
        when {
            host.hasInfotainmentSession() -> sendPending()

            // A read already asked for the session; the action follows when it arrives.
            host.readWaiting() -> host.log("${host.carName}: charge port waits for the Infotainment session")

            else -> {
                host.log("${host.carName}: requesting Infotainment session for the charge port")
                if (!host.startSession()) failPending()
            }
        }
    }

    /** Sends the action waiting for the Infotainment session, when there is one and the session exists. */
    fun sendPending() {
        if (!pending || !host.hasInfotainmentSession()) return
        pending = false
        val request = host.sendActuation(TeslaCommands.buildChargePortOpenRequest())
        if (request != null) {
            sentByRequest += request
            publish(QuickActionOutcome.SENT)
        } else {
            publish(QuickActionOutcome.FAILED)
            host.resumeWaitingRead()
        }
    }

    /** Starts the session for a queued action once the car reports awake; an existing session is used at once. */
    fun startIfAwake(asleep: Boolean) {
        val step =
            nextQuickActionStep(
                asleep = asleep,
                hasPending = pending,
                hasSession = host.hasInfotainmentSession(),
                readWaiting = host.readWaiting(),
            )
        when (step) {
            QuickActionStep.NOTHING, QuickActionStep.WAIT_FOR_SESSION -> Unit
            QuickActionStep.SEND -> sendPending()
            QuickActionStep.START_SESSION -> if (!host.startSession()) failPending()
        }
    }

    /** The car refused the action with this [request], or its reply was rejected before an answer. */
    fun failSent(request: String) {
        if (sentByRequest.remove(request)) publish(QuickActionOutcome.FAILED)
        host.resumeWaitingRead()
    }

    /** The car never answered this [request]: the snackbar reports the action instead of staying silent. */
    fun onExpired(request: String) {
        if (sentByRequest.remove(request)) publish(QuickActionOutcome.FAILED)
    }

    /** Logs the car's answer to the action and releases the read that was waiting behind it. */
    fun onReply(
        plaintext: ByteArray,
        request: String,
    ) {
        val wasAction = sentByRequest.remove(request)
        val refused = runCatching { CommandRecords.actionRefusal(plaintext) }.getOrNull() != null
        host.log("${host.carName}: charge port " + if (refused) "refused" else "accepted")
        if (wasAction && refused) publish(QuickActionOutcome.FAILED)
        host.resumeWaitingRead()
    }

    /** Nothing will send the queued action now; a wake queued for it is forgotten too. */
    fun failQueued() {
        if (!pending) return
        pending = false
        publish(QuickActionOutcome.FAILED)
        host.forgetQueuedWake()
    }

    fun failPending() {
        if (!pending) return
        pending = false
        publish(QuickActionOutcome.FAILED)
        host.resumeWaitingRead()
    }

    fun clear() {
        pending = false
        sentByRequest.clear()
    }

    private fun publish(outcome: QuickActionOutcome) = host.publish(QuickActionFeedback(QuickAction.CHARGE_PORT, outcome))
}
