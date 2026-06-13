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
package org.meshtastic.mqtt.packet

import org.meshtastic.mqtt.MqttProtocolVersion
import org.meshtastic.mqtt.QoS
import org.meshtastic.mqtt.ReasonCode

/**
 * Encode any [MqttPacket] subclass into its complete MQTT wire format bytes.
 *
 * The result includes the fixed header (packet type + flags + remaining length VBI)
 * followed by the variable header and payload per the OASIS MQTT specification.
 *
 * @param version The MQTT protocol version to encode for. Determines whether properties,
 *   reason codes, and 5.0-only packets are included.
 */
@Suppress("CyclomaticComplexMethod")
internal fun MqttPacket.encode(version: MqttProtocolVersion = MqttProtocolVersion.V5_0): ByteArray {
    val (flags, variableHeaderAndPayload) =
        when (this) {
            is Connect -> {
                encodeConnect(version)
            }

            is ConnAck -> {
                encodeConnAck(version)
            }

            is Publish -> {
                encodePublish(version)
            }

            is PubAck -> {
                encodePubAckLike(PacketType.PUBACK, packetIdentifier, reasonCode, properties, version)
            }

            is PubRec -> {
                encodePubAckLike(PacketType.PUBREC, packetIdentifier, reasonCode, properties, version)
            }

            is PubRel -> {
                encodePubAckLike(PacketType.PUBREL, packetIdentifier, reasonCode, properties, version)
            }

            is PubComp -> {
                encodePubAckLike(PacketType.PUBCOMP, packetIdentifier, reasonCode, properties, version)
            }

            is Subscribe -> {
                encodeSubscribe(version)
            }

            is SubAck -> {
                encodeSubAck(version)
            }

            is Unsubscribe -> {
                encodeUnsubscribe(version)
            }

            is UnsubAck -> {
                encodeUnsubAck(version)
            }

            PingReq -> {
                encodePing(PacketType.PINGREQ)
            }

            PingResp -> {
                encodePing(PacketType.PINGRESP)
            }

            is Disconnect -> {
                encodeDisconnect(version)
            }

            is Auth -> {
                require(version == MqttProtocolVersion.V5_0) {
                    "AUTH packets are not supported in MQTT 3.1.1"
                }
                encodeAuth()
            }
        }

    val fixedHeaderByte = (packetType.value shl 4) or flags
    val remainingLength = VariableByteInt.encode(variableHeaderAndPayload.size)
    return byteArrayOf(fixedHeaderByte.toByte()) + remainingLength + variableHeaderAndPayload
}

// --- §3.1 CONNECT ---

private fun Connect.encodeConnect(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()

    // Variable header
    parts += WireFormat.encodeUtf8String(protocolName)
    parts += byteArrayOf(protocolLevel.toByte())

    // Connect flags
    var connectFlags = 0
    if (cleanStart) connectFlags = connectFlags or 0x02
    if (willTopic != null) {
        connectFlags = connectFlags or 0x04
        connectFlags = connectFlags or (willQos.value shl 3)
        if (willRetain) connectFlags = connectFlags or 0x20
    }
    if (password != null) connectFlags = connectFlags or 0x40
    if (username != null) connectFlags = connectFlags or 0x80
    parts += byteArrayOf(connectFlags.toByte())

    parts += WireFormat.encodeTwoByteInt(keepAliveSeconds)
    if (version.supportsProperties) {
        parts += properties.encode()
    }

    // Payload
    parts += WireFormat.encodeUtf8String(clientId)
    if (willTopic != null) {
        if (version.supportsProperties) {
            parts += willProperties.encode()
        }
        parts += WireFormat.encodeUtf8String(willTopic)
        parts += WireFormat.encodeBinaryData(willPayload ?: byteArrayOf())
    }
    username?.let { parts += WireFormat.encodeUtf8String(it) }
    password?.let { parts += WireFormat.encodeBinaryData(it) }

    return 0b0000 to joinByteArrays(parts)
}

// --- §3.2 CONNACK ---

private fun ConnAck.encodeConnAck(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += byteArrayOf(if (sessionPresent) 0x01 else 0x00)
    if (version.supportsProperties) {
        parts += byteArrayOf(reasonCode.value.toByte())
        parts += properties.encode()
    } else {
        // MQTT 3.1.1: single return code byte (§3.2.2.3)
        parts += byteArrayOf(connAckReturnCodeFromReasonCode(reasonCode).toByte())
    }
    return 0b0000 to joinByteArrays(parts)
}

// --- §3.3 PUBLISH ---

private fun Publish.encodePublish(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    val flags =
        (if (dup) 0b1000 else 0) or
            (qos.value shl 1) or
            (if (retain) 0b0001 else 0)

    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeUtf8String(topicName)
    if (qos != QoS.AT_MOST_ONCE) {
        parts += WireFormat.encodeTwoByteInt(packetIdentifier!!)
    }
    if (version.supportsProperties) {
        parts += properties.encode()
    }
    parts += payload

    return flags to joinByteArrays(parts)
}

// --- §3.4-3.7 PUBACK, PUBREC, PUBREL, PUBCOMP ---

private fun encodePubAckLike(
    type: PacketType,
    packetId: Int,
    rc: ReasonCode,
    props: MqttProperties,
    version: MqttProtocolVersion,
): Pair<Int, ByteArray> {
    val flags = type.requiredFlags ?: 0

    // MQTT 3.1.1: only packet ID, no reason code or properties (§3.4)
    if (!version.supportsProperties) {
        return flags to WireFormat.encodeTwoByteInt(packetId)
    }

    // Short form: if reason code is SUCCESS and no properties, just packet ID
    if (rc == ReasonCode.SUCCESS && props == MqttProperties.EMPTY) {
        return flags to WireFormat.encodeTwoByteInt(packetId)
    }

    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetId)
    parts += byteArrayOf(rc.value.toByte())
    if (props != MqttProperties.EMPTY) {
        parts += props.encode()
    }

    return flags to joinByteArrays(parts)
}

