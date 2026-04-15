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
 * All binary data uses [ByteString] for immutability.
 */
public data class MqttConfig(
    /** Client identifier. Empty string lets the broker assign one. */
    val clientId: String = "",
    /** Keep alive interval in seconds (0 = disabled). Per §3.1.2.10. */
    val keepAliveSeconds: Int = 60,
    /** If true, start a new session and discard any existing one. Per §3.1.2.4. */
    val cleanStart: Boolean = true,
    /** Optional username for authentication. */
    val username: String? = null,
    /** Optional password for authentication (immutable). */
    val password: ByteString? = null,
    /** Optional will message configuration. */
    val will: WillConfig? = null,
    /** Session expiry interval in seconds. 0 = expire on disconnect, null = use broker default. Per §3.1.2.11.3. */
    val sessionExpiryInterval: Long? = null,
    /** Maximum number of in-flight QoS 1/2 messages the client will accept. Per §3.1.2.11.4. */
    val receiveMaximum: Int = 65535,
    /** Maximum packet size the client can accept. Null = no limit. Per §3.1.2.11.5. */
    val maximumPacketSize: Long? = null,
    /** Maximum topic alias value. 0 = no topic aliases. Per §3.1.2.11.6. */
    val topicAliasMaximum: Int = 0,
    /** Request the server to return Response Information. Per §3.1.2.11.7. */
    val requestResponseInformation: Boolean = false,
    /** Request the server to return Reason String and User Properties on failures. Per §3.1.2.11.8. */
    val requestProblemInformation: Boolean = true,
    /** User-defined key-value properties to send with CONNECT. */
    val userProperties: List<Pair<String, String>> = emptyList(),
    /** Authentication method for enhanced auth (e.g. "SCRAM-SHA-256"). Per §3.1.2.11.9. */
    val authenticationMethod: String? = null,
    /** Authentication data for enhanced auth. Per §3.1.2.11.10. */
    val authenticationData: ByteString? = null,
    /** Enable automatic reconnection with exponential backoff. */
    val autoReconnect: Boolean = true,
    /** Base delay for reconnection backoff in milliseconds. */
    val reconnectBaseDelayMs: Long = 1000,
    /** Maximum delay for reconnection backoff in milliseconds. */
    val reconnectMaxDelayMs: Long = 30000,
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
 * The will message is published by the broker if the client disconnects unexpectedly.
 * All binary data uses [ByteString] for immutability.
 */
public data class WillConfig(
    /** Topic for the will message. */
    val topic: String,
    /** Payload for the will message (immutable). */
    val payload: ByteString = ByteString(),
    /** QoS level for the will message. */
    val qos: QoS = QoS.AT_MOST_ONCE,
    /** Whether the will message should be retained. */
    val retain: Boolean = false,
    /** Delay before publishing the will in seconds. Per §3.1.3.2. */
    val willDelayInterval: Long? = null,
    /** Message expiry interval for the will in seconds. */
    val messageExpiryInterval: Long? = null,
    /** MIME content type of the will payload. */
    val contentType: String? = null,
    /** Response topic for the will message. */
    val responseTopic: String? = null,
    /** Correlation data for the will message (immutable). */
    val correlationData: ByteString? = null,
    /** Whether the will payload is UTF-8 encoded. */
    val payloadFormatIndicator: Boolean = false,
    /** User properties for the will message. */
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
