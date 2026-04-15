package org.meshtastic.mqtt

/**
 * Represents a received or outgoing MQTT message with full MQTT 5.0 properties.
 *
 * Payload bytes are defensively copied on construction to ensure immutability.
 */
public class MqttMessage(
    /** The topic the message was published to. */
    public val topic: String,
    payload: ByteArray,
    /** The QoS level of the message. */
    public val qos: QoS = QoS.AT_MOST_ONCE,
    /** Whether this is a retained message. */
    public val retain: Boolean = false,
    /** MQTT 5.0 publish properties. */
    public val properties: PublishProperties = PublishProperties(),
) {
    /** The message payload bytes (immutable copy). */
    public val payload: ByteArray = payload.copyOf()

    /** Returns a copy of the payload bytes. */
    public fun payloadCopy(): ByteArray = payload.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MqttMessage) return false
        return topic == other.topic &&
            payload.contentEquals(other.payload) &&
            qos == other.qos &&
            retain == other.retain &&
            properties == other.properties
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + qos.hashCode()
        result = 31 * result + retain.hashCode()
        result = 31 * result + properties.hashCode()
        return result
    }

    override fun toString(): String = "MqttMessage(topic='$topic', payload=${payload.size}B, qos=$qos, retain=$retain)"
}

/**
 * MQTT 5.0 properties that can accompany a PUBLISH packet (§3.3.2.3).
 */
public data class PublishProperties(
    /** Lifetime of the message in seconds. Null means no expiry. */
    val messageExpiryInterval: Long? = null,
    /** Topic alias for header compression. */
    val topicAlias: Int? = null,
    /** MIME type of the payload (e.g. "application/json"). */
    val contentType: String? = null,
    /** Topic for request/response pattern. */
    val responseTopic: String? = null,
    /** Correlation data for request/response pattern. */
    val correlationData: ByteArray? = null,
    /** Indicates whether the payload is UTF-8 encoded (true) or unspecified (false). */
    val payloadFormatIndicator: Boolean = false,
    /** User-defined key-value properties. */
    val userProperties: List<Pair<String, String>> = emptyList(),
    /** Subscription identifiers associated with this message. */
    val subscriptionIdentifiers: List<Int> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PublishProperties) return false
        return messageExpiryInterval == other.messageExpiryInterval &&
            topicAlias == other.topicAlias &&
            contentType == other.contentType &&
            responseTopic == other.responseTopic &&
            correlationData.contentEqualsNullable(other.correlationData) &&
            payloadFormatIndicator == other.payloadFormatIndicator &&
            userProperties == other.userProperties &&
            subscriptionIdentifiers == other.subscriptionIdentifiers
    }

    override fun hashCode(): Int {
        var result = messageExpiryInterval?.hashCode() ?: 0
        result = 31 * result + (topicAlias ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (responseTopic?.hashCode() ?: 0)
        result = 31 * result + (correlationData?.contentHashCode() ?: 0)
        result = 31 * result + payloadFormatIndicator.hashCode()
        result = 31 * result + userProperties.hashCode()
        result = 31 * result + subscriptionIdentifiers.hashCode()
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null && other == null -> true
        this != null && other != null -> this.contentEquals(other)
        else -> false
    }
