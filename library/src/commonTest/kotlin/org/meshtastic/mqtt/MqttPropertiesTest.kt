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

import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.PropertyId
import org.meshtastic.mqtt.packet.VariableByteInt
import org.meshtastic.mqtt.packet.WireFormat
import org.meshtastic.mqtt.packet.decodeProperties
import org.meshtastic.mqtt.packet.encode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MqttPropertiesTest {
    // ===== EMPTY / defaults =====

    @Test
    fun emptyHasAllFieldsAtDefault() {
        val props = MqttProperties.EMPTY
        assertFalse(props.payloadFormatIndicator)
        assertNull(props.messageExpiryInterval)
        assertNull(props.contentType)
        assertNull(props.responseTopic)
        assertNull(props.correlationData)
        assertTrue(props.subscriptionIdentifier.isEmpty())
        assertNull(props.sessionExpiryInterval)
        assertNull(props.assignedClientIdentifier)
        assertNull(props.serverKeepAlive)
        assertNull(props.authenticationMethod)
        assertNull(props.authenticationData)
        assertNull(props.requestProblemInformation)
        assertNull(props.willDelayInterval)
        assertNull(props.requestResponseInformation)
        assertNull(props.responseInformation)
        assertNull(props.serverReference)
        assertNull(props.reasonString)
        assertNull(props.receiveMaximum)
        assertNull(props.topicAliasMaximum)
        assertNull(props.topicAlias)
        assertNull(props.maximumQos)
        assertNull(props.retainAvailable)
        assertTrue(props.userProperties.isEmpty())
        assertNull(props.maximumPacketSize)
        assertNull(props.wildcardSubscriptionAvailable)
        assertNull(props.subscriptionIdentifiersAvailable)
        assertNull(props.sharedSubscriptionAvailable)
    }

    @Test
    fun dataClassEquality() {
        val a = MqttProperties(receiveMaximum = 100, reasonString = "ok")
        val b = MqttProperties(receiveMaximum = 100, reasonString = "ok")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun dataClassEqualityWithByteArrays() {
        val data = byteArrayOf(1, 2, 3)
        val a = MqttProperties(correlationData = data.copyOf())
        val b = MqttProperties(correlationData = data.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ===== Validation tests =====

    @Test
    fun receiveMaximumRejectsZero() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(receiveMaximum = 0) }
    }

    @Test
    fun receiveMaximumRejects65536() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(receiveMaximum = 65_536) }
    }

    @Test
    fun receiveMaximumAccepts1() {
        assertEquals(1, MqttProperties(receiveMaximum = 1).receiveMaximum)
    }

    @Test
    fun receiveMaximumAccepts65535() {
        assertEquals(65_535, MqttProperties(receiveMaximum = 65_535).receiveMaximum)
    }

    @Test
    fun topicAliasRejectsZero() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(topicAlias = 0) }
    }

    @Test
    fun topicAliasRejects65536() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(topicAlias = 65_536) }
    }

    @Test
    fun topicAliasAccepts1() {
        assertEquals(1, MqttProperties(topicAlias = 1).topicAlias)
    }

    @Test
    fun topicAliasAccepts65535() {
        assertEquals(65_535, MqttProperties(topicAlias = 65_535).topicAlias)
    }

    @Test
    fun topicAliasMaximumAccepts0() {
        assertEquals(0, MqttProperties(topicAliasMaximum = 0).topicAliasMaximum)
    }

    @Test
    fun maximumQosRejects2() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(maximumQos = 2) }
    }

    @Test
    fun maximumQosAccepts0() {
        assertEquals(0, MqttProperties(maximumQos = 0).maximumQos)
    }

    @Test
    fun maximumQosAccepts1() {
        assertEquals(1, MqttProperties(maximumQos = 1).maximumQos)
    }

    @Test
    fun maximumPacketSizeRejectsZero() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(maximumPacketSize = 0) }
    }

    @Test
    fun maximumPacketSizeAccepts1() {
        assertEquals(1L, MqttProperties(maximumPacketSize = 1).maximumPacketSize)
    }

    @Test
    fun maximumPacketSizeAcceptsMax() {
        assertEquals(4_294_967_295L, MqttProperties(maximumPacketSize = 4_294_967_295L).maximumPacketSize)
    }

    @Test
    fun subscriptionIdentifierRejectsZero() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(subscriptionIdentifier = listOf(0)) }
    }

    @Test
    fun subscriptionIdentifierRejects268435456() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(subscriptionIdentifier = listOf(268_435_456)) }
    }

    @Test
    fun subscriptionIdentifierAcceptsBoundaries() {
        val props = MqttProperties(subscriptionIdentifier = listOf(1, 268_435_455))
        assertEquals(listOf(1, 268_435_455), props.subscriptionIdentifier)
    }

    @Test
    fun sessionExpiryIntervalRejectsNegative() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(sessionExpiryInterval = -1) }
    }

    @Test
    fun messageExpiryIntervalRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(messageExpiryInterval = 4_294_967_296L) }
    }

    @Test
    fun willDelayIntervalRejectsNegative() {
        assertFailsWith<IllegalArgumentException> { MqttProperties(willDelayInterval = -1) }
    }

    // ===== WireFormat round-trip tests =====

    @Test
    fun wireFormatUtf8StringEmpty() {
        val encoded = WireFormat.encodeUtf8String("")
        assertContentEquals(byteArrayOf(0x00, 0x00), encoded)
        val (decoded, consumed) = WireFormat.decodeUtf8String(encoded, 0)
        assertEquals("", decoded)
        assertEquals(2, consumed)
    }

    @Test
    fun wireFormatUtf8StringAscii() {
        val encoded = WireFormat.encodeUtf8String("hello")
        assertEquals(2 + 5, encoded.size)
        val (decoded, consumed) = WireFormat.decodeUtf8String(encoded, 0)
        assertEquals("hello", decoded)
        assertEquals(7, consumed)
    }

    @Test
    fun wireFormatUtf8StringMultiByteEmoji() {
        val text = "hello 🌍"
        val encoded = WireFormat.encodeUtf8String(text)
        val (decoded, consumed) = WireFormat.decodeUtf8String(encoded, 0)
        assertEquals(text, decoded)
        assertEquals(encoded.size, consumed)
    }

    @Test
    fun wireFormatUtf8StringCjk() {
        val text = "你好世界"
        val encoded = WireFormat.encodeUtf8String(text)
        val (decoded, consumed) = WireFormat.decodeUtf8String(encoded, 0)
        assertEquals(text, decoded)
        assertEquals(encoded.size, consumed)
    }

    @Test
    fun wireFormatBinaryDataEmpty() {
        val encoded = WireFormat.encodeBinaryData(byteArrayOf())
        assertContentEquals(byteArrayOf(0x00, 0x00), encoded)
        val (decoded, consumed) = WireFormat.decodeBinaryData(encoded, 0)
        assertContentEquals(byteArrayOf(), decoded)
        assertEquals(2, consumed)
    }

    @Test
    fun wireFormatBinaryDataNonEmpty() {
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val encoded = WireFormat.encodeBinaryData(data)
        assertEquals(2 + 4, encoded.size)
        val (decoded, consumed) = WireFormat.decodeBinaryData(encoded, 0)
        assertContentEquals(data, decoded)
        assertEquals(6, consumed)
    }

    @Test
    fun wireFormatTwoByteIntZero() {
        val encoded = WireFormat.encodeTwoByteInt(0)
        assertContentEquals(byteArrayOf(0x00, 0x00), encoded)
        assertEquals(0, WireFormat.decodeTwoByteInt(encoded, 0))
    }

    @Test
    fun wireFormatTwoByteIntOne() {
        val encoded = WireFormat.encodeTwoByteInt(1)
        assertContentEquals(byteArrayOf(0x00, 0x01), encoded)
        assertEquals(1, WireFormat.decodeTwoByteInt(encoded, 0))
    }

    @Test
    fun wireFormatTwoByteIntMax() {
        val encoded = WireFormat.encodeTwoByteInt(65535)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), encoded)
        assertEquals(65535, WireFormat.decodeTwoByteInt(encoded, 0))
    }

    @Test
    fun wireFormatFourByteIntZero() {
        val encoded = WireFormat.encodeFourByteInt(0)
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x00, 0x00), encoded)
        assertEquals(0L, WireFormat.decodeFourByteInt(encoded, 0))
    }

    @Test
    fun wireFormatFourByteIntOne() {
        val encoded = WireFormat.encodeFourByteInt(1)
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01), encoded)
        assertEquals(1L, WireFormat.decodeFourByteInt(encoded, 0))
    }

    @Test
    fun wireFormatFourByteIntMax() {
        val encoded = WireFormat.encodeFourByteInt(4_294_967_295L)
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            encoded,
        )
        assertEquals(4_294_967_295L, WireFormat.decodeFourByteInt(encoded, 0))
    }

    @Test
    fun wireFormatStringPair() {
        val encoded = WireFormat.encodeStringPair("key", "value")
        val (key, value, consumed) = WireFormat.decodeStringPair(encoded, 0)
        assertEquals("key", key)
        assertEquals("value", value)
        assertEquals(encoded.size, consumed)
    }

    @Test
    fun wireFormatDecodeWithOffset() {
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val encoded = WireFormat.encodeUtf8String("test")
        val combined = prefix + encoded
        val (decoded, consumed) = WireFormat.decodeUtf8String(combined, 2)
        assertEquals("test", decoded)
        assertEquals(encoded.size, consumed)
    }

    // ===== MqttProperties encode/decode round-trip tests =====

    @Test
    fun encodeDecodeEmptyProperties() {
        val encoded = MqttProperties.EMPTY.encode()
        // Empty properties = just a VBI length of 0
        assertContentEquals(byteArrayOf(0x00), encoded)

        val decoded = decodePropertiesFromEncoded(encoded)
        assertEquals(MqttProperties.EMPTY, decoded)
    }

    @Test
    fun encodeDecodeWithOneField() {
        val props = MqttProperties(receiveMaximum = 100)
        val decoded = roundTrip(props)
        assertEquals(props, decoded)
    }

    @Test
    fun encodeDecodeWithManyFields() {
        val props =
            MqttProperties(
                payloadFormatIndicator = true,
                messageExpiryInterval = 3600L,
                contentType = "application/json",
                responseTopic = "response/topic",
                correlationData = byteArrayOf(1, 2, 3, 4),
                sessionExpiryInterval = 7200L,
                serverKeepAlive = 60,
                authenticationMethod = "SCRAM-SHA-256",
                requestProblemInformation = true,
                requestResponseInformation = false,
                receiveMaximum = 100,
                topicAliasMaximum = 10,
                topicAlias = 5,
                maximumQos = 1,
                retainAvailable = true,
                maximumPacketSize = 1_048_576L,
                wildcardSubscriptionAvailable = true,
                subscriptionIdentifiersAvailable = true,
                sharedSubscriptionAvailable = false,
                reasonString = "all good",
            )
        val decoded = roundTrip(props)
        assertEquals(props, decoded)
    }

    @Test
    fun encodeDecodeWithMultipleUserProperties() {
        val props =
            MqttProperties(
                userProperties =
                    listOf(
                        "key1" to "value1",
                        "key2" to "value2",
                        "key1" to "duplicate-key-different-value",
                    ),
            )
        val decoded = roundTrip(props)
        assertEquals(props.userProperties, decoded.userProperties)
    }

    @Test
    fun encodeDecodeWithMultipleSubscriptionIdentifiers() {
        val props =
            MqttProperties(
                subscriptionIdentifier = listOf(1, 42, 268_435_455),
            )
        val decoded = roundTrip(props)
        assertEquals(props.subscriptionIdentifier, decoded.subscriptionIdentifier)
    }

    @Test
    fun encodeDecodeStringProperties() {
        val props =
            MqttProperties(
                assignedClientIdentifier = "client-abc-123",
                responseInformation = "info",
                serverReference = "server2:1883",
            )
        val decoded = roundTrip(props)
        assertEquals(props, decoded)
    }

    @Test
    fun encodeDecodeAuthenticationData() {
        val authData = byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte())
        val props =
            MqttProperties(
                authenticationMethod = "PLAIN",
                authenticationData = authData,
            )
        val decoded = roundTrip(props)
        assertEquals(props, decoded)
    }

    @Test
    fun encodeDecodeBooleanProperties() {
        val props =
            MqttProperties(
                payloadFormatIndicator = true,
                requestProblemInformation = false,
                requestResponseInformation = true,
                retainAvailable = false,
                wildcardSubscriptionAvailable = true,
                subscriptionIdentifiersAvailable = false,
                sharedSubscriptionAvailable = true,
            )
        val decoded = roundTrip(props)
        assertEquals(props, decoded)
    }

    @Test
    fun encodeDecodeWillDelayInterval() {
        val props = MqttProperties(willDelayInterval = 300L)
        val decoded = roundTrip(props)
        assertEquals(props, decoded)
    }

    @Test
    fun encodeDecodeMaxValues() {
        val props =
            MqttProperties(
                messageExpiryInterval = 4_294_967_295L,
                sessionExpiryInterval = 4_294_967_295L,
                maximumPacketSize = 4_294_967_295L,
                receiveMaximum = 65_535,
                topicAliasMaximum = 65_535,
                topicAlias = 65_535,
            )
        val decoded = roundTrip(props)
        assertEquals(props, decoded)
    }

    // ===== Duplicate Singleton Property Rejection =====

    @Test
    fun duplicateSingletonPropertyThrows() {
        // Manually construct bytes with duplicate Content Type (0x03) property
        val contentType1 = "text/plain".encodeToByteArray()
        val contentType2 = "application/json".encodeToByteArray()

        val body =
            byteArrayOf(PropertyId.CONTENT_TYPE.toByte()) +
                WireFormat.encodeUtf8String(contentType1.decodeToString()) +
                byteArrayOf(PropertyId.CONTENT_TYPE.toByte()) +
                WireFormat.encodeUtf8String(contentType2.decodeToString())

        val encoded = VariableByteInt.encode(body.size) + body

        assertFailsWith<IllegalArgumentException> {
            decodePropertiesFromEncoded(encoded)
        }
    }

    @Test
    fun multipleSubscriptionIdentifiersAllowed() {
        // Subscription Identifier (0x0B) is multi-occurrence — should NOT throw
        val props =
            MqttProperties(
                subscriptionIdentifier = listOf(1, 2, 3),
            )
        val result = roundTrip(props)
        assertEquals(listOf(1, 2, 3), result.subscriptionIdentifier)
    }

    @Test
    fun multipleUserPropertiesAllowed() {
        // User Property (0x26) is multi-occurrence — should NOT throw
        val props =
            MqttProperties(
                userProperties =
                    listOf(
                        "key1" to "val1",
                        "key1" to "val2",
                        "key2" to "val3",
                    ),
            )
        val result = roundTrip(props)
        assertEquals(3, result.userProperties.size)
    }

    // ===== Helpers =====

    private fun roundTrip(props: MqttProperties): MqttProperties {
        val encoded = props.encode()
        return decodePropertiesFromEncoded(encoded)
    }

    private fun decodePropertiesFromEncoded(encoded: ByteArray): MqttProperties {
        // First byte(s) are the VBI property length; decode that to get the body offset/length.
        val vbiResult =
            org.meshtastic.mqtt.packet.VariableByteInt
                .decode(encoded, 0)
        return decodeProperties(encoded, vbiResult.bytesConsumed, vbiResult.value)
    }
}
