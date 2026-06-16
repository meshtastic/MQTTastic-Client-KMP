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
package org.meshtastic.mqtt

import org.meshtastic.mqtt.packet.VariableByteInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariableByteIntTest {
    // --- Canonical byte-vector tests (spec §1.5.5 examples) ---

    @Test
    fun encodeZeroToSingleByte() {
        assertContentEquals(byteArrayOf(0x00), VariableByteInt.encode(0))
    }

    @Test
    fun encode127ToSingleByte() {
        assertContentEquals(byteArrayOf(0x7F), VariableByteInt.encode(127))
    }

    @Test
    fun encode128ToTwoBytes() {
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), VariableByteInt.encode(128))
    }

    @Test
    fun encode16383ToTwoBytes() {
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x7F), VariableByteInt.encode(16383))
    }

    @Test
    fun encode16384ToThreeBytes() {
        assertContentEquals(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01),
            VariableByteInt.encode(16384),
        )
    }

    @Test
    fun encode2097151ToThreeBytes() {
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F),
            VariableByteInt.encode(2097151),
        )
    }

    @Test
    fun encode2097152ToFourBytes() {
        assertContentEquals(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01),
            VariableByteInt.encode(2097152),
        )
    }

    @Test
    fun encodeMaxValueToFourBytes() {
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
            VariableByteInt.encode(268435455),
        )
    }

    // --- Round-trip tests at each byte boundary ---

    @Test
    fun roundTripSingleByte() {
        for (v in listOf(0, 1, 127)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(1, encoded.size, "VBI($v) should be 1 byte")
            val result = VariableByteInt.decode(encoded)
            assertEquals(v, result.value)
            assertEquals(1, result.bytesConsumed)
        }
    }

    @Test
    fun roundTripTwoByte() {
        for (v in listOf(128, 16383)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(2, encoded.size, "VBI($v) should be 2 bytes")
            val result = VariableByteInt.decode(encoded)
            assertEquals(v, result.value)
            assertEquals(2, result.bytesConsumed)
        }
    }

    @Test
    fun roundTripThreeByte() {
        for (v in listOf(16384, 2097151)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(3, encoded.size, "VBI($v) should be 3 bytes")
            val result = VariableByteInt.decode(encoded)
            assertEquals(v, result.value)
            assertEquals(3, result.bytesConsumed)
        }
    }

    @Test
    fun roundTripFourByte() {
        for (v in listOf(2097152, 268435455)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(4, encoded.size, "VBI($v) should be 4 bytes")
            val result = VariableByteInt.decode(encoded)
            assertEquals(v, result.value)
            assertEquals(4, result.bytesConsumed)
        }
    }

    // --- Error cases ---

    @Test
    fun encodeRejectsNegative() {
        assertFailsWith<IllegalArgumentException> { VariableByteInt.encode(-1) }
    }

    @Test
    fun encodeRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> { VariableByteInt.encode(268435456) }
    }

    @Test
    fun decodeRejectsNegativeOffset() {
        assertFailsWith<IllegalArgumentException> {
            VariableByteInt.decode(byteArrayOf(0x00), offset = -1)
        }
    }

    @Test
    fun decodeRejectsTruncatedInput() {
        assertFailsWith<IllegalArgumentException> {
            VariableByteInt.decode(byteArrayOf(0x80.toByte()))
        }
    }

    @Test
    fun decodeRejectsFiveByteTooLong() {
        assertFailsWith<IllegalArgumentException> {
            VariableByteInt.decode(
                byteArrayOf(
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x80.toByte(),
                    0x01,
                ),
            )
        }
    }

    @Test
    fun decodeRejectsEmptyInput() {
        assertFailsWith<IllegalArgumentException> {
            VariableByteInt.decode(byteArrayOf())
        }
    }

    // --- Offset handling ---

    @Test
    fun decodeWithOffset() {
        val prefix = byteArrayOf(0x00, 0x00)
        val vbi = VariableByteInt.encode(300)
        val combined = prefix + vbi
        val result = VariableByteInt.decode(combined, offset = 2)
        assertEquals(300, result.value)
        assertEquals(vbi.size, result.bytesConsumed)
    }
}
