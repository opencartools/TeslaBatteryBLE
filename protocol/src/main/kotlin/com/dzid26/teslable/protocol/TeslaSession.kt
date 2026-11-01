// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.signatures.AES_GCM_Personalized_Signature_Data
import com.tesla.generated.signatures.KeyIdentity
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import com.tesla.generated.universalmessage.RoutableMessage
import okio.ByteString.Companion.toByteString

class TeslaSession private constructor(
    private val sessionKey: ByteArray,
    private val vin: String,
    private val localPublicRaw: ByteArray,
    private val remotePublicRaw: ByteArray,
    private var counter: Int,
    private var epoch: ByteArray,
    private var clockTime: Int,
    private var timeZeroMs: Long,
    private val clock: () -> Long,
    private val nonceGenerator: () -> ByteArray,
) {
    fun encrypt(
        message: RoutableMessage,
        expiresInSeconds: Int,
    ): RoutableMessage? {
        if (counter == COUNTER_MAX) return null
        counter++

        val domain = message.to_destination?.domain ?: return null
        val plaintext = message.protobuf_message_as_bytes?.toByteArray() ?: return null
        val expiresAt = ((clock() - timeZeroMs) / 1000 + expiresInSeconds).toInt()

        val metadata =
            Metadata
                .sha256()
                .add(
                    Tag.TAG_SIGNATURE_TYPE.value,
                    byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED.value.toByte()),
                ).add(Tag.TAG_DOMAIN.value, byteArrayOf(domain.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION.value, vin.encodeToByteArray())
                .add(Tag.TAG_EPOCH.value, epoch)
                .addUint32(Tag.TAG_EXPIRES_AT.value, expiresAt)
                .addUint32(Tag.TAG_COUNTER.value, counter)
        if (message.flags > 0) {
            metadata.addUint32(Tag.TAG_FLAGS.value, message.flags)
        }

        val nonce = nonceGenerator()
        val (ciphertext, tag) =
            TeslaCrypto.encryptGcm(
                sessionKey,
                nonce,
                plaintext,
                metadata.checksum(byteArrayOf()),
            )

        return message.copy(
            protobuf_message_as_bytes = ciphertext.toByteString(),
            signature_data =
                SignatureData(
                    signer_identity = KeyIdentity(public_key = localPublicRaw.toByteString()),
                    AES_GCM_Personalized_data =
                        AES_GCM_Personalized_Signature_Data(
                            epoch = epoch.toByteString(),
                            nonce = nonce.toByteString(),
                            counter = counter,
                            expires_at = expiresAt,
                            tag = tag.toByteString(),
                        ),
                ),
        )
    }

    /**
     * Resyncs this session with session info the car attached to a rejected command, following the
     * Go signer's `UpdateSignedSessionInfo`: the info must be authenticated against the rejected
     * request's uuid ([challenge]) and name the same car key; the epoch and clock are adopted only
     * when the epoch changed or the clock did not go backwards, and the counter never moves
     * backwards. False when the info did not verify or is for another key.
     */
    fun updateSignedSessionInfo(
        challenge: ByteArray,
        encodedInfo: ByteArray,
        tag: ByteArray,
    ): Boolean {
        val authentic = sessionInfoHmac(sessionKey, vin, challenge, encodedInfo).contentEquals(tag)
        val info = if (authentic) runCatching { SessionInfo.ADAPTER.decode(encodedInfo) }.getOrNull() else null
        if (info == null || !info.publicKey.toByteArray().contentEquals(remotePublicRaw)) return false
        val newEpoch = info.epoch.toByteArray()
        // Counters and clocks are unsigned on the wire, as in the Go signer.
        if (!epoch.contentEquals(newEpoch) || clockTime.toUInt() <= info.clock_time.toUInt()) {
            if (counter.toUInt() < info.counter.toUInt()) counter = info.counter
            epoch = newEpoch
            clockTime = info.clock_time
            timeZeroMs = clock() - info.clock_time * MILLIS_PER_SECOND
        }
        return true
    }

    fun requestId(encrypted: RoutableMessage): ByteArray? {
        val tag =
            encrypted.signature_data
                ?.AES_GCM_Personalized_data
                ?.tag
                ?.toByteArray()
                ?: return null
        return byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED.value.toByte()) + tag
    }

    /**
     * Decrypts a response. The anti-replay [window] is per request: the Go
     * dispatcher keeps one per response handler, and the vehicle may reuse a
     * counter for different request ids.
     */
    fun decrypt(
        message: RoutableMessage,
        requestId: ByteArray,
        window: AntiReplayWindow,
    ): ByteArray? {
        val gcmData = message.signature_data?.AES_GCM_Response_data ?: return null
        val domain = message.from_destination?.domain ?: return null
        val fault = message.signedMessageStatus?.signed_message_fault?.value ?: 0
        val ciphertext = message.protobuf_message_as_bytes?.toByteArray() ?: return null

        val metadata =
            Metadata
                .sha256()
                .add(
                    Tag.TAG_SIGNATURE_TYPE.value,
                    byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE.value.toByte()),
                ).add(Tag.TAG_DOMAIN.value, byteArrayOf(domain.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION.value, vin.encodeToByteArray())
                .addUint32(Tag.TAG_COUNTER.value, gcmData.counter)
                .addUint32(Tag.TAG_FLAGS.value, message.flags)
                .add(Tag.TAG_REQUEST_HASH.value, requestId)
                .addUint32(Tag.TAG_FAULT.value, fault)

        val plaintext =
            TeslaCrypto.decryptGcm(
                sessionKey,
                gcmData.nonce.toByteArray(),
                ciphertext,
                gcmData.tag.toByteArray(),
                metadata.checksum(byteArrayOf()),
            ) ?: return null

        if (!window.update(gcmData.counter)) return null
        return plaintext
    }

    companion object {
        const val LABEL_SESSION_INFO = "session info"
        private const val MILLIS_PER_SECOND = 1000L
        private const val COUNTER_MAX = -1

        fun sessionInfoHmac(
            sessionKey: ByteArray,
            vin: String,
            challenge: ByteArray,
            encodedInfo: ByteArray,
        ): ByteArray =
            Metadata
                .hmacSha256(TeslaCrypto.subkey(sessionKey, LABEL_SESSION_INFO))
                .add(Tag.TAG_SIGNATURE_TYPE.value, byteArrayOf(SignatureType.SIGNATURE_TYPE_HMAC.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION.value, vin.encodeToByteArray())
                .add(Tag.TAG_CHALLENGE.value, challenge)
                .checksum(encodedInfo)

        fun import(
            key: VehicleKey,
            vin: String,
            challenge: ByteArray,
            encodedInfo: ByteArray,
            tag: ByteArray,
            clock: () -> Long = System::currentTimeMillis,
            nonceGenerator: () -> ByteArray = { TeslaCrypto.randomBytes(TeslaCrypto.NONCE_SIZE) },
        ): TeslaSession? {
            val info = runCatching { SessionInfo.ADAPTER.decode(encodedInfo) }.getOrNull() ?: return null
            val remotePublic = info.publicKey.toByteArray()
            val sessionKey = runCatching { key.sessionKeyWith(remotePublic) }.getOrNull() ?: return null

            val expected = sessionInfoHmac(sessionKey, vin, challenge, encodedInfo)
            if (!expected.contentEquals(tag)) return null

            return TeslaSession(
                sessionKey = sessionKey,
                vin = vin,
                localPublicRaw = key.publicKeyRaw,
                remotePublicRaw = remotePublic,
                counter = info.counter,
                epoch = info.epoch.toByteArray(),
                clockTime = info.clock_time,
                timeZeroMs = clock() - info.clock_time * MILLIS_PER_SECOND,
                clock = clock,
                nonceGenerator = nonceGenerator,
            )
        }
    }
}
