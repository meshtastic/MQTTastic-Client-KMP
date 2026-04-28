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
import org.meshtastic.mqtt.RetainHandling

/**
 * Decode a complete MQTT packet (including fixed header) into the appropriate [MqttPacket] subclass.
 *
 * @param bytes The complete packet bytes starting with the fixed header.
 * @param version The MQTT protocol version to decode as. Determines whether properties
 *   and reason codes are expected in the packet body.
 * @throws IllegalArgumentException if the packet is malformed or unrecognized.
 */
@Suppress("CyclomaticComplexMethod")
internal fun decodePacket(
    bytes: ByteArray,
    version: MqttProtocolVersion = MqttProtocolVersion.V5_0,
): MqttPacket {
    require(bytes.isNotEmpty()) { "Packet bytes must not be empty" }

    val firstByte = bytes[0].toInt() and 0xFF
    val typeValue = firstByte ushr 4
    val flags = firstByte and 0x0F

    val type = PacketType.fromValue(typeValue)

    // MQTT 3.1.1 does not have AUTH packets
    if (!version.supportsProperties && type == PacketType.AUTH) {
        throw IllegalArgumentException("AUTH packets are not valid in MQTT 3.1.1")
    }

    // Validate flags (PUBLISH has variable flags)
    type.requiredFlags?.let { required ->
        require(flags == required) {
            "Invalid flags for ${type.name}: expected 0x${required.toString(16)}, got 0x${flags.toString(16)}"
        }
    }

    val vbiResult = VariableByteInt.decode(bytes, 1)
    val remainingLength = vbiResult.value
    val headerSize = 1 + vbiResult.bytesConsumed

    require(bytes.size >= headerSize + remainingLength) {
        "Packet too short: expected ${headerSize + remainingLength} bytes, got ${bytes.size}"
    }

    val body = bytes.copyOfRange(headerSize, headerSize + remainingLength)

    return when (type) {
        PacketType.CONNECT -> decodeConnect(body, version)
        PacketType.CONNACK -> decodeConnAck(body, version)
        PacketType.PUBLISH -> decodePublish(body, flags, version)
        PacketType.PUBACK -> decodePubAckLike(body, version, ::PubAck)
        PacketType.PUBREC -> decodePubAckLike(body, version, ::PubRec)
        PacketType.PUBREL -> decodePubAckLike(body, version, ::PubRel)
        PacketType.PUBCOMP -> decodePubAckLike(body, version, ::PubComp)
        PacketType.SUBSCRIBE -> decodeSubscribe(body, version)
        PacketType.SUBACK -> decodeSubAck(body, version)
        PacketType.UNSUBSCRIBE -> decodeUnsubscribe(body, version)
        PacketType.UNSUBACK -> decodeUnsubAck(body, version)
        PacketType.PINGREQ -> PingReq
        PacketType.PINGRESP -> PingResp
        PacketType.DISCONNECT -> decodeDisconnect(body, version)
        PacketType.AUTH -> decodeAuth(body)
    }
}

// --- §3.1 CONNECT ---

@Suppress("CyclomaticComplexMethod")
private fun decodeConnect(
    body: ByteArray,
    version: MqttProtocolVersion,
): Connect {
    var pos = 0

    // Protocol Name
    val (protocolName, nameConsumed) = WireFormat.decodeUtf8String(body, pos)
    pos += nameConsumed

    // Protocol Level
    val protocolLevel = body[pos].toInt() and 0xFF
    pos++

    // Connect Flags — bit 0 is reserved and MUST be 0 (§3.1.2.3)
    val connectFlags = body[pos].toInt() and 0xFF
    require((connectFlags and 0x01) == 0) { "Bit 0 of Connect Flags must be 0 (§3.1.2.3)" }
    pos++
    val cleanStart = (connectFlags and 0x02) != 0
    val willFlag = (connectFlags and 0x04) != 0
    val willQos = QoS.fromValue((connectFlags shr 3) and 0x03)
    val willRetain = (connectFlags and 0x20) != 0
    val hasPassword = (connectFlags and 0x40) != 0
    val hasUsername = (connectFlags and 0x80) != 0

    // Keep Alive
    val keepAlive = WireFormat.decodeTwoByteInt(body, pos)
    pos += 2

    // Properties (MQTT 5.0 only)
    val properties =
        if (version.supportsProperties) {
            val (props, propsConsumed) = decodePropertiesSection(body, pos)
            pos += propsConsumed
            props
        } else {
            MqttProperties.EMPTY
        }

    // Payload: Client ID
    val (clientId, clientIdConsumed) = WireFormat.decodeUtf8String(body, pos)
    pos += clientIdConsumed

    // Will
    var willTopic: String? = null
    var willPayload: ByteArray? = null
    var willProperties = MqttProperties.EMPTY
    if (willFlag) {
        if (version.supportsProperties) {
            val (wp, wpConsumed) = decodePropertiesSection(body, pos)
            willProperties = wp
            pos += wpConsumed
        }

        val (wt, wtConsumed) = WireFormat.decodeUtf8String(body, pos)
        willTopic = wt
        pos += wtConsumed

        val (wpData, wpDataConsumed) = WireFormat.decodeBinaryData(body, pos)
        willPayload = wpData
        pos += wpDataConsumed
    }

    // Username
    var username: String? = null
    if (hasUsername) {
        val (u, uConsumed) = WireFormat.decodeUtf8String(body, pos)
        username = u
        pos += uConsumed
    }

    // Password
    var password: ByteArray? = null
    if (hasPassword) {
        val (p, pConsumed) = WireFormat.decodeBinaryData(body, pos)
        password = p
        pos += pConsumed
    }

    return Connect(
        protocolName = protocolName,
        protocolLevel = protocolLevel,
        cleanStart = cleanStart,
        keepAliveSeconds = keepAlive,
        clientId = clientId,
        willTopic = willTopic,
        willPayload = willPayload,
        willQos = willQos,
        willRetain = willRetain,
        willProperties = willProperties,
        username = username,
        password = password,
        properties = properties,
    )
}

