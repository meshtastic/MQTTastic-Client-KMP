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

import kotlinx.io.bytestring.ByteString

/**
 * Configuration for an MQTT 5.0 client connection.
 *
 * Maps to the fields and properties of the CONNECT packet (§3.1).
 * All binary data uses [ByteString] for immutability. Construct using
 * named parameters and Kotlin's `copy()` for immutable configuration variants.
 *
 * ## Example
 * ```kotlin
 * val config = MqttConfig(
 *     clientId = "sensor-hub-01",
 *     keepAliveSeconds = 30,
 *     cleanStart = false,
 *     autoReconnect = true,
 * )
 * ```
 *
 * @property clientId Client identifier sent to the broker. An empty string requests the
 *   broker to assign a unique identifier (returned in CONNACK via Assigned Client Identifier).
 * @property keepAliveSeconds Maximum interval in seconds between control packets (§3.1.2.10).
 *   The client sends PINGREQ if no other packet is sent within 75% of this interval.
 *   Set to `0` to disable the keepalive mechanism. Range: 0..65,535.
 * @property cleanStart If `true`, the broker discards any existing session state and starts
 *   fresh. If `false`, the broker resumes the previous session (identified by [clientId])
 *   including in-flight QoS 1/2 messages and active subscriptions (§3.1.2.4).
 * @property username Optional username for simple password-based authentication (§3.1.3.5).
 * @property password Optional password for authentication, stored as an immutable [ByteString] (§3.1.3.6).
 * @property will Optional will message configuration. If set, the broker publishes this message
 *   when the client disconnects unexpectedly (§3.1.3.2).
 * @property sessionExpiryInterval How long the broker retains session state after disconnection,
 *   in seconds. `0` = expire immediately on disconnect, `null` = use broker default.
 *   Max value: 4,294,967,295 (§3.1.2.11.3).
 * @property receiveMaximum Maximum number of concurrent in-flight QoS 1 and QoS 2 messages
 *   the client is willing to process. The broker will not send more than this number of
 *   unacknowledged publishes. Range: 1..65,535 (§3.1.2.11.4).
 * @property maximumPacketSize Maximum MQTT packet size (in bytes) the client can accept.
 *   `null` = no limit imposed. Range: 1..4,294,967,295 (§3.1.2.11.5).
 * @property topicAliasMaximum Maximum number of topic aliases the client accepts from the broker.
 *   `0` = topic aliases are not supported by this client. Range: 0..65,535 (§3.1.2.11.6).
 * @property requestResponseInformation If `true`, requests the broker to include Response Information
 *   in CONNACK, which can be used as a basis for response topics (§3.1.2.11.7).
 * @property requestProblemInformation If `true` (default), the broker may include Reason String
 *   and User Properties in packets where a reason code indicates failure (§3.1.2.11.8).
 * @property userProperties Application-defined key-value string pairs sent with the CONNECT packet.
 *   Keys may repeat. Forwarded to the broker but not to other clients.
 * @property authenticationMethod Authentication method for MQTT 5.0 enhanced authentication (§4.12),
 *   e.g. `"SCRAM-SHA-256"`. When set, enables the AUTH packet challenge/response flow.
 * @property authenticationData Initial authentication data for enhanced auth (§3.1.2.11.10).
 *   The format is defined by the [authenticationMethod].
 * @property autoReconnect If `true` (default), the client automatically attempts to reconnect
 *   with exponential backoff when the connection is lost unexpectedly. Subscriptions are
 *   re-established on successful reconnection.
 * @property reconnectBaseDelayMs Initial delay between reconnection attempts in milliseconds.
 *   Doubles after each failed attempt up to [reconnectMaxDelayMs]. Must be > 0.
 * @property reconnectMaxDelayMs Maximum delay between reconnection attempts in milliseconds.
 *   Must be ≥ [reconnectBaseDelayMs].
 * @property logger Optional [MqttLogger] implementation that receives log messages from the
 *   library. When `null` (default), no logging occurs and zero overhead is incurred.
 * @property logLevel Minimum log level for messages delivered to [logger]. Messages below
 *   this level are discarded without constructing the message string. Defaults to
 *   [MqttLogLevel.NONE] (all logging disabled).
 */
