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
