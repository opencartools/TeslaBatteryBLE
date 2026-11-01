// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHandshakeTest {
    private val vin = "5YJ3E1EA7KF000001"
    private val client = TeslaKeys.generate()
    private val vehicle = TeslaKeys.generate()
    private val keys = FakeKeys()
    private val host = FakeHost()
    private val handshake = SessionHandshake(keys, host, clientAddress = ByteArray(16) { 2 })

    @Test
    fun `starting asks VCSEC for a session and arms the retry timer`() {
        assertTrue(handshake.start())

        assertEquals(listOf(Domain.DOMAIN_VEHICLE_SECURITY), host.requestedDomains())
        assertEquals(SessionHandshake.RETRY_MS, host.retryDelay)
    }

    @Test
    fun `infotainment is requested only while the host allows it`() {
        host.infotainmentWanted = true

        handshake.start()

        assertEquals(SessionHandshake.DOMAINS, host.requestedDomains())
    }

    @Test
    fun `nothing starts without a key or without a complete VIN`() {
        keys.stored = null
        assertFalse(handshake.start())

        keys.stored = client
        host.vin = null
        assertFalse(handshake.start())

        assertTrue(host.requests.isEmpty())
        assertNull(host.retryDelay)
    }

    @Test
    fun `an answer from the car establishes the session`() {
        handshake.start()

        assertTrue(handshake.onMessage(answer(host.requests.single(), vin)))

        assertTrue(handshake.has(Domain.DOMAIN_VEHICLE_SECURITY))
        assertEquals(listOf(listOf("DOMAIN_VEHICLE_SECURITY")), host.published)
        assertEquals(listOf(Domain.DOMAIN_VEHICLE_SECURITY), host.established)
    }

    @Test
    fun `the retry timer stops once every domain has a session`() {
        host.infotainmentWanted = true
        handshake.start()

        host.requests.toList().forEach { handshake.onMessage(answer(it, vin)) }

        assertTrue(host.retryCancelled)
        assertNull(host.retryDelay)
        assertFalse(handshake.start())
    }

    @Test
    fun `an answer with a wrong tag is consumed but gives no session`() {
        handshake.start()

        assertTrue(handshake.onMessage(answer(host.requests.single(), "5YJ3E1EA7KF000002")))

        assertFalse(handshake.has(Domain.DOMAIN_VEHICLE_SECURITY))
        assertTrue(host.logs.any { it.contains("verification failed") })
        assertFalse(host.keyUnusable)
    }

    @Test
    fun `a key the store dropped is reported as unusable`() {
        handshake.start()
        val reply = answer(host.requests.single(), vin)
        keys.stored = null

        assertTrue(handshake.onMessage(reply))

        assertTrue(host.keyUnusable)
    }

    @Test
    fun `an answer without a tag is consumed and logged`() {
        handshake.start()
        val uuid = RoutableMessage.ADAPTER.decode(host.requests.single()).uuid
        val reply =
            RoutableMessage(request_uuid = uuid, session_info = sessionInfo().toByteString()).encode()

        assertTrue(handshake.onMessage(reply))

        assertTrue(host.logs.any { it.contains("missing tag") })
    }

    @Test
    fun `messages that answer no request are left to the caller`() {
        handshake.start()

        assertFalse(handshake.onMessage(byteArrayOf(1, 2, 3)))
        assertFalse(handshake.onMessage(RoutableMessage(session_info = sessionInfo().toByteString()).encode()))
        val stray = RoutableMessage(request_uuid = ByteArray(16).toByteString(), session_info = sessionInfo().toByteString())
        assertFalse(handshake.onMessage(stray.encode()))
    }

    @Test
    fun `the retry timer asks again until the attempt limit, then gives up`() {
        handshake.start()
        host.requests.clear()

        repeat(SessionHandshake.MAX_ATTEMPTS) { handshake.onRetryTimer() }
        assertEquals(SessionHandshake.MAX_ATTEMPTS, host.requests.size)
        assertEquals(0, host.gaveUp)
        handshake.onRetryTimer()

        assertEquals(1, host.gaveUp)
        assertEquals(SessionHandshake.MAX_ATTEMPTS, host.requests.size)
    }

    @Test
    fun `giving up forgets pending infotainment requests only`() {
        host.infotainmentWanted = true
        handshake.start()
        val infotainment = host.requests.first { it.domain() == Domain.DOMAIN_INFOTAINMENT }
        val vcsec = host.requests.first { it.domain() == Domain.DOMAIN_VEHICLE_SECURITY }
        repeat(SessionHandshake.MAX_ATTEMPTS + 1) { handshake.onRetryTimer() }

        assertFalse(handshake.onMessage(answer(infotainment, vin)))
        assertTrue(handshake.onMessage(answer(vcsec, vin)))
    }

    @Test
    fun `the retry timer does nothing while the link is not ready`() {
        handshake.start()
        host.requests.clear()
        host.isReady = false

        handshake.onRetryTimer()

        assertTrue(host.requests.isEmpty())
    }

    @Test
    fun `dropping a session asks for a new one on the next start`() {
        handshake.start()
        handshake.onMessage(answer(host.requests.single(), vin))
        host.requests.clear()

        handshake.drop(Domain.DOMAIN_VEHICLE_SECURITY)

        assertTrue(handshake.start())
        assertEquals(1, host.requests.size)
    }

    @Test
    fun `clearing forgets sessions and unanswered requests`() {
        handshake.start()
        val request = host.requests.single()

        handshake.clear()

        assertFalse(handshake.onMessage(answer(request, vin)))
        assertEquals(emptyList(), handshake.established)
    }

    @Test
    fun `session info on a rejected command replaces the session in place`() {
        handshake.start()
        handshake.onMessage(answer(host.requests.single(), vin))
        val challenge = ByteArray(16) { 9 }
        val info = sessionInfo()
        val key = client.sessionKeyWith(vehicle.publicKeyRaw)
        val tag = TeslaSession.sessionInfoHmac(key, vin, challenge, info)

        assertTrue(handshake.resync(Domain.DOMAIN_VEHICLE_SECURITY, challenge, info, tag))

        assertTrue(handshake.has(Domain.DOMAIN_VEHICLE_SECURITY))
        assertTrue(host.logs.any { it.contains("resynced") })
    }

    @Test
    fun `session info that does not verify leaves the session alone`() {
        handshake.start()
        handshake.onMessage(answer(host.requests.single(), vin))
        val info = sessionInfo()
        val key = client.sessionKeyWith(vehicle.publicKeyRaw)
        val forAnotherChallenge = TeslaSession.sessionInfoHmac(key, vin, ByteArray(16) { 1 }, info)

        assertFalse(handshake.resync(Domain.DOMAIN_VEHICLE_SECURITY, ByteArray(16) { 9 }, info, forAnotherChallenge))

        assertTrue(handshake.has(Domain.DOMAIN_VEHICLE_SECURITY))
        assertTrue(host.logs.none { it.contains("resynced") })
    }

    @Test
    fun `session info for a domain with no session is refused`() {
        val challenge = ByteArray(16) { 9 }
        val info = sessionInfo()
        val key = client.sessionKeyWith(vehicle.publicKeyRaw)
        val tag = TeslaSession.sessionInfoHmac(key, vin, challenge, info)

        assertFalse(handshake.resync(Domain.DOMAIN_VEHICLE_SECURITY, challenge, info, tag))
    }

    private fun sessionInfo(): ByteArray =
        SessionInfo(
            counter = 7,
            publicKey = vehicle.publicKeyRaw.toByteString(),
            epoch = ByteArray(16) { it.toByte() }.toByteString(),
            clock_time = 100,
        ).encode()

    /** The car's answer to [request], tagged for [forVin]. */
    private fun answer(
        request: ByteArray,
        forVin: String,
    ): ByteArray {
        val uuid = RoutableMessage.ADAPTER.decode(request).uuid
        val info = sessionInfo()
        val key = client.sessionKeyWith(vehicle.publicKeyRaw)
        val tag = TeslaSession.sessionInfoHmac(key, forVin, uuid.toByteArray(), info)
        return RoutableMessage(
            request_uuid = uuid,
            session_info = info.toByteString(),
            signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = tag.toByteString())),
        ).encode()
    }

    private fun ByteArray.domain(): Domain? =
        RoutableMessage.ADAPTER
            .decode(this)
            .to_destination
            ?.domain

    private inner class FakeKeys : SessionKeys {
        var stored: TeslaKeyPair? = client

        override fun hasKey() = stored != null

        override fun publicKeyRaw() = stored?.publicKeyRaw

        override fun load() = stored
    }

    private inner class FakeHost : SessionHost {
        override var isReady = true
        override var isAwake = true
        override var vin: String? = this@SessionHandshakeTest.vin
        var infotainmentWanted = false
        var retryDelay: Long? = null
        var retryCancelled = false
        var keyUnusable = false
        var gaveUp = 0
        val logs = mutableListOf<String>()
        val requests = mutableListOf<ByteArray>()
        val published = mutableListOf<List<String>>()
        val established = mutableListOf<Domain>()

        fun requestedDomains(): List<Domain> = requests.mapNotNull { it.domain() }

        override fun log(message: String) {
            logs += message
        }

        override fun transmitSessionRequest(request: ByteArray): Boolean {
            requests += request
            return true
        }

        override fun publishSessions(domains: List<String>) {
            published += domains
        }

        override fun mayRequestInfotainment(awake: Boolean) = infotainmentWanted && awake

        override fun onEstablished(domain: Domain) {
            established += domain
        }

        override fun onGaveUp() {
            gaveUp++
        }

        override fun onKeyUnusable() {
            keyUnusable = true
        }

        override fun scheduleRetry(delayMs: Long) {
            retryDelay = delayMs
        }

        override fun cancelRetry() {
            retryCancelled = true
            retryDelay = null
        }
    }
}
