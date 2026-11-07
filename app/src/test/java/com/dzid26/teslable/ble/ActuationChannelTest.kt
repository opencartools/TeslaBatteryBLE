// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.protocol.TeslaChargeCommands
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The charge controls' queue, session wait and terminal feedback, without a BLE link. */
class ActuationChannelTest {
    private val host = FakeHost()
    private val channel = ActuationChannel(host)

    @Test
    fun `a sleeping car is woken and the action waits for its session`() {
        channel.submit(ActuationKey.CHARGE_LIMIT, TeslaChargeCommands.buildChargeLimitRequest(80))

        assertEquals(1, host.wakeCalls)
        assertTrue(host.sent.isEmpty())
        assertEquals(setOf(ActuationKey.CHARGE_LIMIT), host.pendingStates.last())

        host.awake = true
        channel.onAwake()
        assertEquals(1, host.sessionStarts)

        host.session = true
        channel.onSessionEstablished()
        assertEquals(listOf(ActuationKey.CHARGE_LIMIT), host.sent.map { it.first })
        assertEquals(setOf(ActuationKey.CHARGE_LIMIT), host.pendingStates.last())

        channel.onReply(ActuationKey.CHARGE_LIMIT, actionStatus(ok = true))

        assertEquals(ActuationFeedback(ActuationKey.CHARGE_LIMIT, accepted = true), host.feedback.single())
        assertTrue(host.pendingStates.last().isEmpty())
        assertTrue(host.resumedReads == 1)
    }

    @Test
    fun `an action waits for the in-flight read`() {
        host.awake = true
        host.session = true
        host.readInFlight = true

        channel.submit(ActuationKey.CHARGE_LIMIT, TeslaChargeCommands.buildChargeLimitRequest(80))

        assertTrue(host.sent.isEmpty())
        assertEquals(setOf(ActuationKey.CHARGE_LIMIT), host.pendingStates.last())

        host.readInFlight = false
        channel.onReadFinished()

        assertEquals(listOf(ActuationKey.CHARGE_LIMIT), host.sent.map { it.first })
    }

    @Test
    fun `a newer value replaces the one still queued`() {
        channel.submit(ActuationKey.CHARGE_LIMIT, TeslaChargeCommands.buildChargeLimitRequest(80))
        channel.submit(ActuationKey.CHARGE_LIMIT, TeslaChargeCommands.buildChargeLimitRequest(85))
        host.awake = true
        host.session = true
        channel.onAwake()

        val action = Action.ADAPTER.decode(host.sent.single().second)

        assertEquals(85, action.vehicleAction?.chargingSetLimitAction?.percent)
        assertEquals(1, host.wakeCalls)
    }

    @Test
    fun `stop replaces start while it is still queued`() {
        channel.submit(ActuationKey.CHARGE_START, TeslaChargeCommands.buildChargeStartRequest())
        channel.submit(ActuationKey.CHARGE_STOP, TeslaChargeCommands.buildChargeStopRequest())
        host.awake = true
        host.session = true
        channel.onAwake()

        val action = Action.ADAPTER.decode(host.sent.single().second)

        assertNull(action.vehicleAction?.chargingStartStopAction?.start)
        assertNotNull(action.vehicleAction?.chargingStartStopAction?.stop)
    }

    @Test
    fun `a refusal reports the action and resumes the waiting read`() {
        sendLimit()

        channel.onReply(ActuationKey.CHARGE_LIMIT, actionStatus(ok = false))

        assertEquals(ActuationFeedback(ActuationKey.CHARGE_LIMIT, accepted = false), host.feedback.single())
        assertEquals(false, host.lastResumeAccepted)
    }

    @Test
    fun `an unanswered action drops the queued values`() {
        sendLimit()
        channel.submit(ActuationKey.CHARGING_AMPS, TeslaChargeCommands.buildChargingAmpsRequest(20))

        channel.onExpired(ActuationKey.CHARGE_LIMIT)

        assertEquals(
            listOf(
                ActuationFeedback(ActuationKey.CHARGE_LIMIT, accepted = false),
                ActuationFeedback(ActuationKey.CHARGING_AMPS, accepted = false),
            ),
            host.feedback,
        )
        assertTrue(host.pendingStates.last().isEmpty())
        assertEquals(1, host.forgetCalls)
    }

    @Test
    fun `an interrupted awake car fails the action`() {
        sendLimit()

        channel.onInterrupted()

        assertEquals(ActuationFeedback(ActuationKey.CHARGE_LIMIT, accepted = false), host.feedback.single())
    }

    @Test
    fun `an interruption during a requested wake leaves the action waiting`() {
        channel.submit(ActuationKey.CHARGE_LIMIT, TeslaChargeCommands.buildChargeLimitRequest(80))

        channel.onInterrupted()
        assertTrue(host.feedback.isEmpty())
        assertEquals(setOf(ActuationKey.CHARGE_LIMIT), host.pendingStates.last())

        channel.onStopped()
        assertEquals(ActuationFeedback(ActuationKey.CHARGE_LIMIT, accepted = false), host.feedback.single())
        assertEquals(1, host.forgetCalls)
    }

    @Test
    fun `a send failure reports the action and clears the queue`() {
        host.awake = true
        host.session = true
        host.sendResult = false

        channel.submit(ActuationKey.CHARGE_LIMIT, TeslaChargeCommands.buildChargeLimitRequest(80))

        assertFalse(host.feedback.single().accepted)
        assertTrue(host.pendingStates.last().isEmpty())
    }

    private fun sendLimit() {
        host.awake = true
        host.session = true
        channel.submit(ActuationKey.CHARGE_LIMIT, TeslaChargeCommands.buildChargeLimitRequest(80))
    }

    private fun actionStatus(ok: Boolean): ByteArray =
        Response(
            actionStatus =
                ActionStatus(
                    result = if (ok) OperationStatus_E.OPERATIONSTATUS_OK else OperationStatus_E.OPERATIONSTATUS_ERROR,
                ),
        ).encode()

    private class FakeHost : ActuationHost {
        var awake = false
        var session = false
        var readInFlight = false
        var readWaiting = false
        var sendResult = true
        var startSessionResult = true
        var wakeCalls = 0
        var sessionStarts = 0
        var forgetCalls = 0
        var resumedReads = 0
        var lastResumeAccepted: Boolean? = null
        val sent = mutableListOf<Pair<ActuationKey, ByteArray>>()
        val feedback = mutableListOf<ActuationFeedback>()
        val pendingStates = mutableListOf<Set<ActuationKey>>()

        override fun isAwake(): Boolean = awake

        override fun hasSession(): Boolean = session

        override fun readWaiting(): Boolean = readWaiting

        override fun readActive(): Boolean = readInFlight

        override fun startSession(): Boolean {
            sessionStarts++
            return startSessionResult
        }

        override fun send(
            payload: ByteArray,
            key: ActuationKey,
        ): Boolean {
            sent += key to payload
            return sendResult
        }

        override fun wake() {
            wakeCalls++
        }

        override fun forgetWake() {
            forgetCalls++
        }

        override fun publish(
            pending: Set<ActuationKey>,
            feedback: ActuationFeedback?,
        ) {
            pendingStates += pending
            feedback?.let { this.feedback += it }
        }

        override fun resumeRead(accepted: Boolean) {
            resumedReads++
            lastResumeAccepted = accepted
        }
    }
}
