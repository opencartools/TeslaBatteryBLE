// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class MetadataTest {
    @Test
    fun `computes the reference sha256 checksum`() {
        val expected =
            byteArrayOf(
                0xab.toByte(),
                0xab.toByte(),
                0x04,
                0xd8.toByte(),
                0x04,
                0x49,
                0x98.toByte(),
                0x13,
                0x38,
                0x2e,
                0xfd.toByte(),
                0x74,
                0xa0.toByte(),
                0x67,
                0x91.toByte(),
                0xce.toByte(),
                0x2d,
                0xe7.toByte(),
                0x77,
                0x43,
                0x96.toByte(),
                0x03,
                0x24,
                0x6d,
                0xfb.toByte(),
                0xaa.toByte(),
                0x83.toByte(),
                0x92.toByte(),
                0xca.toByte(),
                0x05,
                0x86.toByte(),
                0x8e.toByte(),
            )

        assertContentEquals(expected, referenceMetadata("testVIN").checksum(byteArrayOf()))
    }

    @Test
    fun `computes a stable hmac checksum for a fixed key`() {
        val metadata = Metadata.hmacSha256(ByteArray(16) { it.toByte() }).add(2, "testVIN".encodeToByteArray())
        assertContentEquals(HMAC_CHECKSUM.hexToByteArray(), metadata.checksum(byteArrayOf()))
    }

    @Test
    fun `rejects out of order tags`() {
        val metadata =
            Metadata
                .sha256()
                .add(1, byteArrayOf(1))
                .add(2, byteArrayOf(2))
        assertFailsWith<IllegalArgumentException> {
            metadata.add(1, byteArrayOf(3))
        }
    }

    @Test
    fun `rejects fields longer than 255 bytes`() {
        val metadata = Metadata.sha256()
        assertFailsWith<IllegalArgumentException> {
            metadata.add(1, ByteArray(256))
        }
    }

    @Test
    fun `encodes uint32 as big endian`() {
        val metadata = Metadata.sha256().addUint32(5, 0x0E74)
        val expected = Metadata.sha256().add(5, byteArrayOf(0x00, 0x00, 0x0e, 0x74))
        assertContentEquals(expected.checksum(byteArrayOf()), metadata.checksum(byteArrayOf()))
    }

    private fun referenceMetadata(vin: String): Metadata =
        Metadata
            .sha256()
            .add(0, byteArrayOf(0x05))
            .add(1, byteArrayOf(0x02))
            .add(2, vin.toByteArray())
            .add(
                3,
                byteArrayOf(
                    0xaa.toByte(),
                    0xda.toByte(),
                    0x92.toByte(),
                    0x8a.toByte(),
                    0x4f,
                    0x21,
                    0x5f,
                    0x55,
                    0xf9.toByte(),
                    0xe6.toByte(),
                    0xe4.toByte(),
                    0x5e,
                    0x66,
                    0xb6.toByte(),
                    0x52,
                    0x1e,
                ),
            ).add(4, byteArrayOf(0x00, 0x00, 0x0e, 0x74))
            .add(5, byteArrayOf(0x00, 0x00, 0x05, 0x3a))

    private companion object {
        // Regression value from this implementation. The HMAC path is used by
        // the Fleet API transport; BLE uses AES-GCM.
        const val HMAC_CHECKSUM =
            "d2c9fd6a2d8428f90dda6e03838dfb6303f591470cb46b628f642ec6a3af427a"
    }
}
