// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage

/** The pairing key of one car, as the session handshake needs it. */
interface SessionKeys {
    fun hasKey(): Boolean

    fun publicKeyRaw(): ByteArray?

    /** Null when there is no key, or one this phone cannot use. */
    fun load(): VehicleKey?
}

/**
 * What a [SessionHandshake] needs from the link that owns it. Everything platform-bound stays
 * behind this seam (ADR-0007).
 */
interface SessionHost {
    /** The link is ready to exchange messages. */
    val isReady: Boolean

    /** The latest VCSEC verdict: the car is awake. */
    val isAwake: Boolean

    /** The car's VIN; null until the user has entered a complete one. */
    val vin: String?

    fun log(message: String)

    /** Sends a session-info request; false when the transport did not take it. */
    fun transmitSessionRequest(request: ByteArray): Boolean

    /** The domains that now have a session, sorted by name. */
    fun publishSessions(domains: List<String>)

    /** Whether the Infotainment session may be requested now (a read is waiting and the car is awake). */
    fun mayRequestInfotainment(awake: Boolean): Boolean

    /** A session for [domain] was established and is already usable. */
    fun onEstablished(domain: Domain)

    /** The handshake was abandoned after [SessionHandshake.MAX_ATTEMPTS] retries. */
    fun onGaveUp()

    /** The stored key can never be used on this phone. */
    fun onKeyUnusable()

    fun scheduleRetry(delayMs: Long)

    fun cancelRetry()
}

/**
 * The session handshake of one car link: asks each domain for a session, retries until every
 * domain has one, and imports the car's answer. Sessions and unanswered requests live here;
 * encryption stays in [TeslaSession].
 *
 * One instance per vehicle link, not thread-safe.
 */
class SessionHandshake(
    private val keys: SessionKeys,
    private val host: SessionHost,
    private val clientAddress: ByteArray,
) {
    private val sessions = mutableMapOf<Domain, TeslaSession>()
    private val pending = mutableMapOf<String, Domain>()
    private var retryAttempts = 0

    fun has(domain: Domain): Boolean = sessions.containsKey(domain)

    operator fun get(domain: Domain): TeslaSession? = sessions[domain]

    /** Domains with a session, sorted by name. */
    val established: List<String>
        get() = sessions.keys.map { it.name }.sorted()

    /** Forgets the [domain] session without telling the host; the next [start] asks for a new one. */
    fun drop(domain: Domain) {
        sessions.remove(domain)
    }

    /** Forgets every session and unanswered request. */
    fun clear() {
        sessions.clear()
        pending.clear()
    }

    /** Forgets unanswered requests only. */
    fun clearPending() {
        pending.clear()
    }

    fun publish() = host.publishSessions(established)

    /** Forgets unanswered Infotainment requests, so none is mistaken for a handshake still in flight. */
    fun dropPendingInfotainment() {
        pending.values.removeAll { it == Domain.DOMAIN_INFOTAINMENT }
    }

    fun cancelTimer() = host.cancelRetry()

    /** True when session requests went out. */
    fun start(): Boolean {
        if (!keys.hasKey()) {
            host.log("no pairing key yet")
            return false
        }
        if (host.vin == null) {
            host.log("enter your VIN to establish a session")
            return false
        }
        val needed = neededDomains()
        if (needed.isEmpty()) return false
        retryAttempts = 0
        sendRequests(needed)
        host.cancelRetry()
        host.scheduleRetry(RETRY_MS)
        return true
    }

    /** The retry timer fired. */
    fun onRetryTimer() {
        if (!host.isReady) return
        val needed = neededDomains()
        if (needed.isEmpty()) return
        if (retryAttempts++ >= MAX_ATTEMPTS) {
            host.log("session not established (${needed.joinToString { it.name }})")
            host.onGaveUp()
            dropPendingInfotainment()
            return
        }
        sendRequests(needed)
        host.scheduleRetry(RETRY_MS)
    }

    /**
     * VCSEC is always needed. Infotainment only while an awake car has a read waiting for it
     * (a sleeping car cannot answer), never on connect.
     */
    private fun neededDomains(): List<Domain> =
        DOMAINS.filter { domain ->
            !sessions.containsKey(domain) &&
                (domain != Domain.DOMAIN_INFOTAINMENT || host.mayRequestInfotainment(host.isAwake))
        }

    private fun sendRequests(domains: List<Domain>) {
        val publicKeyRaw = keys.publicKeyRaw() ?: return
        for (domain in domains) {
            val uuid = TeslaCrypto.randomBytes(UUID_BYTES)
            val routing =
                if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
                    TeslaCrypto.randomBytes(UUID_BYTES)
                } else {
                    clientAddress
                }
            pending[uuid.toHexString()] = domain
            val request =
                TeslaSessionRequests.buildSessionInfoRequest(
                    domain = domain,
                    publicKeyRaw = publicKeyRaw,
                    routingAddress = routing,
                    uuid = uuid,
                )
            host.log("session request ${domain.name}")
            if (!host.transmitSessionRequest(request)) host.log("failed to send session request")
        }
    }

    /** True when [bytes] answered one of our session requests, whether or not it yielded a session. */
    fun onMessage(bytes: ByteArray): Boolean {
        val message = runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull() ?: return false
        val encodedInfo = message.session_info?.toByteArray() ?: return false
        val challenge = message.request_uuid.toByteArray()
        val domain = pending.remove(challenge.toHexString()) ?: return false
        val tag =
            message.signature_data
                ?.session_info_tag
                ?.tag
                ?.toByteArray()
        if (tag == null) {
            host.log("session info missing tag")
            return true
        }
        val key = keys.load()
        val vin = host.vin
        val session =
            if (key != null && vin != null) {
                TeslaSession.import(
                    key = key,
                    vin = vin,
                    challenge = challenge,
                    encodedInfo = encodedInfo,
                    tag = tag,
                )
            } else {
                null
            }
        if (session == null) {
            // The store dropped a key this phone can never decrypt (for example a
            // device-only key restored from another phone).
            if (key == null && !keys.hasKey()) host.onKeyUnusable()
            host.log("session verification failed (${domain.name})")
            return true
        }
        sessions[domain] = session
        publish()
        host.log("session established (${domain.name})")
        if (DOMAINS.all { sessions.containsKey(it) }) host.cancelRetry()
        host.onEstablished(domain)
        return true
    }

    /**
     * Applies the session info a car attached to a rejected command (counter, epoch or clock out
     * of step) to the existing [domain] session, as the Go dispatcher's `checkForSessionUpdate`
     * does. False when there is no session or the info did not verify; the caller then drops the
     * session and handshakes again.
     */
    fun resync(
        domain: Domain,
        challenge: ByteArray,
        encodedInfo: ByteArray,
        tag: ByteArray,
    ): Boolean {
        val resynced = sessions[domain]?.updateSignedSessionInfo(challenge, encodedInfo, tag) == true
        if (resynced) host.log("session resynced (${domain.name})")
        return resynced
    }

    companion object {
        const val RETRY_MS = 3000L
        const val MAX_ATTEMPTS = 20
        private const val UUID_BYTES = 16

        val DOMAINS = listOf(Domain.DOMAIN_VEHICLE_SECURITY, Domain.DOMAIN_INFOTAINMENT)
    }
}
