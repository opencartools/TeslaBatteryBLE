// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import okio.ByteString

// The car's firmware sends fields the vendored schema does not define: a 2019 Model 3 on firmware
// 2026.38.3.1 puts VehicleStatus 7 and 8 in `unknownFields`, and neither our copy nor upstream
// names them. This summary is what the debug log prints, so a correlation run needs no separate
// parser: the fields show by number and value, and an unknown wrapper reads through to its inside.

/** How deep a length-delimited field is read as a nested message before it is shown as bytes. */
private const val MAX_DEPTH = 2

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5

/** The tag's low three bits are the wire type; the rest is the field number. */
private const val WIRE_TYPE_MASK = 0x7L
private const val FIELD_NUMBER_SHIFT = 3

private const val FIXED64_BYTES = 8
private const val FIXED32_BYTES = 4

private const val BYTE_MASK = 0xFF
private const val VARINT_PAYLOAD = 0x7F
private const val VARINT_CONTINUATION = 0x80
private const val VARINT_BITS_PER_BYTE = 7
private const val MAX_VARINT_BITS = 64

/**
 * "7=2, 8.1=2": the fields [bytes] carries that the schema does not define, by number and value.
 * A varint shows in decimal and a fixed-width field in hex. A length-delimited field is read as a
 * nested message when its bytes parse cleanly, so an unknown wrapper like `{1: 2}` reads `8.1=2`;
 * otherwise it stays `hex:<bytes>`, the honest reading of a string or an opaque blob. Bytes that do
 * not parse at all are kept whole as `raw:<hex>`, so nothing is dropped. Empty for empty input.
 */
fun unknownFieldsSummary(bytes: ByteString): String = summarize(bytes, prefix = "", depth = 0) ?: "raw:${bytes.hex()}"

private fun summarize(
    bytes: ByteString,
    prefix: String,
    depth: Int,
): String? {
    val parts = mutableListOf<String>()
    var index = 0
    while (index < bytes.size) {
        val field = readField(bytes, index) ?: return null
        index = field.end
        when (field.wireType) {
            WIRE_VARINT -> parts += "$prefix${field.number}=${field.value}"
            // An unknown message is the useful reading of a length-delimited field here; a string or
            // an opaque blob rarely parses as one, and falls back to its bytes.
            WIRE_LENGTH_DELIMITED ->
                parts +=
                    (if (depth < MAX_DEPTH) summarize(field.payload, "$prefix${field.number}.", depth + 1) else null)
                        ?: "$prefix${field.number}=hex:${field.payload.hex()}"
            else -> parts += "$prefix${field.number}=hex:${field.payload.hex()}"
        }
    }
    return parts.joinToString(", ")
}

private class Field(
    val number: Int,
    val wireType: Int,
    val value: Long,
    val payload: ByteString,
    val end: Int,
)

private class Varint(
    val value: Long,
    val next: Int,
)

/** What follows a field's tag: its value (varint only), the raw bytes, and where the next field starts. */
private class Body(
    val value: Long,
    val payload: ByteString,
    val end: Int,
)

/** One protobuf field at [start], or null when the bytes are not a well-formed field. */
private fun readField(
    bytes: ByteString,
    start: Int,
): Field? {
    val tag = readVarint(bytes, start) ?: return null
    val number = (tag.value ushr FIELD_NUMBER_SHIFT).toInt()
    val wireType = (tag.value and WIRE_TYPE_MASK).toInt()
    val body = readBody(bytes, tag.next, wireType)
    return if (number == 0 || body == null) null else Field(number, wireType, body.value, body.payload, body.end)
}

private fun readBody(
    bytes: ByteString,
    start: Int,
    wireType: Int,
): Body? =
    when (wireType) {
        WIRE_VARINT -> readVarint(bytes, start)?.let { Body(it.value, ByteString.EMPTY, it.next) }
        WIRE_FIXED64 -> fixedBody(bytes, start, FIXED64_BYTES)
        WIRE_FIXED32 -> fixedBody(bytes, start, FIXED32_BYTES)
        WIRE_LENGTH_DELIMITED -> lengthDelimitedBody(bytes, start)
        else -> null
    }

private fun fixedBody(
    bytes: ByteString,
    start: Int,
    width: Int,
): Body? = if (start + width > bytes.size) null else Body(0L, bytes.substring(start, start + width), start + width)

private fun lengthDelimitedBody(
    bytes: ByteString,
    start: Int,
): Body? {
    val length = readVarint(bytes, start) ?: return null
    return length
        .takeIf { it.value in 0..(bytes.size - it.next).toLong() }
        ?.let {
            val end = it.next + it.value.toInt()
            Body(0L, bytes.substring(it.next, end), end)
        }
}

private fun readVarint(
    bytes: ByteString,
    start: Int,
): Varint? {
    var value = 0L
    var shift = 0
    var index = start
    while (index < bytes.size && shift < MAX_VARINT_BITS) {
        val byte = bytes[index++].toInt() and BYTE_MASK
        value = value or ((byte and VARINT_PAYLOAD).toLong() shl shift)
        if (byte and VARINT_CONTINUATION == 0) return Varint(value, index)
        shift += VARINT_BITS_PER_BYTE
    }
    return null
}
