// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import okio.ByteString
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the debug log's reading of the fields the vendored schema does not define. */
class UnknownFieldsTest {
    @Test
    fun `an empty message has nothing to show`() {
        assertEquals("", unknownFieldsSummary(ByteString.EMPTY))
    }

    @Test
    fun `a varint shows in decimal`() {
        // Field 7, varint: (7 << 3) | 0 = 0x38.
        assertEquals("7=2", unknownFieldsSummary("3802".decodeHex()))
        assertEquals("7=3", unknownFieldsSummary("3803".decodeHex()))
    }

    @Test
    fun `an unknown wrapper reads through to its inside`() {
        // Field 8, length-delimited (0x42), two bytes: field 1 varint 2.
        assertEquals("8.1=2", unknownFieldsSummary("42020802".decodeHex()))
        assertEquals("7=2, 8.1=2", unknownFieldsSummary("380242020802".decodeHex()))
    }

    @Test
    fun `a fixed-width field shows its bytes`() {
        // Field 9, fixed32: (9 << 3) | 5 = 0x4d.
        assertEquals("9=hex:01020304", unknownFieldsSummary("4d01020304".decodeHex()))
    }

    @Test
    fun `a payload that is not a message stays its bytes`() {
        // Field 10, length-delimited, "abc": not a well-formed message.
        assertEquals("10=hex:616263", unknownFieldsSummary("5203616263".decodeHex()))
    }

    @Test
    fun `bytes that do not parse at all are kept whole`() {
        // A tag with no value after it.
        assertEquals("raw:38", unknownFieldsSummary("38".decodeHex()))
    }
}
