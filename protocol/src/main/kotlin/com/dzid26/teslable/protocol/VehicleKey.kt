// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

/**
 * One car's pairing key as the protocol uses it (ADR-0007, issue #119).
 *
 * A key is a handle, not bytes: an implementation may hold PKCS#8 (the
 * software [TeslaKeyPair]) or a non-exportable platform key (Android Keystore
 * `PURPOSE_AGREE_KEY`, Secure Enclave/CryptoKit). The Tesla session-key rule,
 * `SHA-1(shared secret)[:16]`, stays in [sessionKeyWith], so an implementation
 * supplies only the ECDH result and cannot choose a different truncation.
 */
abstract class VehicleKey {
    /** The uncompressed P-256 public point: 65 bytes, starting with 0x04. Cheap: no key-store call. */
    abstract val publicKeyRaw: ByteArray

    /**
     * The 16-byte Tesla session key for [peerPublicRaw]: the truncated SHA-1 of
     * the ECDH shared secret, as the protocol mandates. Final on purpose.
     *
     * @throws IllegalArgumentException when [peerPublicRaw] is not an uncompressed P-256 point.
     */
    fun sessionKeyWith(peerPublicRaw: ByteArray): ByteArray {
        TeslaCrypto.requireUncompressedPoint(peerPublicRaw)
        return TeslaCrypto.sessionKey(sharedSecretWith(peerPublicRaw))
    }

    /**
     * The raw ECDH shared secret with [peerPublicRaw], the X coordinate exactly
     * as the platform key agreement returns it. Implementations must not hash,
     * truncate or pad it; [sessionKeyWith] does the protocol's derivation.
     */
    protected abstract fun sharedSecretWith(peerPublicRaw: ByteArray): ByteArray
}