// --- §3.2 CONNACK ---

private fun decodeConnAck(
    body: ByteArray,
    version: MqttProtocolVersion,
): ConnAck {
    if (version.supportsProperties) {
        require(body.size >= 3) { "CONNACK body too short" }
    } else {
        require(body.size >= 2) { "CONNACK body too short" }
    }
    // Connect Acknowledge Flags — bits 7-1 are reserved and MUST be 0 (§3.2.2.1)
    require((body[0].toInt() and 0xFE) == 0) {
        "Reserved bits in Connect Acknowledge Flags must be 0 (§3.2.2.1)"
    }
    val sessionPresent = (body[0].toInt() and 0x01) != 0

    return if (version.supportsProperties) {
        val reasonCode = ReasonCode.fromValue(body[1].toInt() and 0xFF)
        val (properties, _) = decodePropertiesSection(body, 2)
        ConnAck(sessionPresent = sessionPresent, reasonCode = reasonCode, properties = properties)
    } else {
        // MQTT 3.1.1: single return code byte, no properties (§3.2.2.3)
        val returnCode = body[1].toInt() and 0xFF
        val reasonCode = reasonCodeFromConnAckReturnCode(returnCode)
        ConnAck(sessionPresent = sessionPresent, reasonCode = reasonCode)
    }
}

// --- §3.3 PUBLISH ---

private fun decodePublish(
    body: ByteArray,
    flags: Int,
    version: MqttProtocolVersion,
): Publish {
    val dup = (flags and 0x08) != 0
    val qos = QoS.fromValue((flags shr 1) and 0x03)
    val retain = (flags and 0x01) != 0

    var pos = 0
    val (topicName, topicConsumed) = WireFormat.decodeUtf8String(body, pos)
    pos += topicConsumed

    var packetIdentifier: Int? = null
    if (qos != QoS.AT_MOST_ONCE) {
        packetIdentifier = WireFormat.decodeTwoByteInt(body, pos)
        pos += 2
    }

    val properties: MqttProperties
    if (version.supportsProperties) {
        val (props, propsConsumed) = decodePropertiesSection(body, pos)
        properties = props
        pos += propsConsumed
    } else {
        properties = MqttProperties.EMPTY
    }

    val payload = body.copyOfRange(pos, body.size)

    return Publish(
        dup = dup,
        qos = qos,
        retain = retain,
        topicName = topicName,
        packetIdentifier = packetIdentifier,
        payload = payload,
        properties = properties,
    )
}

// --- §3.4-3.7 PUBACK, PUBREC, PUBREL, PUBCOMP ---

private fun <T : MqttPacket> decodePubAckLike(
    body: ByteArray,
    version: MqttProtocolVersion,
    factory: (Int, ReasonCode, MqttProperties) -> T,
): T {
    require(body.size >= 2) { "Packet body too short for packet identifier" }
    val packetId = WireFormat.decodeTwoByteInt(body, 0)

    // MQTT 3.1.1: only packet identifier, no reason code or properties
    if (!version.supportsProperties) {
        return factory(packetId, ReasonCode.SUCCESS, MqttProperties.EMPTY)
    }

    // Short form: remaining length == 2 → SUCCESS with no properties
    if (body.size == 2) {
        return factory(packetId, ReasonCode.SUCCESS, MqttProperties.EMPTY)
    }

    val reasonCode = ReasonCode.fromValue(body[2].toInt() and 0xFF)

    // Remaining length == 3 → reason code but no properties
    if (body.size == 3) {
        return factory(packetId, reasonCode, MqttProperties.EMPTY)
    }

    val (properties, _) = decodePropertiesSection(body, 3)
    return factory(packetId, reasonCode, properties)
}

