// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.RoutableMessage
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.tesla.generated.universalmessage.OperationStatus_E as MessageOperationStatus

/**
 * A fake car that rejects requests with each kind of fault, and the path each one takes through
 * the link's recovery: resync the session in place, pair again, or leave the session alone.
 */
class FakeCarFaultsTest {
    private companion object {
        const val BASE_COUNTER = 40
    }

    private val vin = "5YJ3E1EA7KF000001"
    private val phone = TeslaKeys.generate()
    private val car = FakeCar()

    private val handshake = SessionHandshake(PhoneKeys(), Host(), clientAddress = ByteArray(16) { 2 })

    @Test
    fun `every stale session fault is recovered by resyncing from the car's session info`() {
        val staleFaults =
            listOf(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
                MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
                MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
            )
        for (fault in staleFaults) {
            val reply = car.reject(fault, sessionInfo = true)
            assertEquals(FaultRecovery.RESYNC_SESSION, reply.status().recovery, fault.name)
            assertTrue(resync(reply), fault.name)
        }
    }

    @Test
    fun `resyncing twice never moves the session counter back`() {
        val high = car.reject(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, sessionInfo = true, counter = 90)
        val low = car.reject(MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH, sessionInfo = true, counter = 45)

        assertTrue(resync(high))
        assertTrue(resync(low))

        // The counter the next request carries is one past the highest the car reported.
        assertEquals(91, nextCounter())
    }

    @Test
    fun `a stale fault with no session info cannot be resynced`() {
        val reply = car.reject(MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH, sessionInfo = false)

        assertEquals(FaultRecovery.RESYNC_SESSION, reply.status().recovery)
        assertFalse(resync(reply))
    }

    @Test
    fun `a stale fault whose session info does not verify cannot be resynced`() {
        val reply = car.reject(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, sessionInfo = true, tagVin = "5YJ3E1EA7KF000002")

        assertEquals(FaultRecovery.RESYNC_SESSION, reply.status().recovery)
        assertFalse(resync(reply))
    }

    @Test
    fun `a car that does not know the key means pairing again, not resyncing`() {
        val reply = car.reject(MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID, sessionInfo = false)

        assertEquals(FaultRecovery.PAIR_AGAIN, reply.status().recovery)
    }

    @Test
    fun `faults that leave the session intact are left to the next poll`() {
        val leaveAlone =
            listOf(
                MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
                MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT,
                MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL,
                MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES,
                MessageFault_E.MESSAGEFAULT_ERROR_INACTIVE_KEY,
                MessageFault_E.MESSAGEFAULT_ERROR_REMOTE_ACCESS_DISABLED,
            )
        for (fault in leaveAlone) {
            val status = car.reject(fault, sessionInfo = false).status()
            assertTrue(status.isRefusal, fault.name)
            assertEquals(FaultRecovery.NONE, status.recovery, fault.name)
        }
    }

    @Test
    fun `a reply without a fault needs no recovery`() {
        val status = MessageStatus(MessageOperationStatus.OPERATIONSTATUS_OK)

        assertFalse(status.isRefusal)
        assertEquals(FaultRecovery.NONE, status.recovery)
    }

    @Test
    fun `a rejected reply carries its status through the wire encoding`() {
        val decoded = RoutableMessage.ADAPTER.decode(car.reject(MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID, sessionInfo = false))

        assertNotNull(decoded.signedMessageStatus)
        assertNull(decoded.signature_data)
    }

    private fun ByteArray.status(): MessageStatus = RoutableMessage.ADAPTER.decode(this).signedMessageStatus!!

    /** Applies the session info of [reply] to a session the car answered earlier, as the controller does. */
    private fun resync(reply: ByteArray): Boolean {
        if (!handshake.has(Domain.DOMAIN_VEHICLE_SECURITY)) establish()
        val message = RoutableMessage.ADAPTER.decode(reply)
        val info = message.session_info?.toByteArray()
        val tag =
            message.signature_data
                ?.session_info_tag
                ?.tag
                ?.toByteArray()
        return info != null &&
            tag != null &&
            handshake.resync(Domain.DOMAIN_VEHICLE_SECURITY, message.request_uuid.toByteArray(), info, tag)
    }

    private fun nextCounter(): Int {
        val request =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                protobuf_message_as_bytes = "x".encodeToByteArray().toByteString(),
            )
        return handshake[Domain.DOMAIN_VEHICLE_SECURITY]!!
            .encrypt(request, 5)!!
            .signature_data!!
            .AES_GCM_Personalized_data!!
            .counter
    }

    private fun establish() {
        handshake.start()
        handshake.onMessage(car.sessionAnswer(requests.single()))
    }

    private val requests = mutableListOf<ByteArray>()

    private inner class PhoneKeys : SessionKeys {
        override fun hasKey() = true

        override fun publicKeyRaw() = phone.publicKeyRaw

        override fun load() = phone
    }

    private inner class Host : SessionHost {
        override val isReady = true
        override val isAwake = true
        override val vin: String = this@FakeCarFaultsTest.vin

        override fun log(message: String) = Unit

        override fun transmitSessionRequest(request: ByteArray): Boolean {
            requests += request
            return true
        }

        override fun publishSessions(domains: List<String>) = Unit

        override fun mayRequestInfotainment(awake: Boolean) = false

        override fun onEstablished(domain: Domain) = Unit

        override fun onGaveUp() = Unit

        override fun onKeyUnusable() = Unit

        override fun scheduleRetry(delayMs: Long) = Unit

        override fun cancelRetry() = Unit
    }

    /** A car with one key pair that answers session requests and rejects commands. */
    private inner class FakeCar {
        private val keys = TeslaKeys.generate()
        private val sessionKey = phone.sessionKeyWith(keys.publicKeyRaw)

        private fun info(counter: Int = BASE_COUNTER): ByteArray =
            SessionInfo(
                counter = counter,
                publicKey = keys.publicKeyRaw.toByteString(),
                epoch = ByteArray(16) { it.toByte() }.toByteString(),
                clock_time = 500,
            ).encode()

        fun sessionAnswer(request: ByteArray): ByteArray {
            val uuid = RoutableMessage.ADAPTER.decode(request).uuid
            val encoded = info()
            val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, uuid.toByteArray(), encoded)
            return RoutableMessage(
                request_uuid = uuid,
                session_info = encoded.toByteString(),
                signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = tag.toByteString())),
            ).encode()
        }

        /**
         * A rejection with [fault], with or without fresh session info. The rejected command's uuid and the
         * counter in the info differ per fault, unless [counter] is given.
         */
        fun reject(
            fault: MessageFault_E,
            sessionInfo: Boolean,
            tagVin: String = vin,
            counter: Int = BASE_COUNTER + fault.value,
        ): ByteArray {
            val challenge = ByteArray(16) { (fault.value + 1).toByte() }
            val status = MessageStatus(MessageOperationStatus.OPERATIONSTATUS_ERROR, fault)
            if (!sessionInfo) {
                return RoutableMessage(request_uuid = challenge.toByteString(), signedMessageStatus = status).encode()
            }
            val encoded = info(counter)
            val tag = TeslaSession.sessionInfoHmac(sessionKey, tagVin, challenge, encoded)
            return RoutableMessage(
                request_uuid = challenge.toByteString(),
                signedMessageStatus = status,
                session_info = encoded.toByteString(),
                signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = tag.toByteString())),
            ).encode()
        }
    }
}
