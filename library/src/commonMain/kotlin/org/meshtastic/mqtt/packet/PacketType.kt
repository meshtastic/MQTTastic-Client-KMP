package org.meshtastic.mqtt.packet

/**
 * MQTT 5.0 packet type identifiers and required fixed-header flags per §2.1.2–§2.1.3.
 *
 * @property value Packet type code (upper 4 bits of fixed header byte 1).
 * @property requiredFlags Required lower 4 bits of fixed header byte 1. Null means flags are variable (PUBLISH).
 */
internal enum class PacketType(
    val value: Int,
    val requiredFlags: Int?,
) {
    CONNECT(1, 0b0000),
    CONNACK(2, 0b0000),
    PUBLISH(3, null), // Flags are DUP/QoS/Retain — variable per packet
    PUBACK(4, 0b0000),
    PUBREC(5, 0b0000),
    PUBREL(6, 0b0010),
    PUBCOMP(7, 0b0000),
    SUBSCRIBE(8, 0b0010),
    SUBACK(9, 0b0000),
    UNSUBSCRIBE(10, 0b0010),
    UNSUBACK(11, 0b0000),
    PINGREQ(12, 0b0000),
    PINGRESP(13, 0b0000),
    DISCONNECT(14, 0b0000),
    AUTH(15, 0b0000),
    ;

    companion object {
        fun fromValue(value: Int): PacketType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown packet type: $value")
    }
}
