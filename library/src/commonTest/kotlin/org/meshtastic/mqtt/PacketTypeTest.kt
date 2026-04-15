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

import org.meshtastic.mqtt.packet.PacketType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PacketTypeTest {
    @Test
    fun fromValueReturnsCorrectType() {
        assertEquals(PacketType.CONNECT, PacketType.fromValue(1))
        assertEquals(PacketType.CONNACK, PacketType.fromValue(2))
        assertEquals(PacketType.PUBLISH, PacketType.fromValue(3))
        assertEquals(PacketType.PUBACK, PacketType.fromValue(4))
        assertEquals(PacketType.PUBREC, PacketType.fromValue(5))
        assertEquals(PacketType.PUBREL, PacketType.fromValue(6))
        assertEquals(PacketType.PUBCOMP, PacketType.fromValue(7))
        assertEquals(PacketType.SUBSCRIBE, PacketType.fromValue(8))
        assertEquals(PacketType.SUBACK, PacketType.fromValue(9))
        assertEquals(PacketType.UNSUBSCRIBE, PacketType.fromValue(10))
        assertEquals(PacketType.UNSUBACK, PacketType.fromValue(11))
        assertEquals(PacketType.PINGREQ, PacketType.fromValue(12))
        assertEquals(PacketType.PINGRESP, PacketType.fromValue(13))
        assertEquals(PacketType.DISCONNECT, PacketType.fromValue(14))
        assertEquals(PacketType.AUTH, PacketType.fromValue(15))
    }

    @Test
    fun fromValueThrowsOnInvalid() {
        assertFailsWith<IllegalArgumentException> { PacketType.fromValue(0) }
        assertFailsWith<IllegalArgumentException> { PacketType.fromValue(16) }
        assertFailsWith<IllegalArgumentException> { PacketType.fromValue(-1) }
    }

    @Test
    fun requiredFlagsCorrectPerSpec() {
        // Per §2.1.3: PUBREL, SUBSCRIBE, UNSUBSCRIBE require flags 0b0010
        assertEquals(0b0010, PacketType.PUBREL.requiredFlags)
        assertEquals(0b0010, PacketType.SUBSCRIBE.requiredFlags)
        assertEquals(0b0010, PacketType.UNSUBSCRIBE.requiredFlags)

        // PUBLISH has variable flags (DUP/QoS/Retain)
        assertNull(PacketType.PUBLISH.requiredFlags)

        // All others require 0b0000
        val zeroFlagTypes =
            listOf(
                PacketType.CONNECT,
                PacketType.CONNACK,
                PacketType.PUBACK,
                PacketType.PUBREC,
                PacketType.PUBCOMP,
                PacketType.SUBACK,
                PacketType.UNSUBACK,
                PacketType.PINGREQ,
                PacketType.PINGRESP,
                PacketType.DISCONNECT,
                PacketType.AUTH,
            )
        for (type in zeroFlagTypes) {
            assertEquals(0b0000, type.requiredFlags, "$type should have flags 0b0000")
        }
    }

    @Test
    fun allFifteenPacketTypesCovered() {
        assertEquals(15, PacketType.entries.size)
    }
}
