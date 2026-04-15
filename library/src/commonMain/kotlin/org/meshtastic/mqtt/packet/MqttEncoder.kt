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

import org.meshtastic.mqtt.QoS

/**
 * Encode any [MqttPacket] subclass into its complete MQTT 5.0 wire format bytes.
 *
 * The result includes the fixed header (packet type + flags + remaining length VBI)
 * followed by the variable header and payload per the OASIS MQTT 5.0 specification.
 */
@Suppress("CyclomaticComplexMethod")
internal fun MqttPacket.encode(): ByteArray {
    val (flags, variableHeaderAndPayload) =
        when (this) {
            is Connect -> encodeConnect()
            is ConnAck -> encodeConnAck()
            is Publish -> encodePublish()
            is PubAck -> encodePubAckLike(PacketType.PUBACK, packetIdentifier, reasonCode, properties)
            is PubRec -> encodePubAckLike(PacketType.PUBREC, packetIdentifier, reasonCode, properties)
            is PubRel -> encodePubAckLike(PacketType.PUBREL, packetIdentifier, reasonCode, properties)
            is PubComp -> encodePubAckLike(PacketType.PUBCOMP, packetIdentifier, reasonCode, properties)
            is Subscribe -> encodeSubscribe()
            is SubAck -> encodeSubAck()
            is Unsubscribe -> encodeUnsubscribe()
            is UnsubAck -> encodeUnsubAck()
            PingReq -> encodePing(PacketType.PINGREQ)
            PingResp -> encodePing(PacketType.PINGRESP)
            is Disconnect -> encodeDisconnect()
            is Auth -> encodeAuth()
        }

    val fixedHeaderByte = (packetType.value shl 4) or flags
    val remainingLength = VariableByteInt.encode(variableHeaderAndPayload.size)
    return byteArrayOf(fixedHeaderByte.toByte()) + remainingLength + variableHeaderAndPayload
}

// --- §3.1 CONNECT ---

private fun Connect.encodeConnect(): Pair<Int, ByteArray> {
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
    parts += properties.encode()

    // Payload
    parts += WireFormat.encodeUtf8String(clientId)
    if (willTopic != null) {
        parts += willProperties.encode()
        parts += WireFormat.encodeUtf8String(willTopic)
        parts += WireFormat.encodeBinaryData(willPayload ?: byteArrayOf())
    }
    username?.let { parts += WireFormat.encodeUtf8String(it) }
    password?.let { parts += WireFormat.encodeBinaryData(it) }

    return 0b0000 to joinByteArrays(parts)
}

// --- §3.2 CONNACK ---

private fun ConnAck.encodeConnAck(): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += byteArrayOf(if (sessionPresent) 0x01 else 0x00)
    parts += byteArrayOf(reasonCode.value.toByte())
    parts += properties.encode()
    return 0b0000 to joinByteArrays(parts)
}

// --- §3.3 PUBLISH ---

private fun Publish.encodePublish(): Pair<Int, ByteArray> {
    val flags =
        (if (dup) 0b1000 else 0) or
            (qos.value shl 1) or
            (if (retain) 0b0001 else 0)

    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeUtf8String(topicName)
    if (qos != QoS.AT_MOST_ONCE) {
        parts += WireFormat.encodeTwoByteInt(packetIdentifier!!)
    }
    parts += properties.encode()
    parts += payload

    return flags to joinByteArrays(parts)
}

// --- §3.4-3.7 PUBACK, PUBREC, PUBREL, PUBCOMP ---

private fun encodePubAckLike(
    type: PacketType,
    packetId: Int,
    rc: ReasonCode,
    props: MqttProperties,
): Pair<Int, ByteArray> {
    val flags = type.requiredFlags ?: 0

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

private fun Subscribe.encodeSubscribe(): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    parts += properties.encode()

    for (sub in subscriptions) {
        parts += WireFormat.encodeUtf8String(sub.topicFilter)
        val options =
            (sub.retainHandling.value shl 4) or
                (if (sub.retainAsPublished) 0x08 else 0) or
                (if (sub.noLocal) 0x04 else 0) or
                sub.maxQos.value
        parts += byteArrayOf(options.toByte())
    }

    return 0b0010 to joinByteArrays(parts)
}

// --- §3.9 SUBACK ---

private fun SubAck.encodeSubAck(): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    parts += properties.encode()
    parts += ByteArray(reasonCodes.size) { reasonCodes[it].value.toByte() }
    return 0b0000 to joinByteArrays(parts)
}

// --- §3.10 UNSUBSCRIBE ---

private fun Unsubscribe.encodeUnsubscribe(): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    parts += properties.encode()
    for (filter in topicFilters) {
        parts += WireFormat.encodeUtf8String(filter)
    }
    return 0b0010 to joinByteArrays(parts)
}

// --- §3.11 UNSUBACK ---

private fun UnsubAck.encodeUnsubAck(): Pair<Int, ByteArray> {
    val parts = mutableListOf<ByteArray>()
    parts += WireFormat.encodeTwoByteInt(packetIdentifier)
    parts += properties.encode()
    parts += ByteArray(reasonCodes.size) { reasonCodes[it].value.toByte() }
    return 0b0000 to joinByteArrays(parts)
}

// --- §3.12-3.13 PINGREQ, PINGRESP ---

private fun encodePing(type: PacketType): Pair<Int, ByteArray> {
    val flags = type.requiredFlags ?: 0
    return flags to byteArrayOf()
}

// --- §3.14 DISCONNECT ---

private fun Disconnect.encodeDisconnect(): Pair<Int, ByteArray> {
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
