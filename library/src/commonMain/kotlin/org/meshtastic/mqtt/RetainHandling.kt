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
 * Controls when the broker sends retained messages for a subscription (§3.8.3.1).
 *
 * When subscribing to a topic that has a retained message, this option controls
 * whether and when that retained message is delivered to the subscribing client.
 *
 * @property value The numeric option value (0, 1, or 2) as encoded in the SUBSCRIBE packet.
 */
public enum class RetainHandling(
    public val value: Int,
) {
    /**
     * Send retained messages at the time of the subscribe (default).
     *
     * The broker delivers any retained message for the matching topic immediately
     * when this subscription is established, regardless of whether a prior
     * subscription existed.
     */
    SEND_AT_SUBSCRIBE(0),

    /**
     * Send retained messages only if the subscription does not already exist.
     *
     * The broker delivers retained messages only on the first subscribe for a topic.
     * Re-subscribing to an already-active topic does not re-trigger delivery.
     */
    SEND_IF_NEW_SUBSCRIPTION(1),

    /**
     * Do not send retained messages at the time of the subscribe.
     *
     * Retained messages are never sent when the subscription is established.
     * The client only receives messages published after the subscription is active.
     */
    DO_NOT_SEND(2),
    ;

    public companion object {
        /**
         * Returns the [RetainHandling] corresponding to the given numeric [value].
         *
         * @param value The retain handling option (0, 1, or 2).
         * @throws IllegalArgumentException if [value] is not a valid retain handling option.
         */
        public fun fromValue(value: Int): RetainHandling =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown retain handling: $value")
    }
}
