package org.meshtastic.mqtt

/**
 * Quality of Service levels for MQTT message delivery.
 */
public enum class QoS(
    public val value: Int,
) {
    /** At most once delivery — fire and forget. */
    AT_MOST_ONCE(0),

    /** At least once delivery — acknowledged delivery. */
    AT_LEAST_ONCE(1),

    /** Exactly once delivery — assured delivery via 4-step handshake. */
    EXACTLY_ONCE(2),
    ;

    public companion object {
        public fun fromValue(value: Int): QoS =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Invalid QoS value: $value")
    }
}
