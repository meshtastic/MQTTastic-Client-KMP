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
 * Uses [ByteString] for the payload, guaranteeing immutability at the type level.
 */
public data class MqttMessage(
    /** The topic the message was published to. */
    val topic: String,
    /** The message payload (immutable). */
    val payload: ByteString,
    /** The QoS level of the message. */
    val qos: QoS = QoS.AT_MOST_ONCE,
    /** Whether this is a retained message. */
    val retain: Boolean = false,
    /** MQTT 5.0 publish properties. */
    val properties: PublishProperties = PublishProperties(),
) {
    /** Convenience constructor accepting raw bytes (copied into an immutable [ByteString]). */
    public constructor(
        topic: String,
        payload: ByteArray,
        qos: QoS = QoS.AT_MOST_ONCE,
        retain: Boolean = false,
        properties: PublishProperties = PublishProperties(),
    ) : this(topic, ByteString(payload), qos, retain, properties)

    override fun toString(): String = "MqttMessage(topic='$topic', payload=${payload.size}B, qos=$qos, retain=$retain)"
}

/**
 * MQTT 5.0 properties that can accompany a PUBLISH packet (§3.3.2.3).
 *
 * All fields are immutable — [ByteString] for binary data and defensive list copies.
 */
public data class PublishProperties(
    /** Lifetime of the message in seconds (0..4,294,967,295). Null means no expiry. */
    val messageExpiryInterval: Long? = null,
    /** Topic alias for header compression (1..65,535). */
    val topicAlias: Int? = null,
    /** MIME type of the payload (e.g. "application/json"). */
    val contentType: String? = null,
    /** Topic for request/response pattern. */
    val responseTopic: String? = null,
    /** Correlation data for request/response pattern (immutable). */
    val correlationData: ByteString? = null,
    /** Indicates whether the payload is UTF-8 encoded (true) or unspecified (false). */
    val payloadFormatIndicator: Boolean = false,
    /** User-defined key-value properties. */
    val userProperties: List<Pair<String, String>> = emptyList(),
    /** Subscription identifiers associated with this message (each 1..268,435,455). */
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
