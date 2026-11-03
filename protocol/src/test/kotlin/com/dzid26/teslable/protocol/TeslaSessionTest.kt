// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeslaSessionTest {
    private val vin = "5YJ3E1EA7KF000001"
    private val client = TeslaKeys.generate()
    private val vehicle = TeslaKeys.generate()
    private val challenge = ByteArray(16) { 1 }
    private val sessionKey = client.sessionKeyWith(vehicle.publicKeyRaw)

    private fun sessionInfo(
        counter: Int = 7,
        clockTime: Int = 100,
    ): ByteArray =
        SessionInfo(
            counter = counter,
            publicKey = vehicle.publicKeyRaw.toByteString(),
            epoch = ByteArray(16) { it.toByte() }.toByteString(),
            clock_time = clockTime,
        ).encode()

    private fun createSession(
        clock: () -> Long = { 0 },
        counter: Int = 7,
        clockTime: Int = 100,
    ): TeslaSession? {
        val info = sessionInfo(counter, clockTime)
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        return TeslaSession.import(
            key = client,
            vin = vin,
            challenge = challenge,
            encodedInfo = info,
            tag = tag,
            clock = clock,
        )
    }

    @Test
    fun `imports a session with a valid tag`() {
        assertNotNull(createSession())
    }

    @Test
    fun `rejects a session with a tampered tag`() {
        val info = sessionInfo()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        tag[0] = (tag[0] + 1).toByte()
        assertEquals(
            null,
            TeslaSession.import(client, vin, challenge, info, tag),
        )
    }

    @Test
    fun `rejects a session with the wrong vin`() {
        val info = sessionInfo()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        assertEquals(
            null,
            TeslaSession.import(
                client,
                "WRONGVIN000000000",
                challenge,
                info,
                tag,
            ),
        )
    }

    @Test
    fun `encrypts with an incrementing counter and decrypts the response`() {
        val session = createSession()!!
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
                from_destination = Destination(routing_address = ByteArray(16).toByteString()),
                protobuf_message_as_bytes = "hello".encodeToByteArray().toByteString(),
            )

        val encrypted = session.encrypt(message, 5)
        assertNotNull(encrypted)
        val gcm = encrypted.signature_data?.AES_GCM_Personalized_data
        assertNotNull(gcm)
        assertEquals(8, gcm.counter)
        assertFalse(
            encrypted.protobuf_message_as_bytes!!
                .toByteArray()
                .contentEquals("hello".encodeToByteArray()),
        )

        val requestId = session.requestId(encrypted)!!
        val response = vehicleResponse(requestId, "world".encodeToByteArray(), counter = 3)
        val window = AntiReplayWindow()
        val plaintext = session.decrypt(response, requestId, window)
        assertNotNull(plaintext)
        assertContentEquals("world".encodeToByteArray(), plaintext)

        assertEquals(null, session.decrypt(response, requestId, window))
    }

    @Test
    fun `session info from a rejected command moves the counter forward`() {
        val session = createSession()!!

        assertTrue(resync(session, counter = 20))

        assertEquals(21, nextCounter(session))
    }

    @Test
    fun `session info with a lower counter never moves the counter back`() {
        val session = createSession()!!
        nextCounter(session)

        assertTrue(resync(session, counter = 3))

        assertEquals(9, nextCounter(session))
    }

    @Test
    fun `session info for another car key is refused`() {
        val session = createSession()!!
        val other = TeslaKeys.generate()
        val info =
            SessionInfo(
                counter = 50,
                publicKey = other.publicKeyRaw.toByteString(),
                epoch = ByteArray(16) { it.toByte() }.toByteString(),
                clock_time = 100,
            ).encode()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)

        assertFalse(session.updateSignedSessionInfo(challenge, info, tag))

        assertEquals(8, nextCounter(session))
    }

    @Test
    fun `session info with an older clock in the same epoch is ignored`() {
        val session = createSession()!!

        assertTrue(resync(session, counter = 99, clockTime = 50))

        assertEquals(8, nextCounter(session))
    }

    @Test
    fun `session info for a new epoch is adopted even with an older clock`() {
        val session = createSession()!!

        assertTrue(resync(session, counter = 50, clockTime = 10, epochByte = 9))

        val encrypted = session.encrypt(infotainmentMessage(), 5)!!
        val gcm = encrypted.signature_data!!.AES_GCM_Personalized_data!!
        assertEquals(51, gcm.counter)
        assertContentEquals(ByteArray(16) { 9 }, gcm.epoch.toByteArray())
    }

    @Test
    fun `session info with a wrong tag leaves the session alone`() {
        val session = createSession()!!
        val info = sessionInfo(counter = 50)
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, ByteArray(16) { 5 }, info)

        assertFalse(session.updateSignedSessionInfo(challenge, info, tag))

        assertEquals(8, nextCounter(session))
    }

    private fun infotainmentMessage() =
        RoutableMessage(
            to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
            from_destination = Destination(routing_address = ByteArray(16).toByteString()),
            protobuf_message_as_bytes = "hello".encodeToByteArray().toByteString(),
        )

    /** The counter the session's next request carries. */
    private fun nextCounter(session: TeslaSession): Int =
        session
            .encrypt(infotainmentMessage(), 5)!!
            .signature_data!!
            .AES_GCM_Personalized_data!!
            .counter

    private fun resync(
        session: TeslaSession,
        counter: Int,
        clockTime: Int = 100,
        epochByte: Int = 0,
    ): Boolean {
        val epoch = if (epochByte == 0) ByteArray(16) { it.toByte() } else ByteArray(16) { epochByte.toByte() }
        val info =
            SessionInfo(
                counter = counter,
                publicKey = vehicle.publicKeyRaw.toByteString(),
                epoch = epoch.toByteString(),
                clock_time = clockTime,
            ).encode()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        return session.updateSignedSessionInfo(challenge, info, tag)
    }

    @Test
    fun `decrypts responses for separate requests that reuse a counter`() {
        val session = createSession()!!
        val firstId = ByteArray(33) { 1 }
        val secondId = ByteArray(33) { 2 }

        val first =
            session.decrypt(
                vehicleResponse(firstId, "first".encodeToByteArray(), counter = 0, nonceSeed = 1),
                firstId,
                AntiReplayWindow(),
            )
        val second =
            session.decrypt(
                vehicleResponse(secondId, "second".encodeToByteArray(), counter = 0, nonceSeed = 2),
                secondId,
                AntiReplayWindow(),
            )

        assertContentEquals("first".encodeToByteArray(), first)
        assertContentEquals("second".encodeToByteArray(), second)
    }

    @Test
    fun `encrypt uses the injected nonce`() {
        val fixedNonce = ByteArray(12) { 7 }
        val info = sessionInfo()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        val session =
            TeslaSession.import(
                key = client,
                vin = vin,
                challenge = challenge,
                encodedInfo = info,
                tag = tag,
                clock = { 0 },
                nonceGenerator = { fixedNonce },
            )!!
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
                from_destination = Destination(routing_address = ByteArray(16).toByteString()),
                protobuf_message_as_bytes = "hello".encodeToByteArray().toByteString(),
            )
        val encrypted = session.encrypt(message, 5)!!
        assertContentEquals(
            fixedNonce,
            encrypted.signature_data
                ?.AES_GCM_Personalized_data
                ?.nonce
                ?.toByteArray(),
        )
    }

    private fun vehicleResponse(
        requestId: ByteArray,
        plaintext: ByteArray,
        counter: Int,
        nonceSeed: Byte = 2,
    ): RoutableMessage {
        val metadata =
            Metadata
                .sha256()
                .add(
                    Tag.TAG_SIGNATURE_TYPE.value,
                    byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE.value.toByte()),
                ).add(Tag.TAG_DOMAIN.value, byteArrayOf(Domain.DOMAIN_INFOTAINMENT.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION.value, vin.toByteArray())
                .addUint32(Tag.TAG_COUNTER.value, counter)
                .addUint32(Tag.TAG_FLAGS.value, 0)
                .add(Tag.TAG_REQUEST_HASH.value, requestId)
                .addUint32(Tag.TAG_FAULT.value, 0)
        // GCM forbids reusing a nonce under one key (the provider pools its
        // cipher, and SunJCE refuses the second init), so each response gets
        // its own nonce. Production nonces are random per message.
        val nonce = ByteArray(12) { nonceSeed }
        val (ciphertext, tag) =
            TeslaCrypto.encryptGcm(
                sessionKey,
                nonce,
                plaintext,
                metadata.checksum(byteArrayOf()),
            )
        return RoutableMessage(
            from_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
            protobuf_message_as_bytes = ciphertext.toByteString(),
            signature_data =
                SignatureData(
                    AES_GCM_Response_data =
                        AES_GCM_Response_Signature_Data(
                            nonce = nonce.toByteString(),
                            counter = counter,
                            tag = tag.toByteString(),
                        ),
                ),
        )
    }
}
