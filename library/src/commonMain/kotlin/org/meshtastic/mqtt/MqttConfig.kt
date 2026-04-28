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
 *     defaultQos = QoS.AT_LEAST_ONCE,
 * )
 * ```
 *
 * Or using the builder DSL:
 *
 * ```kotlin
 * val config = MqttConfig.build {
 *     clientId = "sensor-hub-01"
 *     keepAliveSeconds = 30
 *     defaultQos = QoS.AT_LEAST_ONCE
 *     will {
 *         topic = "sensors/status"
 *         payload("offline")
 *         retain = true
 *     }
 * }
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
 *   **Note:** Client-side session persistence is not yet implemented. When `cleanStart=false`,
 *   the broker will resume session state but the client does not persist in-flight QoS 1/2
 *   messages across reconnects. Packet IDs are preserved, but unacknowledged messages may be
 *   lost. Full session persistence will be added in a future release.
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
 * @property maxReconnectAttempts Maximum number of consecutive reconnection attempts before
 *   giving up. `0` = unlimited attempts. Defaults to `0` (unlimited).
 * @property defaultQos Default [QoS] level applied to convenience [MqttClient.publish] calls
 *   when no explicit QoS is specified. Similar to Ktor's `defaultRequest {}` pattern.
 *   Defaults to [QoS.AT_MOST_ONCE].
 * @property defaultRetain Default retain flag applied to convenience [MqttClient.publish] calls
 *   when no explicit retain value is specified. Defaults to `false`.
 * @property logger Optional [MqttLogger] implementation that receives log messages from the
 *   library. When `null` (default), no logging occurs and zero overhead is incurred.
 * @property logLevel Minimum log level for messages delivered to [logger]. Messages below
 *   this level are discarded without constructing the message string. Defaults to
 *   [MqttLogLevel.NONE] (all logging disabled).
 */
