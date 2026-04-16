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
 * Quality of Service levels for MQTT message delivery per §4.3.
 *
 * QoS determines the delivery guarantee between the client and broker:
 * - [AT_MOST_ONCE] (0): Best-effort, no acknowledgement. Messages may be lost.
 * - [AT_LEAST_ONCE] (1): Guaranteed delivery via PUBACK. Messages may be duplicated.
 * - [EXACTLY_ONCE] (2): Guaranteed single delivery via the 4-step PUBLISH → PUBREC → PUBREL → PUBCOMP handshake.
 *
 * Higher QoS levels have higher overhead. Choose the level appropriate for your use case:
 * telemetry data tolerates [AT_MOST_ONCE], while financial transactions require [EXACTLY_ONCE].
 *
 * @property value The numeric QoS level (0, 1, or 2) as encoded on the wire.
 */
public enum class QoS(
    public val value: Int,
) {
    /**
     * At most once delivery (QoS 0) — fire and forget.
     *
     * The message is delivered at most once, with no acknowledgement from the receiver.
     * This is the fastest and lowest-overhead option, but messages may be lost if the
     * network connection fails.
     */
    AT_MOST_ONCE(0),

    /**
     * At least once delivery (QoS 1) — acknowledged delivery.
     *
     * The message is guaranteed to arrive at least once. The sender stores the message
     * until it receives a PUBACK from the receiver. If no PUBACK arrives, the message
     * is retransmitted with the DUP flag set. This may result in duplicate messages.
     */
    AT_LEAST_ONCE(1),

    /**
     * Exactly once delivery (QoS 2) — assured delivery via 4-step handshake.
     *
     * The message is guaranteed to arrive exactly once using the full
     * PUBLISH → PUBREC → PUBREL → PUBCOMP handshake. This is the safest but
     * slowest option, requiring four network round-trips per message.
     */
    EXACTLY_ONCE(2),
    ;

    public companion object {
        /**
         * Returns the [QoS] corresponding to the given numeric [value].
         *
         * @param value The QoS level (0, 1, or 2).
         * @throws IllegalArgumentException if [value] is not a valid QoS level.
         */
        public fun fromValue(value: Int): QoS =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Invalid QoS value: $value")
    }
}
