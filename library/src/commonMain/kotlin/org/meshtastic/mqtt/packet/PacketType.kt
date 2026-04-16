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
 * MQTT 5.0 packet type identifiers and required fixed-header flags per §2.1.2–§2.1.3.
 *
 * @property value Packet type code (upper 4 bits of fixed header byte 1).
 * @property requiredFlags Required lower 4 bits of fixed header byte 1. Null means flags are variable (PUBLISH).
 */
internal enum class PacketType(
    val value: Int,
    val requiredFlags: Int?,
) {
    CONNECT(1, 0b0000),
    CONNACK(2, 0b0000),
    PUBLISH(3, null), // Flags are DUP/QoS/Retain — variable per packet
    PUBACK(4, 0b0000),
    PUBREC(5, 0b0000),
    PUBREL(6, 0b0010),
    PUBCOMP(7, 0b0000),
    SUBSCRIBE(8, 0b0010),
    SUBACK(9, 0b0000),
    UNSUBSCRIBE(10, 0b0010),
    UNSUBACK(11, 0b0000),
    PINGREQ(12, 0b0000),
    PINGRESP(13, 0b0000),
    DISCONNECT(14, 0b0000),
    AUTH(15, 0b0000),
    ;

    companion object {
        private val byValue: Map<Int, PacketType> = entries.associateBy { it.value }

        fun fromValue(value: Int): PacketType =
            byValue[value]
                ?: throw IllegalArgumentException("Unknown packet type: $value")
    }
}
