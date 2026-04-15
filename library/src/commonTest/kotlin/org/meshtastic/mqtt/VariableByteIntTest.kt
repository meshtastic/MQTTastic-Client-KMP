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
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(1, consumed)
        }
    }

    @Test
    fun roundTripTwoByte() {
        for (v in listOf(128, 16383)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(2, encoded.size, "VBI($v) should be 2 bytes")
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(2, consumed)
        }
    }

    @Test
    fun roundTripThreeByte() {
        for (v in listOf(16384, 2097151)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(3, encoded.size, "VBI($v) should be 3 bytes")
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(3, consumed)
        }
    }

    @Test
    fun roundTripFourByte() {
        for (v in listOf(2097152, 268435455)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(4, encoded.size, "VBI($v) should be 4 bytes")
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(4, consumed)
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
        // Continuation bit set but no next byte
        assertFailsWith<IllegalArgumentException> {
            VariableByteInt.decode(byteArrayOf(0x80.toByte()))
        }
    }

    @Test
    fun decodeRejectsFiveByteTooLong() {
        // 5-byte encoding with continuation bits — exceeds 4-byte limit
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
        val (decoded, consumed) = VariableByteInt.decode(combined, offset = 2)
        assertEquals(300, decoded)
        assertEquals(vbi.size, consumed)
    }
}
