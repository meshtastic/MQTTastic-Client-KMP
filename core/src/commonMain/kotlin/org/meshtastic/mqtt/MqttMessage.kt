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
 * Represents a received or outgoing MQTT message with full MQTT 5.0 properties.
 *
 * This is the primary data type flowing through [MqttClient.messages] and passed
 * to [MqttClient.publish]. Uses [ByteString] for the payload, guaranteeing
 * immutability at the type level — payloads can be safely shared across coroutines.
 *
 * ## Example
 * ```kotlin
 * // Publish a message
 * client.publish(MqttMessage(
 *     topic = "sensors/temperature",
 *     payload = ByteString("22.5".encodeToByteArray()),
 *     qos = QoS.AT_LEAST_ONCE,
 * ))
 *
 * // Receive messages
 * client.messages.collect { msg ->
 *     println("${msg.topic}: ${msg.payload}")
 * }
 * ```
 *
 * @property topic The MQTT topic this message was published to. Must not be empty.
 * @property payload The message payload as an immutable [ByteString]. May be empty.
 * @property qos The Quality of Service level controlling delivery guarantees.
 * @property retain If `true`, the broker stores this message and delivers it to future subscribers
 *   as the "last known good" value for this topic.
 * @property properties MQTT 5.0 publish properties such as message expiry, content type,
 *   response topic for request/response, and user properties.
 */
public data class MqttMessage(
    val topic: String,
    val payload: ByteString,
    val qos: QoS = QoS.AT_MOST_ONCE,
    val retain: Boolean = false,
    val properties: PublishProperties = PublishProperties(),
) {
    init {
        require(topic.isNotEmpty()) { "Topic must not be empty (§4.7)" }
    }

    /**
     * Convenience constructor accepting raw bytes.
     *
     * The [payload] is copied into an immutable [ByteString], so mutations to the
     * original array after construction have no effect on this message.
     */
    public constructor(
        topic: String,
        payload: ByteArray,
        qos: QoS = QoS.AT_MOST_ONCE,
        retain: Boolean = false,
        properties: PublishProperties = PublishProperties(),
    ) : this(topic, ByteString(payload), qos, retain, properties)

    override fun toString(): String = "MqttMessage(topic='$topic', payload=${payload.size}B, qos=$qos, retain=$retain)"

    /**
     * Returns the payload decoded as a UTF-8 string.
     *
     * Convenience accessor for the very common case of text-based MQTT payloads.
     *
     * ## Example
     * ```kotlin
     * client.messages.collect { msg ->
     *     println("${msg.topic}: ${msg.payloadAsString()}")
     * }
     * ```
     */
    public fun payloadAsString(): String = payload.toByteArray().decodeToString()
}

/**
 * MQTT 5.0 properties that can accompany a PUBLISH packet (§3.3.2.3).
 *
 * All fields are immutable — [ByteString] for binary data and defensive list copies.
 * Properties that are `null` are not present on the wire; the broker and client
 * treat absent properties as "not specified" (which is distinct from zero/empty).
 *
 * @property messageExpiryInterval Lifetime of the message in seconds. After this interval,
 *   the broker discards the message if it has not been delivered. Range: 0..4,294,967,295.
 *   `null` means the message does not expire.
 * @property topicAlias A numeric alias for the topic name, used for header compression
 *   to reduce bandwidth on repeated publishes to the same topic. Range: 1..65,535.
 * @property contentType MIME type of the payload (e.g. `"application/json"`, `"text/plain"`).
 *   Informational only — the broker does not interpret this value.
 * @property responseTopic The topic that the receiver should publish its response to,
 *   enabling the MQTT 5.0 request/response pattern (§4.10).
 * @property correlationData Opaque binary data used to correlate a response with
 *   its original request when using the request/response pattern.
 * @property payloadFormatIndicator If `true`, indicates the payload is UTF-8 encoded text.
 *   If `false` (default), the payload format is unspecified (treated as raw bytes).
 * @property userProperties Application-defined key-value string pairs. Keys may repeat.
 *   These are forwarded from the publisher to the subscriber unchanged.
 * @property subscriptionIdentifiers Identifiers of the subscriptions that caused this
 *   message to be delivered (set by the broker, not the publisher). Each value is
 *   in the range 1..268,435,455.
 */
public data class PublishProperties(
    val messageExpiryInterval: Long? = null,
    val topicAlias: Int? = null,
    val contentType: String? = null,
    val responseTopic: String? = null,
    val correlationData: ByteString? = null,
    val payloadFormatIndicator: Boolean = false,
    val userProperties: List<Pair<String, String>> = emptyList(),
    val subscriptionIdentifiers: List<Int> = emptyList(),
) {
    init {
        messageExpiryInterval?.let {
            require(it in 0..4_294_967_295L) {
                "messageExpiryInterval must be 0..4294967295, got: $it"
            }
        }
        topicAlias?.let {
            require(it in 1..65_535) { "topicAlias must be 1..65535, got: $it" }
        }
        subscriptionIdentifiers.forEach {
            require(it in 1..268_435_455) {
                "subscriptionIdentifier must be 1..268435455, got: $it"
            }
        }
    }
}
