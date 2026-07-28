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
     * The connection attempt failed before the broker accepted or refused it.
     *
     * Covers transport-level failures during [MqttClient.connect] — DNS resolution, TCP connect,
     * TLS handshake, a socket that closed mid-handshake, and a CONNACK that never arrived. The
     * broker never expressed an opinion, so the attempt is worth retrying; contrast
     * [ConnectionRejected], which carries the broker's own refusal and will fail the same way
     * against the same configuration.
     */
    public class ConnectionFailed(
        reasonCode: ReasonCode,
        message: String,
        cause: Throwable? = null,
    ) : MqttException(reasonCode, message, cause)

    /**
     * The connection was lost unexpectedly, after it had been established.
     *
     * Emitted when the transport fails, keepalive times out, or the broker
     * sends a DISCONNECT packet. A failure during the initial handshake is
     * [ConnectionFailed] instead.
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

/**
 * Coerce an arbitrary [Throwable] into a public [MqttException] for inclusion in
 * [ConnectionState.Disconnected.reason] or [ConnectionState.Reconnecting.lastError].
 *
 * Pass-through for existing [MqttException] instances. Maps internal
 * `MqttConnectionException` to its public counterpart. All other throwables become
 * [MqttException.ConnectionLost] with [defaultReasonCode].
 *
 * Cancellation must be re-thrown by callers before reaching this helper —
 * structured concurrency must be preserved.
 */
internal fun Throwable.toMqttException(defaultReasonCode: ReasonCode = ReasonCode.UNSPECIFIED_ERROR): MqttException =
    when (this) {
        is MqttException -> {
            this
        }

        is MqttConnectionException -> {
            if (this.reasonCode.isConnectionRejection) {
                MqttException.ConnectionRejected(
                    reasonCode = this.reasonCode,
                    message = this.message ?: "Connection rejected",
                    cause = this.cause,
                    serverReference = this.serverReference,
                )
            } else if (this.failure == ConnectFailure.TRANSPORT) {
                MqttException.ConnectionFailed(
                    reasonCode = this.reasonCode,
                    message = this.message ?: "Connection failed",
                    cause = this.cause,
                )
            } else {
                MqttException.ConnectionLost(
                    reasonCode = this.reasonCode,
                    message = this.message ?: "Connection error",
                    cause = this.cause,
                )
            }
        }

        else -> {
            MqttException.ConnectionLost(
                reasonCode = defaultReasonCode,
                message = this.message ?: this::class.simpleName ?: "Unknown error",
                cause = this,
            )
        }
    }

/**
 * Map a handshake failure to the public [MqttException] the [MqttClient.connect] caller sees.
 *
 * Classification follows which side failed, not the reason code: a broker may refuse a CONNECT
 * with `PROTOCOL_ERROR`, and a transport failure synthesises `UNSPECIFIED_ERROR`, so the reason
 * code alone cannot separate "the broker said no" from "the network flaked". Only
 * [ConnectFailure.BROKER_REFUSAL] becomes [MqttException.ConnectionRejected]; retrying the others
 * against the same configuration may well succeed.
 *
 * Falls back to [toMqttException] when the origin is unset, which happens only for failures
 * raised outside the CONNECT/CONNACK handshake.
 */
internal fun MqttConnectionException.toConnectException(): MqttException =
    when (failure) {
        ConnectFailure.BROKER_REFUSAL -> {
            MqttException.ConnectionRejected(
                reasonCode = reasonCode,
                message = message ?: "Connection refused by broker",
                cause = cause,
                serverReference = serverReference,
            )
        }

        ConnectFailure.PROTOCOL_VIOLATION -> {
            MqttException.ProtocolError(
                reasonCode = reasonCode,
                message = message ?: "Protocol error during connect",
                cause = cause,
            )
        }

        ConnectFailure.TRANSPORT -> {
            MqttException.ConnectionFailed(
                reasonCode = reasonCode,
                message = message ?: "Connection failed",
                cause = cause,
            )
        }

        null -> {
            toMqttException()
        }
    }
