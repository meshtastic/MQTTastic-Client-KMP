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

/**
 * MQTT 5.0 property identifiers per §2.2.2.2.
 */
internal object PropertyId {
    const val PAYLOAD_FORMAT_INDICATOR: Int = 0x01
    const val MESSAGE_EXPIRY_INTERVAL: Int = 0x02
    const val CONTENT_TYPE: Int = 0x03
    const val RESPONSE_TOPIC: Int = 0x08
    const val CORRELATION_DATA: Int = 0x09
    const val SUBSCRIPTION_IDENTIFIER: Int = 0x0B
    const val SESSION_EXPIRY_INTERVAL: Int = 0x11
    const val ASSIGNED_CLIENT_IDENTIFIER: Int = 0x12
    const val SERVER_KEEP_ALIVE: Int = 0x13
    const val AUTHENTICATION_METHOD: Int = 0x15
    const val AUTHENTICATION_DATA: Int = 0x16
    const val REQUEST_PROBLEM_INFORMATION: Int = 0x17
    const val WILL_DELAY_INTERVAL: Int = 0x18
    const val REQUEST_RESPONSE_INFORMATION: Int = 0x19
    const val RESPONSE_INFORMATION: Int = 0x1A
    const val SERVER_REFERENCE: Int = 0x1C
    const val REASON_STRING: Int = 0x1F
    const val RECEIVE_MAXIMUM: Int = 0x21
    const val TOPIC_ALIAS_MAXIMUM: Int = 0x22
    const val TOPIC_ALIAS: Int = 0x23
    const val MAXIMUM_QOS: Int = 0x24
    const val RETAIN_AVAILABLE: Int = 0x25
    const val USER_PROPERTY: Int = 0x26
    const val MAXIMUM_PACKET_SIZE: Int = 0x27
    const val WILDCARD_SUBSCRIPTION_AVAILABLE: Int = 0x28
    const val SUBSCRIPTION_IDENTIFIERS_AVAILABLE: Int = 0x29
    const val SHARED_SUBSCRIPTION_AVAILABLE: Int = 0x2A
}

/**
 * Unified MQTT 5.0 property model used across all packet types (§2.2.2).
 *
 * All properties are nullable (null = not present on the wire). Multi-occurrence
 * properties use lists that default to empty.
 */
