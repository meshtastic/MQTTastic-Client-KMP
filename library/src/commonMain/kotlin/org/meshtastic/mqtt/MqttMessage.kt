package org.meshtastic.mqtt

/**
 * Represents a received MQTT message.
 */
public data class MqttMessage(
    /** The topic the message was published to. */
    val topic: String,
    /** The message payload bytes. */
    val payload: ByteArray,
    /** The QoS level of the message. */
    val qos: QoS,
    /** Whether this is a retained message. */
    val retain: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MqttMessage) return false
        return topic == other.topic &&
            payload.contentEquals(other.payload) &&
            qos == other.qos &&
            retain == other.retain
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + qos.hashCode()
        result = 31 * result + retain.hashCode()
        return result
    }
}
