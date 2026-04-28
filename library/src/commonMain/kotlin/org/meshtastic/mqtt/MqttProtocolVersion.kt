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

/**
 * MQTT protocol version for the client connection.
 *
 * Determines the wire format, packet encoding, and available features.
 * MQTT 3.1.1 uses a simpler packet format without properties, reason codes
 * in most packets, or advanced features like topic aliases and enhanced auth.
 *
 * @property protocolLevel The protocol level byte sent in the CONNECT packet variable header.
 */
public enum class MqttProtocolVersion(
    public val protocolLevel: Int,
) {
    /** MQTT 3.1.1 (protocol level 4). */
    V3_1_1(4),

    /** MQTT 5.0 (protocol level 5). */
    V5_0(5),
    ;

    /** Returns `true` if this version supports MQTT 5.0 properties on packets. */
    public val supportsProperties: Boolean get() = this == V5_0

    public companion object {
        /** Look up version by protocol level byte. */
        public fun fromProtocolLevel(level: Int): MqttProtocolVersion =
            when (level) {
                4 -> V3_1_1

                5 -> V5_0

                else -> throw IllegalArgumentException(
                    "Unsupported MQTT protocol level: $level (supported: 4 for 3.1.1, 5 for 5.0)",
                )
            }
    }
}