internal data class MqttProperties(
    val payloadFormatIndicator: Boolean = false,
    val messageExpiryInterval: Long? = null,
    val contentType: String? = null,
    val responseTopic: String? = null,
    val correlationData: ByteArray? = null,
    val subscriptionIdentifier: List<Int> = emptyList(),
    val sessionExpiryInterval: Long? = null,
    val assignedClientIdentifier: String? = null,
    val serverKeepAlive: Int? = null,
    val authenticationMethod: String? = null,
    val authenticationData: ByteArray? = null,
    val requestProblemInformation: Boolean? = null,
    val willDelayInterval: Long? = null,
    val requestResponseInformation: Boolean? = null,
    val responseInformation: String? = null,
    val serverReference: String? = null,
    val reasonString: String? = null,
    val receiveMaximum: Int? = null,
    val topicAliasMaximum: Int? = null,
    val topicAlias: Int? = null,
    val maximumQos: Int? = null,
    val retainAvailable: Boolean? = null,
    val userProperties: List<Pair<String, String>> = emptyList(),
    val maximumPacketSize: Long? = null,
    val wildcardSubscriptionAvailable: Boolean? = null,
    val subscriptionIdentifiersAvailable: Boolean? = null,
    val sharedSubscriptionAvailable: Boolean? = null,
) {
    init {
        receiveMaximum?.let {
            require(it in 1..65_535) { "receiveMaximum must be 1..65535, got: $it" }
        }
        topicAliasMaximum?.let {
            require(it in 0..65_535) { "topicAliasMaximum must be 0..65535, got: $it" }
        }
        topicAlias?.let {
            require(it in 1..65_535) { "topicAlias must be 1..65535, got: $it" }
        }
        maximumQos?.let {
            require(it == 0 || it == 1) { "maximumQos must be 0 or 1, got: $it" }
        }
        maximumPacketSize?.let {
            require(it in 1..4_294_967_295L) { "maximumPacketSize must be 1..4294967295, got: $it" }
        }
        sessionExpiryInterval?.let {
            require(it in 0..4_294_967_295L) { "sessionExpiryInterval must be 0..4294967295, got: $it" }
        }
        messageExpiryInterval?.let {
            require(it in 0..4_294_967_295L) { "messageExpiryInterval must be 0..4294967295, got: $it" }
        }
        willDelayInterval?.let {
            require(it in 0..4_294_967_295L) { "willDelayInterval must be 0..4294967295, got: $it" }
        }
        subscriptionIdentifier.forEach {
            require(it in 1..VariableByteInt.MAX_VALUE) {
                "subscriptionIdentifier must be 1..268435455, got: $it"
            }
        }
    }

    // ByteArray fields require manual equals/hashCode for data class correctness.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MqttProperties) return false
        return payloadFormatIndicator == other.payloadFormatIndicator &&
            messageExpiryInterval == other.messageExpiryInterval &&
            contentType == other.contentType &&
            responseTopic == other.responseTopic &&
            correlationData.contentEqualsNullable(other.correlationData) &&
            subscriptionIdentifier == other.subscriptionIdentifier &&
            sessionExpiryInterval == other.sessionExpiryInterval &&
            assignedClientIdentifier == other.assignedClientIdentifier &&
            serverKeepAlive == other.serverKeepAlive &&
            authenticationMethod == other.authenticationMethod &&
            authenticationData.contentEqualsNullable(other.authenticationData) &&
            requestProblemInformation == other.requestProblemInformation &&
            willDelayInterval == other.willDelayInterval &&
            requestResponseInformation == other.requestResponseInformation &&
            responseInformation == other.responseInformation &&
            serverReference == other.serverReference &&
            reasonString == other.reasonString &&
            receiveMaximum == other.receiveMaximum &&
            topicAliasMaximum == other.topicAliasMaximum &&
            topicAlias == other.topicAlias &&
            maximumQos == other.maximumQos &&
            retainAvailable == other.retainAvailable &&
            userProperties == other.userProperties &&
            maximumPacketSize == other.maximumPacketSize &&
            wildcardSubscriptionAvailable == other.wildcardSubscriptionAvailable &&
            subscriptionIdentifiersAvailable == other.subscriptionIdentifiersAvailable &&
            sharedSubscriptionAvailable == other.sharedSubscriptionAvailable
    }

    override fun hashCode(): Int {
        var result = payloadFormatIndicator.hashCode()
        result = 31 * result + messageExpiryInterval.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + responseTopic.hashCode()
        result = 31 * result + (correlationData?.contentHashCode() ?: 0)
        result = 31 * result + subscriptionIdentifier.hashCode()
        result = 31 * result + sessionExpiryInterval.hashCode()
        result = 31 * result + assignedClientIdentifier.hashCode()
        result = 31 * result + serverKeepAlive.hashCode()
        result = 31 * result + authenticationMethod.hashCode()
        result = 31 * result + (authenticationData?.contentHashCode() ?: 0)
        result = 31 * result + requestProblemInformation.hashCode()
        result = 31 * result + willDelayInterval.hashCode()
        result = 31 * result + requestResponseInformation.hashCode()
        result = 31 * result + responseInformation.hashCode()
        result = 31 * result + serverReference.hashCode()
        result = 31 * result + reasonString.hashCode()
        result = 31 * result + receiveMaximum.hashCode()
        result = 31 * result + topicAliasMaximum.hashCode()
        result = 31 * result + topicAlias.hashCode()
        result = 31 * result + maximumQos.hashCode()
        result = 31 * result + retainAvailable.hashCode()
        result = 31 * result + userProperties.hashCode()
        result = 31 * result + maximumPacketSize.hashCode()
        result = 31 * result + wildcardSubscriptionAvailable.hashCode()
        result = 31 * result + subscriptionIdentifiersAvailable.hashCode()
        result = 31 * result + sharedSubscriptionAvailable.hashCode()
        return result
    }

    companion object {
        val EMPTY: MqttProperties = MqttProperties()
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this === other -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }

// --- Encode / Decode ---

/** Encode all non-default properties into wire format (property length VBI + property bytes). */
internal fun MqttProperties.encode(): ByteArray {
    val body = encodePropertyBody()
    val lengthVbi = VariableByteInt.encode(body.size)
    return lengthVbi + body
}

