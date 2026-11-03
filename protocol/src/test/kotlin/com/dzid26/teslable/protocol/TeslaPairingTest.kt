// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.KeyFormFactor
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.SignatureType
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import dev.whyoleg.cryptography.algorithms.EC
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeslaPairingTest {
    @Test
    fun `generates a p256 key pair with an uncompressed public key`() {
        val keyPair = TeslaKeys.generate()
        assertEquals(65, keyPair.publicKeyRaw.size)
        assertEquals(0x04, keyPair.publicKeyRaw[0].toInt())
        assertTrue(keyPair.privateKeyPkcs8.isNotEmpty())
        assertEquals(4, TeslaKeys.keyId(keyPair.publicKeyRaw).size)
    }

    @Test
    fun `encodes a public key with a short x coordinate at full width`() {
        // Found by an offline search over scalars of consecutive bytes, like
        // the Go fixture keys; Go and OpenSSL agree on its public key. X starts
        // 00 1b, so BigInteger.toByteArray() returns 31 bytes that the encoding
        // must pad. Y starts b2, so it returns 33 bytes with a sign byte that
        // the encoding must drop.
        val publicKey = TeslaCrypto.rawToPublicKey(SHORT_X_PUBLIC.hex())
        val encoded = publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW)

        assertContentEquals(SHORT_X_PUBLIC.hex(), encoded)
    }

    @Test
    fun `add key request is a present-key envelope with the public key and role`() {
        val keyPair = TeslaKeys.generate()
        val envelope =
            ToVCSECMessage.ADAPTER.decode(
                TeslaPairing.buildAddKeyRequest(keyPair.publicKeyRaw),
            )
        val signedMessage = envelope.signedMessage
        assertNotNull(signedMessage)
        assertEquals(SignatureType.SIGNATURE_TYPE_PRESENT_KEY, signedMessage.signatureType)

        val payload = UnsignedMessage.ADAPTER.decode(signedMessage.protobufMessageAsBytes!!)
        val operation = payload.VCSEC_WhitelistOperation
        assertNotNull(operation)
        val change = operation.addKeyToWhitelistAndAddPermissions
        assertNotNull(change)
        assertContentEquals(keyPair.publicKeyRaw, change.key?.PublicKeyRaw?.toByteArray())
        assertEquals(Role.ROLE_CHARGING_MANAGER, change.keyRole)
        assertEquals(
            KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE,
            operation.metadataForKey?.keyFormFactor,
        )
    }

    @Test
    fun `parses raw add key responses`() {
        assertEquals(
            TeslaPairing.Result.WAITING_FOR_CARD,
            TeslaPairing.parseAddKeyResponse(rawResponse(OperationStatus_E.OPERATIONSTATUS_WAIT)),
        )
        assertEquals(
            TeslaPairing.Result.OK,
            TeslaPairing.parseAddKeyResponse(rawResponse(OperationStatus_E.OPERATIONSTATUS_OK)),
        )
        assertEquals(
            TeslaPairing.Result.ERROR,
            TeslaPairing.parseAddKeyResponse(rawResponse(OperationStatus_E.OPERATIONSTATUS_ERROR)),
        )
        assertEquals(null, TeslaPairing.parseAddKeyResponse(byteArrayOf()))
    }

    @Test
    fun `parses wrapped add key responses`() {
        val payload =
            FromVCSECMessage(
                commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT),
            ).encode()
        val wrapped = RoutableMessage(protobuf_message_as_bytes = payload.toByteString()).encode()
        assertEquals(TeslaPairing.Result.WAITING_FOR_CARD, TeslaPairing.parseAddKeyResponse(wrapped))
    }

    private fun rawResponse(status: OperationStatus_E): ByteArray =
        FromVCSECMessage(
            commandStatus = CommandStatus(operationStatus = status),
        ).encode()

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val SHORT_X_PUBLIC =
            "04001b54c0f1f9c7ee4e7d2382a65a6190b43f1e4429d2b19a929351869355d7d2" +
                "b2c7f4b35e385a906559d4cfa608d74ed583ce27c2993a84a0075dc5284861d8"
    }
}
