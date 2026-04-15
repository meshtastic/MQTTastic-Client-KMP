/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.mqtt.packet

/**
 * Result of decoding a Variable Byte Integer.
 *
 * @property value The decoded integer value.
 * @property bytesConsumed Number of bytes consumed from the input.
 */
internal data class VbiResult(
    val value: Int,
    val bytesConsumed: Int,
)

/**
 * Variable Byte Integer encoding/decoding per MQTT 5.0 §1.5.5.
 *
 * Each byte encodes 7 data bits (bits 0-6) plus a continuation bit (bit 7).
 * Maximum 4 bytes, maximum value 268,435,455.
 */
internal object VariableByteInt {
    const val MAX_VALUE: Int = 268_435_455

    /**
     * Encode an integer as a Variable Byte Integer.
     * @throws IllegalArgumentException if [value] exceeds [MAX_VALUE] or is negative.
     */
    fun encode(value: Int): ByteArray {
        require(value in 0..MAX_VALUE) { "Value $value out of VBI range 0..$MAX_VALUE" }

        val buf = ByteArray(4)
        var remaining = value
        var pos = 0
        do {
            var encodedByte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining > 0) encodedByte = encodedByte or 0x80
            buf[pos++] = encodedByte.toByte()
        } while (remaining > 0)

        return buf.copyOf(pos)
    }

    /**
     * Decode a Variable Byte Integer from the given bytes starting at [offset].
     * @return A [VbiResult] containing the decoded value and number of bytes consumed.
     * @throws IllegalArgumentException if the encoding is malformed.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
    ): VbiResult {
        require(offset >= 0) { "Offset must be non-negative, got: $offset" }

        var multiplier = 1
        var value = 0
        var index = offset

        do {
            require(index < bytes.size) { "Malformed VBI: unexpected end of data" }
            require(index - offset < 4) { "Malformed VBI: exceeds 4 bytes" }

            val encodedByte = bytes[index].toInt() and 0xFF
            value += (encodedByte and 0x7F) * multiplier
            multiplier *= 128
            index++
        } while (encodedByte and 0x80 != 0)

        return VbiResult(value, index - offset)
    }
}