/** Decode properties from wire bytes starting at [offset] for [length] bytes. */
internal fun decodeProperties(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): MqttProperties {
    if (length == 0) return MqttProperties.EMPTY

    var pos = offset
    val end = offset + length
    val seenSingletons = mutableSetOf<Int>()

    var payloadFormatIndicator = false
    var messageExpiryInterval: Long? = null
    var contentType: String? = null
    var responseTopic: String? = null
    var correlationData: ByteArray? = null
    val subscriptionIdentifier = mutableListOf<Int>()
    var sessionExpiryInterval: Long? = null
    var assignedClientIdentifier: String? = null
    var serverKeepAlive: Int? = null
    var authenticationMethod: String? = null
    var authenticationData: ByteArray? = null
    var requestProblemInformation: Boolean? = null
    var willDelayInterval: Long? = null
    var requestResponseInformation: Boolean? = null
    var responseInformation: String? = null
    var serverReference: String? = null
    var reasonString: String? = null
    var receiveMaximum: Int? = null
    var topicAliasMaximum: Int? = null
    var topicAlias: Int? = null
    var maximumQos: Int? = null
    var retainAvailable: Boolean? = null
    val userProperties = mutableListOf<Pair<String, String>>()
    var maximumPacketSize: Long? = null
    var wildcardSubscriptionAvailable: Boolean? = null
    var subscriptionIdentifiersAvailable: Boolean? = null
    var sharedSubscriptionAvailable: Boolean? = null

    while (pos < end) {
        val idResult = VariableByteInt.decode(bytes, pos)
        pos += idResult.bytesConsumed
        val propertyId = idResult.value

        // Multi-occurrence properties: User Property and Subscription Identifier
        val isMultiOccurrence =
            propertyId == PropertyId.USER_PROPERTY ||
                propertyId == PropertyId.SUBSCRIPTION_IDENTIFIER

        if (!isMultiOccurrence) {
            require(seenSingletons.add(propertyId)) {
                "Duplicate property ID: 0x${propertyId.toString(16)}"
            }
        }

        when (propertyId) {
            PropertyId.PAYLOAD_FORMAT_INDICATOR -> {
                payloadFormatIndicator = decodeBooleanByte(bytes, pos)
                pos += 1
            }

            PropertyId.MESSAGE_EXPIRY_INTERVAL -> {
                messageExpiryInterval = WireFormat.decodeFourByteInt(bytes, pos)
                pos += 4
            }

            PropertyId.CONTENT_TYPE -> {
                val (v, consumed) = WireFormat.decodeUtf8String(bytes, pos)
                contentType = v
                pos += consumed
            }

            PropertyId.RESPONSE_TOPIC -> {
                val (v, consumed) = WireFormat.decodeUtf8String(bytes, pos)
                responseTopic = v
                pos += consumed
            }

            PropertyId.CORRELATION_DATA -> {
                val (v, consumed) = WireFormat.decodeBinaryData(bytes, pos)
                correlationData = v
                pos += consumed
            }

            PropertyId.SUBSCRIPTION_IDENTIFIER -> {
                val vbiResult = VariableByteInt.decode(bytes, pos)
                subscriptionIdentifier.add(vbiResult.value)
                pos += vbiResult.bytesConsumed
            }

            PropertyId.SESSION_EXPIRY_INTERVAL -> {
                sessionExpiryInterval = WireFormat.decodeFourByteInt(bytes, pos)
                pos += 4
            }

            PropertyId.ASSIGNED_CLIENT_IDENTIFIER -> {
                val (v, consumed) = WireFormat.decodeUtf8String(bytes, pos)
                assignedClientIdentifier = v
                pos += consumed
            }

            PropertyId.SERVER_KEEP_ALIVE -> {
                serverKeepAlive = WireFormat.decodeTwoByteInt(bytes, pos)
                pos += 2
            }

            PropertyId.AUTHENTICATION_METHOD -> {
                val (v, consumed) = WireFormat.decodeUtf8String(bytes, pos)
                authenticationMethod = v
                pos += consumed
            }

            PropertyId.AUTHENTICATION_DATA -> {
                val (v, consumed) = WireFormat.decodeBinaryData(bytes, pos)
                authenticationData = v
                pos += consumed
            }

            PropertyId.REQUEST_PROBLEM_INFORMATION -> {
                requestProblemInformation = decodeBooleanByte(bytes, pos)
                pos += 1
            }

            PropertyId.WILL_DELAY_INTERVAL -> {
                willDelayInterval = WireFormat.decodeFourByteInt(bytes, pos)
                pos += 4
            }

            PropertyId.REQUEST_RESPONSE_INFORMATION -> {
                requestResponseInformation = decodeBooleanByte(bytes, pos)
                pos += 1
            }

            PropertyId.RESPONSE_INFORMATION -> {
                val (v, consumed) = WireFormat.decodeUtf8String(bytes, pos)
                responseInformation = v
                pos += consumed
            }

            PropertyId.SERVER_REFERENCE -> {
                val (v, consumed) = WireFormat.decodeUtf8String(bytes, pos)
                serverReference = v
                pos += consumed
            }

            PropertyId.REASON_STRING -> {
                val (v, consumed) = WireFormat.decodeUtf8String(bytes, pos)
                reasonString = v
                pos += consumed
            }

            PropertyId.RECEIVE_MAXIMUM -> {
                receiveMaximum = WireFormat.decodeTwoByteInt(bytes, pos)
                pos += 2
            }

            PropertyId.TOPIC_ALIAS_MAXIMUM -> {
                topicAliasMaximum = WireFormat.decodeTwoByteInt(bytes, pos)
                pos += 2
            }

            PropertyId.TOPIC_ALIAS -> {
                topicAlias = WireFormat.decodeTwoByteInt(bytes, pos)
                pos += 2
            }

            PropertyId.MAXIMUM_QOS -> {
                maximumQos = bytes[pos].toInt() and 0xFF
                pos += 1
            }

            PropertyId.RETAIN_AVAILABLE -> {
                retainAvailable = decodeBooleanByte(bytes, pos)
                pos += 1
            }

            PropertyId.USER_PROPERTY -> {
                val (key, value, consumed) = WireFormat.decodeStringPair(bytes, pos)
                userProperties.add(key to value)
                pos += consumed
            }

            PropertyId.MAXIMUM_PACKET_SIZE -> {
                maximumPacketSize = WireFormat.decodeFourByteInt(bytes, pos)
                pos += 4
            }

            PropertyId.WILDCARD_SUBSCRIPTION_AVAILABLE -> {
                wildcardSubscriptionAvailable = decodeBooleanByte(bytes, pos)
                pos += 1
            }

            PropertyId.SUBSCRIPTION_IDENTIFIERS_AVAILABLE -> {
                subscriptionIdentifiersAvailable = decodeBooleanByte(bytes, pos)
                pos += 1
            }

            PropertyId.SHARED_SUBSCRIPTION_AVAILABLE -> {
                sharedSubscriptionAvailable = decodeBooleanByte(bytes, pos)
                pos += 1
            }

            else -> {
                throw IllegalArgumentException("Unknown property ID: 0x${idResult.value.toString(16)}")
            }
        }
    }

    return MqttProperties(
        payloadFormatIndicator = payloadFormatIndicator,
        messageExpiryInterval = messageExpiryInterval,
        contentType = contentType,
        responseTopic = responseTopic,
        correlationData = correlationData,
        subscriptionIdentifier = subscriptionIdentifier,
        sessionExpiryInterval = sessionExpiryInterval,
        assignedClientIdentifier = assignedClientIdentifier,
        serverKeepAlive = serverKeepAlive,
        authenticationMethod = authenticationMethod,
        authenticationData = authenticationData,
        requestProblemInformation = requestProblemInformation,
        willDelayInterval = willDelayInterval,
        requestResponseInformation = requestResponseInformation,
        responseInformation = responseInformation,
        serverReference = serverReference,
        reasonString = reasonString,
        receiveMaximum = receiveMaximum,
        topicAliasMaximum = topicAliasMaximum,
        topicAlias = topicAlias,
        maximumQos = maximumQos,
        retainAvailable = retainAvailable,
        userProperties = userProperties,
        maximumPacketSize = maximumPacketSize,
        wildcardSubscriptionAvailable = wildcardSubscriptionAvailable,
        subscriptionIdentifiersAvailable = subscriptionIdentifiersAvailable,
        sharedSubscriptionAvailable = sharedSubscriptionAvailable,
    )
}