public data class MqttConfig(
    val protocolVersion: MqttProtocolVersion = MqttProtocolVersion.V5_0,
    val negotiateVersion: Boolean = true,
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
    val maxReconnectAttempts: Int = 0,
    val defaultQos: QoS = QoS.AT_MOST_ONCE,
    val defaultRetain: Boolean = false,
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
        require(maxReconnectAttempts >= 0) {
            "maxReconnectAttempts must be >= 0, got: $maxReconnectAttempts"
        }
        // MQTT 3.1.1 does not support these features — fail fast if explicitly configured
        if (protocolVersion == MqttProtocolVersion.V3_1_1) {
            validateV311Compatibility()
        }
    }

    /**
     * Check whether this config is compatible with MQTT 3.1.1.
     *
     * Called eagerly when [protocolVersion] is [MqttProtocolVersion.V3_1_1], and
     * at connection time when [negotiateVersion] triggers a fallback from 5.0 to 3.1.1.
     *
     * @throws IllegalArgumentException if any 5.0-only options are configured.
     */
    internal fun validateV311Compatibility() {
        require(sessionExpiryInterval == null) {
            "sessionExpiryInterval is not supported in MQTT 3.1.1"
        }
        require(authenticationMethod == null) {
            "Enhanced authentication (authenticationMethod) is not supported in MQTT 3.1.1"
        }
        require(authenticationData == null) {
            "Enhanced authentication (authenticationData) is not supported in MQTT 3.1.1"
        }
        // §3.1.2.3: In 3.1.1, password flag requires username flag
        if (password != null) {
            require(username != null) {
                "MQTT 3.1.1 requires username when password is set (§3.1.2.3)"
            }
        }
    }

    public companion object {
        /**
         * Build an [MqttConfig] using a lambda with named property assignments.
         *
         * ## Example
         * ```kotlin
         * val config = MqttConfig.build {
         *     clientId = "sensor-hub-01"
         *     keepAliveSeconds = 30
         *     defaultQos = QoS.AT_LEAST_ONCE
         *     autoReconnect = true
         *     logger = MqttLogger.println()
         *     logLevel = MqttLogLevel.DEBUG
         *     will {
         *         topic = "sensors/status"
         *         payload("offline")
         *         retain = true
         *     }
         * }
         * ```
         */
        public fun build(block: Builder.() -> Unit): MqttConfig = Builder().apply(block).build()
    }

    /**
     * Mutable builder for [MqttConfig].
     *
     * All properties default to the same values as the [MqttConfig] data class constructor.
     * Set only the properties you want to customize.
     */
    @MqttDsl
    @Suppress("TooManyFunctions")
    public class Builder {
        /** MQTT protocol version for this connection. Defaults to MQTT 5.0. */
        public var protocolVersion: MqttProtocolVersion = MqttProtocolVersion.V5_0

        /**
         * If `true` (default) and [protocolVersion] is [MqttProtocolVersion.V5_0], the client
         * automatically falls back to MQTT 3.1.1 when the broker rejects the 5.0 CONNECT with
         * `UNSUPPORTED_PROTOCOL_VERSION`. The negotiated version is remembered for reconnects.
         * Set to `false` to require the exact [protocolVersion] without fallback.
         */
        public var negotiateVersion: Boolean = true

        /** Client identifier sent to the broker. Empty requests broker-assigned ID. */
        public var clientId: String = ""

        /** Maximum interval in seconds between control packets (§3.1.2.10). Range: 0..65,535. */
        public var keepAliveSeconds: Int = 60

        /** If `true`, the broker discards any existing session state (§3.1.2.4). */
        public var cleanStart: Boolean = true

        /** Optional username for password-based authentication (§3.1.3.5). */
        public var username: String? = null

        /** Optional password as an immutable [ByteString] (§3.1.3.6). */
        public var password: ByteString? = null

        /** Optional will message published on unexpected disconnect (§3.1.3.2). */
        public var will: WillConfig? = null

        /** Session state retention duration in seconds after disconnect (§3.1.2.11.3). */
        public var sessionExpiryInterval: Long? = null

        /** Maximum concurrent in-flight QoS 1/2 messages. Range: 1..65,535 (§3.1.2.11.4). */
        public var receiveMaximum: Int = 65535

        /** Maximum MQTT packet size in bytes the client accepts (§3.1.2.11.5). */
        public var maximumPacketSize: Long? = null

        /** Maximum topic aliases the client accepts from the broker (§3.1.2.11.6). */
        public var topicAliasMaximum: Int = 0

        /** If `true`, requests Response Information in CONNACK (§3.1.2.11.7). */
        public var requestResponseInformation: Boolean = false

        /** If `true`, allows Reason String and User Properties on failure packets (§3.1.2.11.8). */
        public var requestProblemInformation: Boolean = true

        /** Application-defined key-value pairs sent with the CONNECT packet. */
        public var userProperties: List<Pair<String, String>> = emptyList()

        /** Authentication method for enhanced authentication (§4.12). */
        public var authenticationMethod: String? = null

        /** Initial authentication data for enhanced auth (§3.1.2.11.10). */
        public var authenticationData: ByteString? = null

        /** If `true`, automatically reconnect with exponential backoff on connection loss. */
        public var autoReconnect: Boolean = true

        /** Initial delay in milliseconds between reconnection attempts. Must be > 0. */
        public var reconnectBaseDelayMs: Long = 1000

        /** Maximum delay in milliseconds between reconnection attempts. */
        public var reconnectMaxDelayMs: Long = 30000

        /** Maximum consecutive reconnect attempts. `0` = unlimited. */
        public var maxReconnectAttempts: Int = 0

        /** Default [QoS] level for convenience [MqttClient.publish] calls. */
        public var defaultQos: QoS = QoS.AT_MOST_ONCE

        /** Default retain flag for convenience [MqttClient.publish] calls. */
        public var defaultRetain: Boolean = false

        /** Optional [MqttLogger] for receiving library log output. */
        public var logger: MqttLogger? = null

        /** Minimum log level for messages delivered to [logger]. */
        public var logLevel: MqttLogLevel = MqttLogLevel.NONE

        /**
         * Set the password from a plain [String].
         *
         * Convenience alternative to assigning [password] as a [ByteString] directly.
         *
         * ```kotlin
         * MqttClient("sensor") {
         *     username = "meshdev"
         *     password("large4cats")
         * }
         * ```
         *
         * @param value The password string, encoded as UTF-8 bytes.
         */
        public fun password(value: String) {
            password = ByteString(value.encodeToByteArray())
        }

        /**
         * Configure a will message using a DSL block.
         *
         * The broker publishes this message when the client disconnects unexpectedly.
         * This is the Ktor-style alternative to assigning [will] directly.
         *
         * ```kotlin
         * MqttClient("sensor") {
         *     will {
         *         topic = "sensors/status"
         *         payload("offline")
         *         qos = QoS.AT_LEAST_ONCE
         *         retain = true
         *     }
         * }
         * ```
         *
         * @param block Configuration block applied to a [WillConfig.Builder].
         */
        public fun will(block: WillConfig.Builder.() -> Unit) {
            will = WillConfig.Builder().apply(block).build()
        }

        /** Build the immutable [MqttConfig] from the current builder state. */
        public fun build(): MqttConfig =
            MqttConfig(
                protocolVersion = protocolVersion,
                negotiateVersion = negotiateVersion,
                clientId = clientId,
                keepAliveSeconds = keepAliveSeconds,
                cleanStart = cleanStart,
                username = username,
                password = password,
                will = will,
                sessionExpiryInterval = sessionExpiryInterval,
                receiveMaximum = receiveMaximum,
                maximumPacketSize = maximumPacketSize,
                topicAliasMaximum = topicAliasMaximum,
                requestResponseInformation = requestResponseInformation,
                requestProblemInformation = requestProblemInformation,
                userProperties = userProperties,
                authenticationMethod = authenticationMethod,
                authenticationData = authenticationData,
                autoReconnect = autoReconnect,
                reconnectBaseDelayMs = reconnectBaseDelayMs,
                reconnectMaxDelayMs = reconnectMaxDelayMs,
                maxReconnectAttempts = maxReconnectAttempts,
                defaultQos = defaultQos,
                defaultRetain = defaultRetain,
                logger = logger,
                logLevel = logLevel,
            )
    }
}

