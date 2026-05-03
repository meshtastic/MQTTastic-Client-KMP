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
 * Wire format encoding/decoding helpers for MQTT 5.0 primitive types (§1.5).
 *
 * All multi-byte integers use big-endian byte order per the MQTT spec.
 */
internal object WireFormat {
    // --- UTF-8 String (§1.5.4): 2-byte big-endian length prefix + UTF-8 bytes ---

    fun encodeUtf8String(value: String): ByteArray {
        val utf8 = value.encodeToByteArray()
        requireValidMqttUtf8(value)
        require(utf8.size <= 65_535) { "UTF-8 string length ${utf8.size} exceeds max 65535" }
        val result = ByteArray(2 + utf8.size)
        result[0] = (utf8.size shr 8).toByte()
        result[1] = (utf8.size and 0xFF).toByte()
        utf8.copyInto(result, 2)
        return result
    }

    /** @return Pair of (decoded string, total bytes consumed including length prefix). */
    fun decodeUtf8String(
        bytes: ByteArray,
        offset: Int,
    ): Pair<String, Int> {
        require(offset + 2 <= bytes.size) { "Not enough bytes for UTF-8 string length at offset $offset" }
        val length = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        require(offset + 2 + length <= bytes.size) {
            "Not enough bytes for UTF-8 string data: need $length at offset ${offset + 2}"
        }
        val str = bytes.decodeToString(offset + 2, offset + 2 + length)
        requireValidMqttUtf8(str)
        return str to (2 + length)
    }

    /**
     * Validate a string conforms to MQTT UTF-8 requirements (§1.5.4):
     * - Must not contain null character U+0000
     * - Must not contain unpaired surrogates U+D800..U+DFFF
     */
    private fun requireValidMqttUtf8(value: String) {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            require(ch != '\u0000') {
                "UTF-8 string must not contain null character U+0000 (§1.5.4)"
            }
            if (ch.isHighSurrogate()) {
                val next = value.getOrNull(i + 1)
                require(next != null && next.isLowSurrogate()) {
                    "UTF-8 string must not contain unpaired surrogate U+${ch.code.toString(16).uppercase()} (§1.5.4)"
                }
                i += 2 // skip the valid pair
            } else {
                require(!ch.isLowSurrogate()) {
                    "UTF-8 string must not contain unpaired surrogate U+${ch.code.toString(16).uppercase()} (§1.5.4)"
                }
                i++
            }
        }
    }

    // --- Binary Data (§1.5.6): 2-byte big-endian length prefix + raw bytes ---

    fun encodeBinaryData(value: ByteArray): ByteArray {
        require(value.size <= 65_535) { "Binary data length ${value.size} exceeds max 65535" }
        val result = ByteArray(2 + value.size)
        result[0] = (value.size shr 8).toByte()
        result[1] = (value.size and 0xFF).toByte()
        value.copyInto(result, 2)
        return result
    }

    /** @return Pair of (decoded bytes, total bytes consumed including length prefix). */
    fun decodeBinaryData(
        bytes: ByteArray,
        offset: Int,
    ): Pair<ByteArray, Int> {
        require(offset + 2 <= bytes.size) { "Not enough bytes for binary data length at offset $offset" }
        val length = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        require(offset + 2 + length <= bytes.size) {
            "Not enough bytes for binary data: need $length at offset ${offset + 2}"
        }
        val data = bytes.copyOfRange(offset + 2, offset + 2 + length)
        return data to (2 + length)
    }

    // --- Two Byte Integer (§1.5.2): big-endian UInt16 ---

    fun encodeTwoByteInt(value: Int): ByteArray {
        require(value in 0..65_535) { "Two Byte Integer $value out of range 0..65535" }
        return byteArrayOf(
            (value shr 8).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    fun decodeTwoByteInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        require(offset + 2 <= bytes.size) { "Not enough bytes for Two Byte Integer at offset $offset" }
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    // --- Four Byte Integer (§1.5.3): big-endian UInt32 ---

    fun encodeFourByteInt(value: Long): ByteArray {
        require(value in 0..4_294_967_295L) { "Four Byte Integer $value out of range 0..4294967295" }
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    fun decodeFourByteInt(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        require(offset + 4 <= bytes.size) { "Not enough bytes for Four Byte Integer at offset $offset" }
        return ((bytes[offset].toInt() and 0xFF).toLong() shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (bytes[offset + 3].toInt() and 0xFF).toLong()
    }

    // --- UTF-8 String Pair (§1.5.7) ---

    fun encodeStringPair(
        key: String,
        value: String,
    ): ByteArray {
        val encodedKey = encodeUtf8String(key)
        val encodedValue = encodeUtf8String(value)
        return encodedKey + encodedValue
    }

    /** @return Triple of (key, value, total bytes consumed). */
    fun decodeStringPair(
        bytes: ByteArray,
        offset: Int,
    ): Triple<String, String, Int> {
        val (key, keyConsumed) = decodeUtf8String(bytes, offset)
        val (value, valueConsumed) = decodeUtf8String(bytes, offset + keyConsumed)
        return Triple(key, value, keyConsumed + valueConsumed)
    }
}
