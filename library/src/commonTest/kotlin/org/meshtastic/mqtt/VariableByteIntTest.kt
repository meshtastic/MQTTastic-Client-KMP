package org.meshtastic.mqtt

import org.meshtastic.mqtt.packet.VariableByteInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariableByteIntTest {
    @Test
    fun encodeDecodeSingleByte() {
        // 0, 1, 127 — all single-byte values
        for (v in listOf(0, 1, 127)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(1, encoded.size, "VBI($v) should be 1 byte")
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(1, consumed)
        }
    }

    @Test
    fun encodeDecodeTwoByte() {
        // 128 and 16383 are two-byte boundary values
        for (v in listOf(128, 16383)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(2, encoded.size, "VBI($v) should be 2 bytes")
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(2, consumed)
        }
    }

    @Test
    fun encodeDecodeThreeByte() {
        for (v in listOf(16384, 2097151)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(3, encoded.size, "VBI($v) should be 3 bytes")
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(3, consumed)
        }
    }

    @Test
    fun encodeDecodeFourByte() {
        for (v in listOf(2097152, 268435455)) {
            val encoded = VariableByteInt.encode(v)
            assertEquals(4, encoded.size, "VBI($v) should be 4 bytes")
            val (decoded, consumed) = VariableByteInt.decode(encoded)
            assertEquals(v, decoded)
            assertEquals(4, consumed)
        }
    }

    @Test
    fun encodeRejectsNegative() {
        assertFailsWith<IllegalArgumentException> { VariableByteInt.encode(-1) }
    }

    @Test
    fun encodeRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> { VariableByteInt.encode(268435456) }
    }

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
