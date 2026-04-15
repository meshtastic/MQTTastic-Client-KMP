package org.meshtastic.mqtt.packet

/**
 * MQTT 5.0 packet type identifiers per §2.1.2.
 */
internal enum class PacketType(
    val value: Int,
) {
    CONNECT(1),
    CONNACK(2),
    PUBLISH(3),
    PUBACK(4),
    PUBREC(5),
    PUBREL(6),
    PUBCOMP(7),
    SUBSCRIBE(8),
    SUBACK(9),
    UNSUBSCRIBE(10),
    UNSUBACK(11),
    PINGREQ(12),
    PINGRESP(13),
    DISCONNECT(14),
    AUTH(15),
    ;

    companion object {
        fun fromValue(value: Int): PacketType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown packet type: $value")
    }
}
