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
 * Quality of Service levels for MQTT message delivery.
 */
public enum class QoS(
    public val value: Int,
) {
    /** At most once delivery — fire and forget. */
    AT_MOST_ONCE(0),

    /** At least once delivery — acknowledged delivery. */
    AT_LEAST_ONCE(1),

    /** Exactly once delivery — assured delivery via 4-step handshake. */
    EXACTLY_ONCE(2),
    ;

    public companion object {
        public fun fromValue(value: Int): QoS =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Invalid QoS value: $value")
    }
}
