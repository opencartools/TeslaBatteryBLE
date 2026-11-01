// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VehicleKeyTest {
    @Test
    fun `the protocol owns the key derivation, not the implementation`() {
        val secret = ByteArray(32) { it.toByte() }
        val key = FixedSecretKey(secret)

        val sessionKey = key.sessionKeyWith(PEER_POINT)

        assertContentEquals(TeslaCrypto.sessionKey(secret), sessionKey)
        assertEquals(TeslaCrypto.SHARED_KEY_SIZE, sessionKey.size)
        // A handle that hands back the session key as its "shared secret" is
        // hashed again: the truncation cannot be skipped by an implementation.
        assertFalse(sessionKey.contentEquals(secret))
    }

    @Test
    fun `a malformed peer point is rejected before the implementation runs`() {
        val key = FixedSecretKey(ByteArray(32))

        for (malformed in listOf(ByteArray(0), ByteArray(64), ByteArray(65))) {
            assertFailsWith<IllegalArgumentException> { key.sessionKeyWith(malformed) }
        }
        assertEquals(0, key.agreements)
    }

    /** Models a hardware handle: holds a secret, exposes no private key material. */
    private class FixedSecretKey(
        private val secret: ByteArray,
    ) : VehicleKey() {
        var agreements = 0

        override val publicKeyRaw = POINT

        override fun sharedSecretWith(peerPublicRaw: ByteArray): ByteArray {
            agreements++
            return secret
        }
    }

    private companion object {
        /** Any well-formed uncompressed P-256 point; the handle never decodes it. */
        val POINT = ByteArray(65).also { it[0] = 0x04 }

        val PEER_POINT =
            ByteArray(65).also {
                it[0] = 0x04
                it[64] = 1
            }
    }
}