/**
 * Configuration for an MQTT 5.0 Will Message (§3.1.3.2).
 *
 * The will message is published by the broker when the client disconnects unexpectedly
 * (e.g. network failure, keepalive timeout). If the client disconnects gracefully via
 * [MqttClient.disconnect], the will message is discarded unless DISCONNECT is sent with
 * reason code [org.meshtastic.mqtt.ReasonCode.DISCONNECT_WITH_WILL].
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
        require(topic.isNotBlank()) { "Will topic must not be blank" }
        TopicValidator.validateTopicName(topic)
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

    public companion object {
        /**
         * Build a [WillConfig] using a DSL block.
         *
         * ```kotlin
         * val will = WillConfig.build {
         *     topic = "sensors/status"
         *     payload("offline")
         *     qos = QoS.AT_LEAST_ONCE
         *     retain = true
         * }
         * ```
         */
        public fun build(block: Builder.() -> Unit): WillConfig = Builder().apply(block).build()
    }

    /**
     * Mutable builder for [WillConfig].
     *
     * Provides setter properties for all will fields plus convenience methods
     * for setting the payload from different types.
     *
     * @see WillConfig.build
     * @see MqttConfig.Builder.will
     */
    @MqttDsl
    public class Builder {
        /** Topic the will message will be published to. Must not be blank. */
        public var topic: String = ""

        /** Payload as an immutable [ByteString]. Use [payload] methods for string/ByteArray convenience. */
        public var payloadBytes: ByteString = ByteString()

        /** QoS level for the will message delivery. */
        public var qos: QoS = QoS.AT_MOST_ONCE

        /** If `true`, the broker retains the will message. */
        public var retain: Boolean = false

        /** Delay in seconds before publishing the will after unexpected disconnect. */
        public var willDelayInterval: Long? = null

        /** Lifetime of the will message in seconds after publication. */
        public var messageExpiryInterval: Long? = null

        /** MIME content type of the will payload (e.g. `"application/json"`). */
        public var contentType: String? = null

        /** Topic for request/response pattern in the will message. */
        public var responseTopic: String? = null

        /** Correlation data for request/response pattern in the will message. */
        public var correlationData: ByteString? = null

        /** If `true`, the will payload is UTF-8 encoded text. */
        public var payloadFormatIndicator: Boolean = false

        /** Application-defined key-value pairs sent with the will message. */
        public var userProperties: List<Pair<String, String>> = emptyList()

        /** Set the payload from a UTF-8 string. */
        public fun payload(text: String) {
            payloadBytes = ByteString(text.encodeToByteArray())
            payloadFormatIndicator = true
        }

        /** Set the payload from raw bytes (copied into an immutable [ByteString]). */
        public fun payload(bytes: ByteArray) {
            payloadBytes = ByteString(bytes)
        }

        /** Set the payload from an immutable [ByteString]. */
        public fun payload(byteString: ByteString) {
            payloadBytes = byteString
        }

        /** Build the immutable [WillConfig] from the current builder state. */
        public fun build(): WillConfig =
            WillConfig(
                topic = topic,
                payload = payloadBytes,
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
}
