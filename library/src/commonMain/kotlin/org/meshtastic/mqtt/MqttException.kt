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
 * Base exception for all MQTT client errors.
 *
 * Every exception thrown by [MqttClient] extends this type, so consumers can
 * catch `MqttException` to handle any library error, or match specific subtypes
 * for fine-grained error handling.
 *
 * ## Example
 * ```kotlin
 * try {
 *     client.connect(endpoint)
 * } catch (e: MqttException.ConnectionRejected) {
 *     println("Broker rejected: ${e.reasonCode}")
 * } catch (e: MqttException.ProtocolError) {
 *     println("Protocol violation: ${e.message}")
 * } catch (e: MqttException) {
 *     println("MQTT error: ${e.message}")
 * }
 * ```
 *
 * @property reasonCode The MQTT 5.0 reason code associated with this error.
 */
public sealed class MqttException(
    public val reasonCode: ReasonCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The broker rejected the CONNECT request (§3.2).
     *
     * @property serverReference Optional server reference for redirect handling (§4.13).
     *   Non-null when the reason code is [ReasonCode.USE_ANOTHER_SERVER] or [ReasonCode.SERVER_MOVED].
     */
    public class ConnectionRejected(
        reasonCode: ReasonCode,
        message: String,
        cause: Throwable? = null,
        public val serverReference: String? = null,
    ) : MqttException(reasonCode, message, cause)

    /**
     * The connection was lost unexpectedly.
     *
     * Emitted when the transport fails, keepalive times out, or the broker
     * sends a DISCONNECT packet.
     */
    public class ConnectionLost(
        reasonCode: ReasonCode,
        message: String,
        cause: Throwable? = null,
    ) : MqttException(reasonCode, message, cause)

    /**
     * A protocol-level error occurred.
     *
     * Covers malformed packets, unexpected packet types from the broker,
     * and other MQTT protocol violations.
     */
    public class ProtocolError(
        reasonCode: ReasonCode,
        message: String,
        cause: Throwable? = null,
    ) : MqttException(reasonCode, message, cause)
}
