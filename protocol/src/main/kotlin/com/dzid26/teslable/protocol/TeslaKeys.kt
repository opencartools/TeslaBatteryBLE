// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.SHA1

/**
 * The software (exportable) [VehicleKey], and the record the app persists: the
 * private key as PKCS#8 and the public key as an uncompressed point. A key the
 * car no longer recognizes is never re-enrolled; pairing generates a fresh one
 * (ADR-0005).
 *
 * [privateKeyPkcs8] is public because the store writes it, but it is absent
 * from [VehicleKey], so code that only needs to open sessions cannot read it.
 */
@OptIn(DelicateCryptographyApi::class)
class TeslaKeyPair(
    val privateKeyPkcs8: ByteArray,
    override val publicKeyRaw: ByteArray,
) : VehicleKey() {
    override fun sharedSecretWith(peerPublicRaw: ByteArray): ByteArray =
        decodePrivateKeyPkcs8(privateKeyPkcs8)
            .sharedSecretGenerator()
            .generateSharedSecretToByteArrayBlocking(TeslaCrypto.rawToPublicKey(peerPublicRaw))
}

@OptIn(DelicateCryptographyApi::class)
object TeslaKeys {
    fun generate(): TeslaKeyPair {
        val keyPair = ecdh.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
        return TeslaKeyPair(
            privateKeyPkcs8 = keyPair.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.DER.Generic),
            // Uncompressed point; the car and the Go implementation agree on it.
            publicKeyRaw = keyPair.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW),
        )
    }

    /** The short SHA-1 identifier the car lists for this key; the one place that rule lives. */
    fun keyId(publicKeyRaw: ByteArray): ByteArray =
        // Truncated SHA-1 is mandated by the Tesla protocol, not our choice.
        sha1.hasher().hashBlocking(publicKeyRaw).copyOf(KEY_ID_SIZE)

    private const val KEY_ID_SIZE = 4
}

/** Decodes a stored PKCS#8 key; internal so tests can build a handle and then discard the bytes. */
@OptIn(DelicateCryptographyApi::class)
internal fun decodePrivateKeyPkcs8(pkcs8: ByteArray) =
    ecdh
        .privateKeyDecoder(EC.Curve.P256)
        .decodeFromByteArrayBlocking(EC.PrivateKey.Format.DER.Generic, pkcs8)

@OptIn(DelicateCryptographyApi::class)
private val ecdh = CryptographyProvider.Default.get(ECDH)

@OptIn(DelicateCryptographyApi::class)
private val sha1 = CryptographyProvider.Default.get(SHA1)
