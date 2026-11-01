// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256

@OptIn(DelicateCryptographyApi::class)
object TeslaCrypto {
    const val SHARED_KEY_SIZE = 16
    const val NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16

    private val provider = CryptographyProvider.Default
    private val ecdh = provider.get(ECDH)
    private val sha1 = provider.get(SHA1)

    /**
     * The Tesla session key from a raw ECDH secret: truncated SHA-1, mandated
     * by the protocol. Internal so [VehicleKey.sessionKeyWith] is the only way
     * to derive one, and no platform implementation can pick a different rule.
     */
    internal fun sessionKey(sharedSecret: ByteArray): ByteArray = sha1.hasher().hashBlocking(sharedSecret).copyOf(SHARED_KEY_SIZE)

    /** Rejects anything but the uncompressed P-256 point the car sends. */
    internal fun requireUncompressedPoint(raw: ByteArray) {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "expected uncompressed point" }
    }

    internal fun rawToPublicKey(raw: ByteArray): ECDH.PublicKey {
        requireUncompressedPoint(raw)
        return ecdh
            .publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, raw)
    }

    fun subkey(
        sessionKey: ByteArray,
        label: String,
    ): ByteArray {
        val key =
            provider
                .get(HMAC)
                .keyDecoder(SHA256)
                .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, sessionKey)
        return key.signatureGenerator().generateSignatureBlocking(label.toByteArray(Charsets.US_ASCII))
    }

    fun encryptGcm(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        // Callers must use a fresh nonce per message: the JDK provider refuses
        // a repeated nonce under one key (as Android's GCM does).
        val combined = aesCipher(key).encryptWithIvBlocking(nonce, plaintext, associatedData)
        val ciphertext = combined.copyOf(combined.size - GCM_TAG_SIZE)
        val tag = combined.copyOfRange(combined.size - GCM_TAG_SIZE, combined.size)
        return ciphertext to tag
    }

    fun decryptGcm(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        associatedData: ByteArray,
    ): ByteArray? =
        runCatching {
            aesCipher(key).decryptWithIvBlocking(nonce, ciphertext + tag, associatedData)
        }.getOrNull()

    fun randomBytes(size: Int): ByteArray = CryptographySystem.getDefaultRandom().nextBytes(size)

    private fun aesCipher(key: ByteArray) =
        provider
            .get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher()
}
