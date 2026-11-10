// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/** What one vehicle link supplies to its charge-control channel; the host below forwards it. */
internal data class LinkActuationOps(
    val isAwake: () -> Boolean,
    val hasSession: () -> Boolean,
    val readWaiting: () -> Boolean,
    val readActive: () -> Boolean,
    val startSession: () -> Boolean,
    val send: (ByteArray, ActuationKey) -> Boolean,
    val wake: () -> Unit,
    val forgetWake: () -> Unit,
    val publish: (Set<ActuationKey>, ActuationFeedback?) -> Unit,
    val resumeRead: (Boolean) -> Unit,
)

/** The charge-control channel's view of one vehicle link, with the link's internals behind [LinkActuationOps]. */
internal class LinkActuationHost(
    private val ops: LinkActuationOps,
) : ActuationHost {
    override fun isAwake() = ops.isAwake()

    override fun hasSession() = ops.hasSession()

    override fun readWaiting() = ops.readWaiting()

    override fun readActive() = ops.readActive()

    override fun startSession() = ops.startSession()

    override fun send(
        payload: ByteArray,
        key: ActuationKey,
    ) = ops.send(payload, key)

    override fun wake() = ops.wake()

    override fun forgetWake() = ops.forgetWake()

    override fun publish(
        pending: Set<ActuationKey>,
        feedback: ActuationFeedback?,
    ) = ops.publish(pending, feedback)

    override fun resumeRead(accepted: Boolean) = ops.resumeRead(accepted)
}