// --- §3.8 SUBSCRIBE ---

private fun Subscribe.encodeSubscribe(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    if (version.supportsProperties) {
        parts += properties.encode()
    }

    for (sub in subscriptions) {
        parts += WireFormat.encodeUtf8String(sub.topicFilter)
        if (version.supportsProperties) {
            val options =
                (sub.retainHandling.value shl 4) or
                    (if (sub.retainAsPublished) 0x08 else 0) or
                    (if (sub.noLocal) 0x04 else 0) or
                    sub.maxQos.value
            parts += byteArrayOf(options.toByte())
        } else {
            // MQTT 3.1.1: subscription options byte contains only QoS (§3.8.3.1)
            parts += byteArrayOf(sub.maxQos.value.toByte())
        }
    }

    return 0b0010 to joinByteArrays(parts)
}

// --- §3.9 SUBACK ---

private fun SubAck.encodeSubAck(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    if (version.supportsProperties) {
        parts += properties.encode()
    }
    if (version.supportsProperties) {
        parts += ByteArray(reasonCodes.size) { reasonCodes[it].value.toByte() }
    } else {
        // MQTT 3.1.1 SUBACK return codes: 0x00, 0x01, 0x02 (granted QoS), 0x80 (failure)
        parts += ByteArray(reasonCodes.size) { subAckReturnCodeFromReasonCode(reasonCodes[it]).toByte() }
    }
    return 0b0000 to joinByteArrays(parts)
}

// --- §3.10 UNSUBSCRIBE ---

private fun Unsubscribe.encodeUnsubscribe(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    if (version.supportsProperties) {
        parts += properties.encode()
    }
    for (filter in topicFilters) {
        parts += WireFormat.encodeUtf8String(filter)
    }
    return 0b0010 to joinByteArrays(parts)
}

// --- §3.11 UNSUBACK ---

private fun UnsubAck.encodeUnsubAck(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    if (version.supportsProperties) {
        parts += properties.encode()
        parts += ByteArray(reasonCodes.size) { reasonCodes[it].value.toByte() }
    }
    // MQTT 3.1.1: UNSUBACK has no payload beyond packet identifier (§3.11)
    return 0b0000 to joinByteArrays(parts)
}

// --- §3.12-3.13 PINGREQ, PINGRESP ---

private fun encodePing(type: PacketType): Pair<Int, ByteArray> {
    val flags = type.requiredFlags ?: 0
    return flags to byteArrayOf()
}

// --- §3.14 DISCONNECT ---

private fun Disconnect.encodeDisconnect(version: MqttProtocolVersion): Pair<Int, ByteArray> {
    // MQTT 3.1.1: DISCONNECT has no variable header or payload (§3.14)
    if (!version.supportsProperties) {
        return 0b0000 to byteArrayOf()
    }

    // Short form: if reason code is SUCCESS and no properties, empty body
    if (reasonCode == ReasonCode.SUCCESS && properties == MqttProperties.EMPTY) {
        return 0b0000 to byteArrayOf()
    }

    val parts = mutableListOf<ByteArray>()
    parts += byteArrayOf(reasonCode.value.toByte())
    if (properties != MqttProperties.EMPTY) {
        parts += properties.encode()
    }

    return 0b0000 to joinByteArrays(parts)
}

// --- §3.15 AUTH ---

private fun Auth.encodeAuth(): Pair<Int, ByteArray> {
    // §3.15.1: Short form — if reason code is SUCCESS and no properties, Remaining Length is 0
    if (reasonCode == ReasonCode.SUCCESS && properties == MqttProperties.EMPTY) {
        return 0b0000 to byteArrayOf()
    }
    val parts = mutableListOf<ByteArray>()
    parts += byteArrayOf(reasonCode.value.toByte())
    parts += properties.encode()
    return 0b0000 to joinByteArrays(parts)
}

// --- Utility ---

private fun joinByteArrays(parts: List<ByteArray>): ByteArray {
    val totalSize = parts.sumOf { it.size }
    val result = ByteArray(totalSize)
    var pos = 0
    for (part in parts) {
        part.copyInto(result, pos)
        pos += part.size
    }
    return result
}

/** Map a [ReasonCode] to an MQTT 3.1.1 CONNACK return code byte (§3.2.2.3). */
internal fun connAckReturnCodeFromReasonCode(rc: ReasonCode): Int =
    when (rc) {
        ReasonCode.SUCCESS -> 0
        ReasonCode.UNSUPPORTED_PROTOCOL_VERSION -> 1
        ReasonCode.CLIENT_IDENTIFIER_NOT_VALID -> 2
        ReasonCode.SERVER_UNAVAILABLE -> 3
        ReasonCode.BAD_USER_NAME_OR_PASSWORD -> 4
        ReasonCode.NOT_AUTHORIZED -> 5
        else -> 5 // Map unknown failures to "Not authorized" as a safe fallback
    }

/** Map a [ReasonCode] to an MQTT 3.1.1 SUBACK return code byte (§3.9.3). */
internal fun subAckReturnCodeFromReasonCode(rc: ReasonCode): Int =
    when (rc) {
        ReasonCode.SUCCESS -> 0x00
        ReasonCode.GRANTED_QOS_1 -> 0x01
        ReasonCode.GRANTED_QOS_2 -> 0x02
        else -> 0x80 // Failure
    }