public data class MqttConfig(
    val clientId: String = "",
    val keepAliveSeconds: Int = 60,
    val cleanStart: Boolean = true,
    val username: String? = null,
    val password: ByteString? = null,
    val will: WillConfig? = null,
    val sessionExpiryInterval: Long? = null,
    val receiveMaximum: Int = 65535,
    val maximumPacketSize: Long? = null,
    val topicAliasMaximum: Int = 0,
    val requestResponseInformation: Boolean = false,
    val requestProblemInformation: Boolean = true,
    val userProperties: List<Pair<String, String>> = emptyList(),
    val authenticationMethod: String? = null,
    val authenticationData: ByteString? = null,
    val autoReconnect: Boolean = true,
    val reconnectBaseDelayMs: Long = 1000,
    val reconnectMaxDelayMs: Long = 30000,
    val logger: MqttLogger? = null,
    val logLevel: MqttLogLevel = MqttLogLevel.NONE,
) {
    init {
        require(keepAliveSeconds in 0..65_535) {
            "keepAliveSeconds must be 0..65535, got: $keepAliveSeconds"
        }
        sessionExpiryInterval?.let {
            require(it in 0..4_294_967_295L) {
                "sessionExpiryInterval must be 0..4294967295, got: $it"
            }
        }
        require(receiveMaximum in 1..65_535) {
            "receiveMaximum must be 1..65535, got: $receiveMaximum"
        }
        maximumPacketSize?.let {
            require(it in 1..4_294_967_295L) {
                "maximumPacketSize must be 1..4294967295, got: $it"
            }
        }
        require(topicAliasMaximum in 0..65_535) {
            "topicAliasMaximum must be 0..65535, got: $topicAliasMaximum"
        }
        require(reconnectBaseDelayMs > 0) {
            "reconnectBaseDelayMs must be > 0, got: $reconnectBaseDelayMs"
        }
        require(reconnectMaxDelayMs >= reconnectBaseDelayMs) {
            "reconnectMaxDelayMs must be >= reconnectBaseDelayMs ($reconnectBaseDelayMs), got: $reconnectMaxDelayMs"
        }
    }
}

/**
 * Configuration for an MQTT 5.0 Will Message (§3.1.3.2).
 *
 * The will message is published by the broker when the client disconnects unexpectedly
 * (e.g. network failure, keepalive timeout). If the client disconnects gracefully via
 * [MqttClient.disconnect], the will message is discarded unless DISCONNECT is sent with
 * reason code [org.meshtastic.mqtt.packet.ReasonCode.DISCONNECT_WITH_WILL].
 *
 * All binary data uses [ByteString] for immutability.
 *
 * @property topic Topic the will message will be published to. Must not be blank.
 * @property payload Payload of the will message as an immutable [ByteString].
 * @property qos QoS level for the will message delivery.
 * @property retain If `true`, the broker retains the will message as the last known good
 *   value for [topic], delivering it to future subscribers.
 * @property willDelayInterval Delay in seconds between the server detecting an unexpected
 *   disconnect and publishing the will message. Allows time for the client to reconnect
 *   and cancel the will. Range: 0..4,294,967,295. `null` = publish immediately (§3.1.3.2).
 * @property messageExpiryInterval Lifetime of the will message in seconds after publication.
 *   Range: 0..4,294,967,295. `null` = no expiry.
 * @property contentType MIME content type of the will payload (e.g. `"application/json"`).
 * @property responseTopic Topic for request/response pattern in the will message.
 * @property correlationData Correlation data for request/response pattern in the will message.
 * @property payloadFormatIndicator If `true`, the will payload is UTF-8 encoded text.
 * @property userProperties Application-defined key-value pairs sent with the will message.
 */
public data class WillConfig(
    val topic: String,
    val payload: ByteString = ByteString(),
    val qos: QoS = QoS.AT_MOST_ONCE,
    val retain: Boolean = false,
    val willDelayInterval: Long? = null,
    val messageExpiryInterval: Long? = null,
    val contentType: String? = null,
    val responseTopic: String? = null,
    val correlationData: ByteString? = null,
    val payloadFormatIndicator: Boolean = false,
    val userProperties: List<Pair<String, String>> = emptyList(),
) {
    init {
        require(topic.isNotBlank()) { "topic must not be blank" }
        willDelayInterval?.let {
            require(it in 0..4_294_967_295L) {
                "willDelayInterval must be 0..4294967295, got: $it"
            }
        }
        messageExpiryInterval?.let {
            require(it in 0..4_294_967_295L) {
                "messageExpiryInterval must be 0..4294967295, got: $it"
            }
        }
    }

    /** Convenience constructor accepting raw bytes (copied into an immutable [ByteString]). */
    public constructor(
        topic: String,
        payload: ByteArray,
        qos: QoS = QoS.AT_MOST_ONCE,
        retain: Boolean = false,
        willDelayInterval: Long? = null,
        messageExpiryInterval: Long? = null,
        contentType: String? = null,
        responseTopic: String? = null,
        correlationData: ByteString? = null,
        payloadFormatIndicator: Boolean = false,
        userProperties: List<Pair<String, String>> = emptyList(),
    ) : this(
        topic = topic,
        payload = ByteString(payload),
        qos = qos,
        retain = retain,
        willDelayInterval = willDelayInterval,
        messageExpiryInterval = messageExpiryInterval,
        contentType = contentType,
        responseTopic = responseTopic,
        correlationData = correlationData,
        payloadFormatIndicator = payloadFormatIndicator,
        userProperties = userProperties,
    )
}
