package org.meshtastic.mqtt.packet

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

        val bytes = mutableListOf<Byte>()
        var remaining = value
        do {
            var encodedByte = remaining % 128
            remaining /= 128
            if (remaining > 0) {
                encodedByte = encodedByte or 0x80
            }
            bytes.add(encodedByte.toByte())
        } while (remaining > 0)

        return bytes.toByteArray()
    }

    /**
     * Decode a Variable Byte Integer from the given bytes starting at [offset].
     * @return Pair of (decoded value, number of bytes consumed).
     * @throws IllegalArgumentException if the encoding is malformed.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
    ): Pair<Int, Int> {
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

        return Pair(value, index - offset)
    }
}