// --- §3.8 SUBSCRIBE ---

private fun decodeSubscribe(
    body: ByteArray,
    version: MqttProtocolVersion,
): Subscribe {
    var pos = 0

    val packetId = WireFormat.decodeTwoByteInt(body, pos)
    pos += 2

    val properties: MqttProperties
    if (version.supportsProperties) {
        val (props, propsConsumed) = decodePropertiesSection(body, pos)
        properties = props
        pos += propsConsumed
    } else {
        properties = MqttProperties.EMPTY
    }

    val subscriptions = mutableListOf<Subscription>()
    while (pos < body.size) {
        val (topicFilter, topicConsumed) = WireFormat.decodeUtf8String(body, pos)
        pos += topicConsumed

        val options = body[pos].toInt() and 0xFF
        pos++

        if (version.supportsProperties) {
            // Subscription Options bits 6-7 are reserved and MUST be 0 (§3.8.3.1)
            require((options and 0xC0) == 0) {
                "Reserved bits 6-7 in Subscription Options must be 0 (§3.8.3.1)"
            }

            subscriptions +=
                Subscription(
                    topicFilter = topicFilter,
                    maxQos = QoS.fromValue(options and 0x03),
                    noLocal = (options and 0x04) != 0,
                    retainAsPublished = (options and 0x08) != 0,
                    retainHandling = RetainHandling.fromValue((options shr 4) and 0x03),
                )
        } else {
            // MQTT 3.1.1: options byte is just QoS (bits 0-1), rest reserved as 0
            subscriptions +=
                Subscription(
                    topicFilter = topicFilter,
                    maxQos = QoS.fromValue(options and 0x03),
                )
        }
    }

    return Subscribe(
        packetIdentifier = packetId,
        subscriptions = subscriptions,
        properties = properties,
    )
}

// --- §3.9 SUBACK ---

private fun decodeSubAck(
    body: ByteArray,
    version: MqttProtocolVersion,
): SubAck {
    var pos = 0

    val packetId = WireFormat.decodeTwoByteInt(body, pos)
    pos += 2

    val properties: MqttProperties
    if (version.supportsProperties) {
        val (props, propsConsumed) = decodePropertiesSection(body, pos)
        properties = props
        pos += propsConsumed
    } else {
        properties = MqttProperties.EMPTY
    }

    val reasonCodes = mutableListOf<ReasonCode>()
    while (pos < body.size) {
        val codeByte = body[pos].toInt() and 0xFF
        pos++
        if (version.supportsProperties) {
            reasonCodes += ReasonCode.fromValue(codeByte)
        } else {
            // MQTT 3.1.1 SUBACK return codes: 0x00=QoS0, 0x01=QoS1, 0x02=QoS2, 0x80=Failure
            reasonCodes += reasonCodeFromSubAckReturnCode(codeByte)
        }
    }

    require(reasonCodes.isNotEmpty()) {
        "SUBACK must contain at least one reason code (§3.9.3)"
    }

    return SubAck(
        packetIdentifier = packetId,
        reasonCodes = reasonCodes,
        properties = properties,
    )
}

// --- §3.10 UNSUBSCRIBE ---

private fun decodeUnsubscribe(
    body: ByteArray,
    version: MqttProtocolVersion,
): Unsubscribe {
    var pos = 0

    val packetId = WireFormat.decodeTwoByteInt(body, pos)
    pos += 2

    val properties: MqttProperties
    if (version.supportsProperties) {
        val (props, propsConsumed) = decodePropertiesSection(body, pos)
        properties = props
        pos += propsConsumed
    } else {
        properties = MqttProperties.EMPTY
    }

    val topicFilters = mutableListOf<String>()
    while (pos < body.size) {
        val (filter, consumed) = WireFormat.decodeUtf8String(body, pos)
        topicFilters += filter
        pos += consumed
    }

    return Unsubscribe(
        packetIdentifier = packetId,
        topicFilters = topicFilters,
        properties = properties,
    )
}

// --- §3.11 UNSUBACK ---

