// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.CommandRecords
import com.dzid26.teslable.protocol.TeslaCommands

/** What a queued quick action needs before it can go out, once the car's sleep status is known. */
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

    /** Wakes the car for a queued action; [fromStack] keeps its snackbar on the stack. */
    fun wakeForAction(fromStack: Boolean): Unit

    /** Forgets a wake that will never go out, so a later one cannot report itself to the stack. */
    fun forgetQueuedWake(): Unit

    /** Sends one authenticated action; returns its request id, or null when nothing went out. */
    fun sendActuation(payload: ByteArray): String?

    fun publish(feedback: QuickActionFeedback): Unit
}

/**
 * The car screen's quick-action state machine: one queued action, the actions whose reply is
 * outstanding, and the snackbar feedback both produce. It owns no BLE state of its own; everything it
 * needs from the link goes through [QuickActionHost].
 */
internal class QuickActionSender(
    private val host: QuickActionHost,
) {
    /** The one action waiting for the car to wake or the Infotainment session; a newer tap replaces it. */
    private var pending: QuickAction? = null

    /**
     * The actions whose reply is outstanding, by request uuid: two actions can be in flight at once, and
     * a refusal has to reach the snackbar of the action it belongs to.
     */
    private val sentByRequest = mutableMapOf<String, QuickAction>()

    private val publish: (QuickAction, QuickActionOutcome) -> Unit =
        { action, outcome -> host.publish(QuickActionFeedback(action, outcome)) }

    val hasPending: Boolean get() = pending != null

    fun send(action: QuickAction) {
        when (action) {
            QuickAction.WAKE ->
                if (host.isAwake()) {
                    host.log("${host.carName}: car is already awake")
                    publish(action, QuickActionOutcome.ALREADY_AWAKE)
                } else {
                    publish(action, QuickActionOutcome.WAKING)
                    host.wakeForAction(fromStack = true)
                }

            QuickAction.CHARGE_PORT ->
                if (!host.isAwake()) {
                    // One pending action: it goes out once the wake and the session land.
                    pending = action
                    publish(action, QuickActionOutcome.WAKING)
                    host.wakeForAction(fromStack = false)
                } else {
                    pending?.let {
                        host.log(
                            "${host.carName}: replacing the pending ${it.name.lowercase()} with ${action.name.lowercase()}",
                        )
                    }
                    pending = action
                    startOrWait(action)
                }
        }
    }

    private fun startOrWait(action: QuickAction) {
        when {
            host.hasInfotainmentSession() -> sendPending()

            // A read already asked for the session; the action follows when it arrives.
            host.readWaiting() -> host.log("${host.carName}: ${action.name.lowercase()} waits for the Infotainment session")

            else -> {
                host.log("${host.carName}: requesting Infotainment session for ${action.name.lowercase()}")
                if (!host.startSession()) failPending()
            }
        }
    }

    /** Sends the action waiting for the Infotainment session, when there is one and the session exists. */
    fun sendPending() {
        val action = pending
        if (action == null || !host.hasInfotainmentSession()) return
        val payload =
            when (action) {
                QuickAction.CHARGE_PORT -> TeslaCommands.buildChargePortOpenRequest()
                // Waking goes through its own VCSEC path in send(), so it is never queued here.
                QuickAction.WAKE -> null
            }
        if (payload != null) {
            pending = null
            val request = host.sendActuation(payload)
            if (request != null) {
                sentByRequest[request] = action
                publish(action, QuickActionOutcome.SENT)
            } else {
                publish(action, QuickActionOutcome.FAILED)
                host.resumeWaitingRead()
            }
        }
    }

    /** Starts the session for a queued action once the car reports awake; an existing session is used at once. */
    fun startIfAwake(asleep: Boolean) {
        val step =
            nextQuickActionStep(
                asleep = asleep,
                hasPending = pending != null,
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
        sentByRequest.remove(request)?.let { publish(it, QuickActionOutcome.FAILED) }
        host.resumeWaitingRead()
    }

    /** The car never answered this [request]: the snackbar reports the action instead of staying silent. */
    fun onExpired(request: String) {
        sentByRequest.remove(request)?.let { publish(it, QuickActionOutcome.FAILED) }
    }

    /** Logs the car's answer to a quick action and releases the read that was waiting behind it. */
    fun onReply(
        plaintext: ByteArray,
        request: String,
    ) {
        val action = sentByRequest.remove(request)
        val refused = runCatching { CommandRecords.actionRefusal(plaintext) }.getOrNull() != null
        host.log("${host.carName}: ${action?.name?.lowercase() ?: "action"} " + if (refused) "refused" else "accepted")
        if (action != null && refused) publish(action, QuickActionOutcome.FAILED)
        host.resumeWaitingRead()
    }

    /** Nothing will send the queued action now; [wakeToo] also reports a wake that never went out. */
    fun failQueued(wakeToo: Boolean) {
        val waiting = pending
        pending = null
        val action = waiting ?: if (wakeToo) QuickAction.WAKE else null
        if (action != null) publish(action, QuickActionOutcome.FAILED)
        // One timeout reports once: the wake's own timer and the handshake's give-up both land here.
        if (wakeToo) host.forgetQueuedWake()
    }

    fun failPending() {
        val action = pending
        pending = null
        if (action != null) {
            publish(action, QuickActionOutcome.FAILED)
            host.resumeWaitingRead()
        }
    }

    fun clear() {
        pending = null
        sentByRequest.clear()
    }
}
