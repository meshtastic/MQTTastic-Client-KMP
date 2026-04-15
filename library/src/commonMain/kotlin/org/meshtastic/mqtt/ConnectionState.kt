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
 * Observable connection lifecycle states for [MqttClient].
 *
 * Collect [MqttClient.connectionState] as a [kotlinx.coroutines.flow.StateFlow] to
 * observe state transitions and react to connectivity changes in your application.
 *
 * Typical lifecycle: [DISCONNECTED] → [CONNECTING] → [CONNECTED] → [DISCONNECTED].
 * When auto-reconnect is enabled and a connection drops unexpectedly:
 * [CONNECTED] → [RECONNECTING] → [CONNECTED] (or [DISCONNECTED] on permanent failure).
 */
public enum class ConnectionState {
    /**
     * Actively establishing a connection to the broker.
     *
     * The CONNECT packet has been (or is about to be) sent and the client
     * is awaiting a CONNACK response.
     */
    CONNECTING,

    /**
     * Successfully connected to the broker and ready for publish/subscribe operations.
     *
     * The CONNECT/CONNACK handshake completed with [org.meshtastic.mqtt.packet.ReasonCode.SUCCESS].
     * The background read loop and keepalive timer are active.
     */
    CONNECTED,

    /**
     * Not connected to any broker.
     *
     * Either no connection has been attempted, or the last connection was
     * closed — either by [MqttClient.disconnect]/[MqttClient.close] or
     * due to a broker-initiated DISCONNECT.
     */
    DISCONNECTED,

    /**
     * Attempting to re-establish a lost connection.
     *
     * Entered automatically when the connection drops unexpectedly and
     * [MqttConfig.autoReconnect] is `true`. Uses exponential backoff
     * between attempts (configurable via [MqttConfig.reconnectBaseDelayMs]
     * and [MqttConfig.reconnectMaxDelayMs]). Subscriptions are automatically
     * re-established on successful reconnection.
     */
    RECONNECTING,
}
