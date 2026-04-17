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
 * Observable connection lifecycle state for [MqttClient].
 *
 * Collect [MqttClient.connectionState] as a [kotlinx.coroutines.flow.StateFlow] to
 * observe state transitions and react to connectivity changes — including the
 * underlying cause when a connection drops or a reconnect attempt fails.
 *
 * Typical lifecycle: [Disconnected] → [Connecting] → [Connected] → [Disconnected].
 * When auto-reconnect is enabled and a connection drops unexpectedly:
 * [Connected] → [Reconnecting] → [Connected] (or [Disconnected] on permanent failure).
 *
 * ## Example — react to disconnect reasons
 * ```kotlin
 * client.connectionState.collect { state ->
 *     when (state) {
 *         is ConnectionState.Connected -> println("Online")
 *         is ConnectionState.Connecting -> println("Connecting...")
 *         is ConnectionState.Reconnecting -> println("Reconnecting (attempt ${state.attempt})")
 *         is ConnectionState.Disconnected -> when (val reason = state.reason) {
 *             null -> println("Offline")
 *             else -> println("Offline: ${reason.reasonCode} — ${reason.message}")
 *         }
 *     }
 * }
 * ```
 *
 * ## Migration from 0.1.x
 * In 0.1.x `ConnectionState` was an enum with `CONNECTING`/`CONNECTED`/`DISCONNECTED`/
 * `RECONNECTING` constants. Replace equality checks (`state == ConnectionState.CONNECTED`)
 * with `is` checks (`state is ConnectionState.Connected`).
 */
public sealed class ConnectionState {
    /**
     * Actively establishing a connection to the broker.
     *
     * The CONNECT packet has been (or is about to be) sent and the client
     * is awaiting a CONNACK response.
     */
    public object Connecting : ConnectionState() {
        override fun toString(): String = "Connecting"
    }

    /**
     * Successfully connected to the broker and ready for publish/subscribe operations.
     *
     * The CONNECT/CONNACK handshake completed with [org.meshtastic.mqtt.packet.ReasonCode.SUCCESS].
     * The background read loop and keepalive timer are active.
     */
    public object Connected : ConnectionState() {
        override fun toString(): String = "Connected"
    }

    /**
     * Attempting to re-establish a lost connection.
     *
     * Entered automatically when the connection drops unexpectedly and
     * [MqttConfig.autoReconnect] is `true`. Uses exponential backoff between
     * attempts (configurable via [MqttConfig.reconnectBaseDelayMs] and
     * [MqttConfig.reconnectMaxDelayMs]). Subscriptions are automatically
     * re-established on successful reconnection.
     *
     * @property attempt 1-based count of reconnect attempts performed so far.
     *   `0` indicates the reconnect loop has just started and no attempt has
     *   been made yet.
     * @property lastError The most recent failure that triggered or extended
     *   reconnection, or `null` if reconnect was triggered by a graceful
     *   transport drop with no exception.
     */
    public data class Reconnecting(
        val attempt: Int,
        val lastError: MqttException? = null,
    ) : ConnectionState()

    /**
     * Not connected to any broker.
     *
     * Either no connection has been attempted, the last connection was closed
     * by [MqttClient.disconnect]/[MqttClient.close], or it was lost due to a
     * broker-initiated DISCONNECT or transport failure.
     *
     * @property reason The error that caused the disconnect, or `null` for an
     *   intentional disconnect via [MqttClient.disconnect]/[MqttClient.close]
     *   and for the initial state before any connection has been attempted.
     */
    public data class Disconnected(
        val reason: MqttException? = null,
    ) : ConnectionState() {
        public companion object {
            /** Stable singleton for the initial idle / "no reason" state. */
            public val Idle: Disconnected = Disconnected(reason = null)
        }
    }
}
