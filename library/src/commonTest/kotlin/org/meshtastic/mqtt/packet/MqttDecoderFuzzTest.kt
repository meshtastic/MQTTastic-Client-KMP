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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Randomized / malformed-input fuzz tests for [decodePacket].
 *
 * The contract is simple: the decoder MUST NEVER crash with anything other
 * than [IllegalArgumentException] (or its subclass) or [IndexOutOfBoundsException]
 * for truly arbitrary input. An [OutOfMemoryError], [StackOverflowError], or
 * unchecked [NullPointerException] would indicate a CVE-class bug.
 *
 * Seeds are fixed so failures are reproducible in CI.
 */
class MqttDecoderFuzzTest {
    private fun runFuzz(
        iterations: Int,
        maxLen: Int,
        seed: Long,
    ) {
        val rng = Random(seed)
        repeat(iterations) { i ->
            val len = rng.nextInt(0, maxLen)
            val bytes = ByteArray(len).also { rng.nextBytes(it) }
            try {
                decodePacket(bytes)
                // Success is fine — a random byte sequence could be a valid packet.
            } catch (_: IllegalArgumentException) {
                // Expected failure mode.
            } catch (_: IndexOutOfBoundsException) {
                // Acceptable — underlying array reads surface this for truncated input.
            } catch (_: NumberFormatException) {
                // Acceptable for pathological enum/reason-code values.
            } catch (e: Throwable) {
                fail(
                    "Iteration $i (seed=$seed, len=$len) threw unexpected " +
                        "${e::class.simpleName}: ${e.message}\nbytes=${bytes.toHex()}",
                )
            }
        }
    }

    @Test
    fun decoder_never_crashes_on_random_bytes_small() {
        runFuzz(iterations = 5_000, maxLen = 16, seed = 0xC0FFEE)
    }

    @Test
    fun decoder_never_crashes_on_random_bytes_medium() {
        runFuzz(iterations = 2_000, maxLen = 256, seed = 0xBADC0DE)
    }

    @Test
    fun decoder_never_crashes_on_random_bytes_large() {
        runFuzz(iterations = 500, maxLen = 4096, seed = 0xDEADBEEF)
    }

    @Test
    fun decoder_rejects_empty_input_cleanly() {
        val err = runCatching { decodePacket(ByteArray(0)) }.exceptionOrNull()
        check(err is IllegalArgumentException) { "Expected IAE, got $err" }
    }

    @Test
    fun decoder_rejects_truncated_vbi_remaining_length() {
        // 0x30 = PUBLISH with flags=0; then a VBI that claims continuation but never terminates.
        val bytes = byteArrayOf(0x30, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val err = runCatching { decodePacket(bytes) }.exceptionOrNull()
        check(err is IllegalArgumentException || err is IndexOutOfBoundsException) {
            "Expected IAE/IOOBE, got $err"
        }
    }

    @Test
    fun decoder_rejects_declared_length_larger_than_buffer() {
        // Fixed header byte + VBI = 127 (largest single-byte VBI), but we only supply 3 payload bytes.
        val bytes = byteArrayOf(0x30, 0x7F, 0x00, 0x00, 0x00)
        val err = runCatching { decodePacket(bytes) }.exceptionOrNull()
        check(err is IllegalArgumentException) { "Expected IAE, got $err" }
    }

    @Test
    fun decoder_handles_every_possible_first_byte() {
        // Cover every (type, flags) combination with a minimal body.
        val failures = mutableListOf<String>()
        for (firstByte in 0..0xFF) {
            val bytes = byteArrayOf(firstByte.toByte(), 0x00)
            try {
                decodePacket(bytes)
            } catch (_: IllegalArgumentException) {
                // Fine.
            } catch (_: IndexOutOfBoundsException) {
                // Fine.
            } catch (e: Throwable) {
                failures += "firstByte=0x${firstByte.toString(16)} -> ${e::class.simpleName}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail("Unexpected decoder failures:\n${failures.joinToString("\n")}")
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