private fun decodeBooleanByte(
    bytes: ByteArray,
    offset: Int,
): Boolean {
    require(offset < bytes.size) { "Not enough bytes for boolean at offset $offset" }
    return (bytes[offset].toInt() and 0xFF) != 0
}

@Suppress("CyclomaticComplexMethod")
private fun MqttProperties.encodePropertyBody(): ByteArray {
    val parts = mutableListOf<ByteArray>()

    if (payloadFormatIndicator) {
        parts += encodeByteProp(PropertyId.PAYLOAD_FORMAT_INDICATOR, 1)
    }
    messageExpiryInterval?.let {
        parts += encodeFourByteProp(PropertyId.MESSAGE_EXPIRY_INTERVAL, it)
    }
    contentType?.let {
        parts += encodeStringProp(PropertyId.CONTENT_TYPE, it)
    }
    responseTopic?.let {
        parts += encodeStringProp(PropertyId.RESPONSE_TOPIC, it)
    }
    correlationData?.let {
        parts += encodeBinaryProp(PropertyId.CORRELATION_DATA, it)
    }
    subscriptionIdentifier.forEach { value ->
        parts += VariableByteInt.encode(PropertyId.SUBSCRIPTION_IDENTIFIER) + VariableByteInt.encode(value)
    }
    sessionExpiryInterval?.let {
        parts += encodeFourByteProp(PropertyId.SESSION_EXPIRY_INTERVAL, it)
    }
    assignedClientIdentifier?.let {
        parts += encodeStringProp(PropertyId.ASSIGNED_CLIENT_IDENTIFIER, it)
    }
    serverKeepAlive?.let {
        parts += encodeTwoByteProp(PropertyId.SERVER_KEEP_ALIVE, it)
    }
    authenticationMethod?.let {
        parts += encodeStringProp(PropertyId.AUTHENTICATION_METHOD, it)
    }
    authenticationData?.let {
        parts += encodeBinaryProp(PropertyId.AUTHENTICATION_DATA, it)
    }
    requestProblemInformation?.let {
        parts += encodeBoolProp(PropertyId.REQUEST_PROBLEM_INFORMATION, it)
    }
    willDelayInterval?.let {
        parts += encodeFourByteProp(PropertyId.WILL_DELAY_INTERVAL, it)
    }
    requestResponseInformation?.let {
        parts += encodeBoolProp(PropertyId.REQUEST_RESPONSE_INFORMATION, it)
    }
    responseInformation?.let {
        parts += encodeStringProp(PropertyId.RESPONSE_INFORMATION, it)
    }
    serverReference?.let {
        parts += encodeStringProp(PropertyId.SERVER_REFERENCE, it)
    }
    reasonString?.let {
        parts += encodeStringProp(PropertyId.REASON_STRING, it)
    }
    receiveMaximum?.let {
        parts += encodeTwoByteProp(PropertyId.RECEIVE_MAXIMUM, it)
    }
    topicAliasMaximum?.let {
        parts += encodeTwoByteProp(PropertyId.TOPIC_ALIAS_MAXIMUM, it)
    }
    topicAlias?.let {
        parts += encodeTwoByteProp(PropertyId.TOPIC_ALIAS, it)
    }
    maximumQos?.let {
        parts += encodeByteProp(PropertyId.MAXIMUM_QOS, it)
    }
    retainAvailable?.let {
        parts += encodeBoolProp(PropertyId.RETAIN_AVAILABLE, it)
    }
    userProperties.forEach { (key, value) ->
        parts += VariableByteInt.encode(PropertyId.USER_PROPERTY) + WireFormat.encodeStringPair(key, value)
    }
    maximumPacketSize?.let {
        parts += encodeFourByteProp(PropertyId.MAXIMUM_PACKET_SIZE, it)
    }
    wildcardSubscriptionAvailable?.let {
        parts += encodeBoolProp(PropertyId.WILDCARD_SUBSCRIPTION_AVAILABLE, it)
    }
    subscriptionIdentifiersAvailable?.let {
        parts += encodeBoolProp(PropertyId.SUBSCRIPTION_IDENTIFIERS_AVAILABLE, it)
    }
    sharedSubscriptionAvailable?.let {
        parts += encodeBoolProp(PropertyId.SHARED_SUBSCRIPTION_AVAILABLE, it)
    }

    val totalSize = parts.sumOf { it.size }
    val result = ByteArray(totalSize)
    var pos = 0
    parts.forEach { part ->
        part.copyInto(result, pos)
        pos += part.size
    }
    return result
}

private fun encodeByteProp(
    id: Int,
    value: Int,
): ByteArray = VariableByteInt.encode(id) + byteArrayOf(value.toByte())

private fun encodeBoolProp(
    id: Int,
    value: Boolean,
): ByteArray = encodeByteProp(id, if (value) 1 else 0)

private fun encodeTwoByteProp(
    id: Int,
    value: Int,
): ByteArray = VariableByteInt.encode(id) + WireFormat.encodeTwoByteInt(value)

private fun encodeFourByteProp(
    id: Int,
    value: Long,
): ByteArray = VariableByteInt.encode(id) + WireFormat.encodeFourByteInt(value)

private fun encodeStringProp(
    id: Int,
    value: String,
): ByteArray = VariableByteInt.encode(id) + WireFormat.encodeUtf8String(value)

private fun encodeBinaryProp(
    id: Int,
    value: ByteArray,
): ByteArray = VariableByteInt.encode(id) + WireFormat.encodeBinaryData(value)