private fun decodeUnsubAck(
    body: ByteArray,
    version: MqttProtocolVersion,
): UnsubAck {
    var pos = 0

    val packetId = WireFormat.decodeTwoByteInt(body, pos)
    pos += 2

    if (!version.supportsProperties) {
        // MQTT 3.1.1: UNSUBACK has no payload beyond packet identifier (§3.11)
        // Synthesize a single SUCCESS — callers that need per-topic status should
        // track the original UNSUBSCRIBE request.
        return UnsubAck(
            packetIdentifier = packetId,
            reasonCodes = listOf(ReasonCode.SUCCESS),
        )
    }

    val (properties, propsConsumed) = decodePropertiesSection(body, pos)
    pos += propsConsumed

    val reasonCodes = mutableListOf<ReasonCode>()
    while (pos < body.size) {
        reasonCodes += ReasonCode.fromValue(body[pos].toInt() and 0xFF)
        pos++
    }

    require(reasonCodes.isNotEmpty()) {
        "UNSUBACK must contain at least one reason code (§3.11.3)"
    }

    return UnsubAck(
        packetIdentifier = packetId,
        reasonCodes = reasonCodes,
        properties = properties,
    )
}

// --- §3.14 DISCONNECT ---

private fun decodeDisconnect(
    body: ByteArray,
    version: MqttProtocolVersion,
): Disconnect {
    // MQTT 3.1.1: DISCONNECT has no variable header or payload (§3.14)
    if (!version.supportsProperties) {
        return Disconnect()
    }

    // Short form: empty body → SUCCESS with no properties
    if (body.isEmpty()) {
        return Disconnect()
    }

    val reasonCode = ReasonCode.fromValue(body[0].toInt() and 0xFF)

    if (body.size == 1) {
        return Disconnect(reasonCode = reasonCode)
    }

    val (properties, _) = decodePropertiesSection(body, 1)
    return Disconnect(reasonCode = reasonCode, properties = properties)
}

// --- §3.15 AUTH ---

private fun decodeAuth(body: ByteArray): Auth {
    // §3.15.2.1: Remaining length may be 0 for SUCCESS with no properties
    if (body.isEmpty()) return Auth()

    val reasonCode = ReasonCode.fromValue(body[0].toInt() and 0xFF)

    if (body.size == 1) {
        return Auth(reasonCode = reasonCode)
    }

    val (properties, _) = decodePropertiesSection(body, 1)
    return Auth(reasonCode = reasonCode, properties = properties)
}

// --- Utility ---

/**
 * Decode a properties section: VBI property-length followed by property bytes.
 * @return Pair of (decoded properties, total bytes consumed including length VBI).
 */
private fun decodePropertiesSection(
    bytes: ByteArray,
    offset: Int,
): Pair<MqttProperties, Int> {
    val lengthResult = VariableByteInt.decode(bytes, offset)
    val propsLength = lengthResult.value
    val props = decodeProperties(bytes, offset + lengthResult.bytesConsumed, propsLength)
    return props to (lengthResult.bytesConsumed + propsLength)
}

/**
 * Map an MQTT 3.1.1 CONNACK return code (§3.2.2.3) to a [ReasonCode].
 *
 * The 3.1.1 return codes use different wire values than MQTT 5.0 reason codes,
 * so this is NOT a simple `ReasonCode.fromValue()` lookup.
 */
internal fun reasonCodeFromConnAckReturnCode(returnCode: Int): ReasonCode =
    when (returnCode) {
        0 -> ReasonCode.SUCCESS
        1 -> ReasonCode.UNSUPPORTED_PROTOCOL_VERSION
        2 -> ReasonCode.CLIENT_IDENTIFIER_NOT_VALID
        3 -> ReasonCode.SERVER_UNAVAILABLE
        4 -> ReasonCode.BAD_USER_NAME_OR_PASSWORD
        5 -> ReasonCode.NOT_AUTHORIZED
        else -> ReasonCode.UNSPECIFIED_ERROR
    }

/**
 * Map an MQTT 3.1.1 SUBACK return code (§3.9.3) to a [ReasonCode].
 *
 * 3.1.1 values: 0x00=Maximum QoS 0, 0x01=Maximum QoS 1, 0x02=Maximum QoS 2, 0x80=Failure.
 */
internal fun reasonCodeFromSubAckReturnCode(returnCode: Int): ReasonCode =
    when (returnCode) {
        0x00 -> ReasonCode.SUCCESS
        0x01 -> ReasonCode.GRANTED_QOS_1
        0x02 -> ReasonCode.GRANTED_QOS_2
        0x80 -> ReasonCode.UNSPECIFIED_ERROR
        else -> ReasonCode.UNSPECIFIED_ERROR
    }
