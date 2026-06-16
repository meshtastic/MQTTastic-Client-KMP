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

import org.meshtastic.mqtt.packet.Auth
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.Connect
import org.meshtastic.mqtt.packet.Disconnect
import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.PingReq
import org.meshtastic.mqtt.packet.PingResp
import org.meshtastic.mqtt.packet.PubAck
import org.meshtastic.mqtt.packet.PubComp
import org.meshtastic.mqtt.packet.PubRec
import org.meshtastic.mqtt.packet.PubRel
import org.meshtastic.mqtt.packet.Publish
import org.meshtastic.mqtt.packet.SubAck
import org.meshtastic.mqtt.packet.Subscribe
import org.meshtastic.mqtt.packet.Subscription
import org.meshtastic.mqtt.packet.UnsubAck
import org.meshtastic.mqtt.packet.Unsubscribe
import org.meshtastic.mqtt.packet.decodePacket
import org.meshtastic.mqtt.packet.encode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("LargeClass")
class MqttEncoderDecoderTest {
    // ===== CONNECT (§3.1) =====

    @Test
    fun connectMinimal() {
        val packet = Connect(clientId = "")
        val decoded = roundTrip(packet) as Connect
        assertEquals(packet.protocolName, decoded.protocolName)
        assertEquals(packet.protocolLevel, decoded.protocolLevel)
        assertEquals(packet.cleanStart, decoded.cleanStart)
        assertEquals(packet.keepAliveSeconds, decoded.keepAliveSeconds)
        assertEquals(packet.clientId, decoded.clientId)
        assertEquals(packet.willTopic, decoded.willTopic)
        assertEquals(packet.username, decoded.username)
        assertEquals(packet.properties, decoded.properties)
    }

    @Test
    fun connectFull() {
        val willPayload = byteArrayOf(0x01, 0x02, 0x03)
        val password = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        val packet =
            Connect(
                cleanStart = false,
                keepAliveSeconds = 300,
                clientId = "test-client-123",
                willTopic = "will/topic",
                willPayload = willPayload,
                willQos = QoS.EXACTLY_ONCE,
                willRetain = true,
                willProperties =
                    MqttProperties(
                        willDelayInterval = 60L,
                    ),
                username = "user",
                password = password,
                properties =
                    MqttProperties(
                        sessionExpiryInterval = 3600L,
                        receiveMaximum = 20,
                    ),
            )
        val decoded = roundTrip(packet) as Connect
        assertEquals(packet.protocolName, decoded.protocolName)
        assertEquals(packet.protocolLevel, decoded.protocolLevel)
        assertEquals(packet.cleanStart, decoded.cleanStart)
        assertEquals(packet.keepAliveSeconds, decoded.keepAliveSeconds)
        assertEquals(packet.clientId, decoded.clientId)
        assertEquals(packet.willTopic, decoded.willTopic)
        assertContentEquals(packet.willPayload, decoded.willPayload)
        assertEquals(packet.willQos, decoded.willQos)
        assertEquals(packet.willRetain, decoded.willRetain)
        assertEquals(packet.willProperties, decoded.willProperties)
        assertEquals(packet.username, decoded.username)
        assertContentEquals(packet.password, decoded.password)
        assertEquals(packet.properties, decoded.properties)
    }

    @Test
    fun connectWithProperties() {
        val packet =
            Connect(
                clientId = "prop-client",
                properties =
                    MqttProperties(
                        sessionExpiryInterval = 0xFFFFFFFFL,
                        authenticationMethod = "SCRAM-SHA-256",
                        authenticationData = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
                        requestProblemInformation = true,
                        requestResponseInformation = true,
                        userProperties = listOf("key" to "value"),
                    ),
            )
        val decoded = roundTrip(packet) as Connect
        assertEquals(packet.properties, decoded.properties)
    }

    @Test
    fun connectFixedHeaderByte() {
        val bytes = Connect(clientId = "").encode()
        // First byte: type 1 shifted left 4 = 0x10, flags 0
        assertEquals(0x10, bytes[0].toInt() and 0xFF)
    }

    // ===== CONNACK (§3.2) =====

    @Test
    fun connackMinimal() {
        val packet = ConnAck()
        val decoded = roundTrip(packet) as ConnAck
        assertEquals(packet, decoded)
    }

    @Test
    fun connackSessionPresent() {
        val packet = ConnAck(sessionPresent = true, reasonCode = ReasonCode.SUCCESS)
        val decoded = roundTrip(packet) as ConnAck
        assertEquals(packet, decoded)
    }

    @Test
    fun connackWithProperties() {
        val packet =
            ConnAck(
                reasonCode = ReasonCode.NOT_AUTHORIZED,
                properties =
                    MqttProperties(
                        reasonString = "Not authorized",
                        serverReference = "other-server:1883",
                        maximumQos = 1,
                        retainAvailable = false,
                        maximumPacketSize = 1_048_576L,
                        topicAliasMaximum = 10,
                    ),
            )
        val decoded = roundTrip(packet) as ConnAck
        assertEquals(packet, decoded)
    }

    // ===== PUBLISH (§3.3) =====

    @Test
    fun publishQos0() {
        val packet = Publish(topicName = "test/topic", payload = "hello".encodeToByteArray())
        val decoded = roundTrip(packet) as Publish
        assertEquals(packet.topicName, decoded.topicName)
        assertEquals(packet.qos, decoded.qos)
        assertEquals(packet.packetIdentifier, decoded.packetIdentifier)
        assertContentEquals(packet.payload, decoded.payload)
    }

    @Test
    fun publishQos1WithPayload() {
        val packet =
            Publish(
                qos = QoS.AT_LEAST_ONCE,
                topicName = "sensor/data",
                packetIdentifier = 42,
                payload = byteArrayOf(0x01, 0x02, 0x03),
            )
        val decoded = roundTrip(packet) as Publish
        assertEquals(packet.topicName, decoded.topicName)
        assertEquals(packet.qos, decoded.qos)
        assertEquals(packet.packetIdentifier, decoded.packetIdentifier)
        assertContentEquals(packet.payload, decoded.payload)
    }

    @Test
    fun publishQos2WithDup() {
        val packet =
            Publish(
                dup = true,
                qos = QoS.EXACTLY_ONCE,
                retain = true,
                topicName = "important/msg",
                packetIdentifier = 65535,
                payload = byteArrayOf(0xFF.toByte()),
            )
        val decoded = roundTrip(packet) as Publish
        assertEquals(packet.dup, decoded.dup)
        assertEquals(packet.qos, decoded.qos)
        assertEquals(packet.retain, decoded.retain)
        assertEquals(packet.packetIdentifier, decoded.packetIdentifier)
        assertContentEquals(packet.payload, decoded.payload)
    }

    @Test
    fun publishEmptyPayload() {
        val packet = Publish(topicName = "empty")
        val decoded = roundTrip(packet) as Publish
        assertContentEquals(byteArrayOf(), decoded.payload)
    }

    @Test
    fun publishWithProperties() {
        val packet =
            Publish(
                topicName = "props/test",
                payload = "data".encodeToByteArray(),
                properties =
                    MqttProperties(
                        payloadFormatIndicator = true,
                        messageExpiryInterval = 3600L,
                        contentType = "application/json",
                        responseTopic = "reply/topic",
                        correlationData = byteArrayOf(0x01, 0x02),
                        topicAlias = 5,
                        userProperties = listOf("x-trace" to "abc123"),
                    ),
            )
        val decoded = roundTrip(packet) as Publish
        assertEquals(packet.properties, decoded.properties)
    }

    @Test
    fun publishFixedHeaderFlags() {
        // QoS 1, DUP=true, RETAIN=true → flags = 1011 = 0x0B
        val bytes =
            Publish(
                dup = true,
                qos = QoS.AT_LEAST_ONCE,
                retain = true,
                topicName = "t",
                packetIdentifier = 1,
            ).encode()
        val flags = bytes[0].toInt() and 0x0F
        assertEquals(0x0B, flags)
    }

    // ===== PUBACK (§3.4) =====

    @Test
    fun pubackMinimal() {
        val packet = PubAck(packetIdentifier = 1)
        val decoded = roundTrip(packet) as PubAck
        assertEquals(packet, decoded)
    }

    @Test
    fun pubackShortForm() {
        // Short form: just packet ID (2 bytes remaining length)
        val bytes = PubAck(packetIdentifier = 100).encode()
        // Remaining length should be 2
        assertEquals(2, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun pubackWithReasonCode() {
        val packet = PubAck(packetIdentifier = 42, reasonCode = ReasonCode.NO_MATCHING_SUBSCRIBERS)
        val decoded = roundTrip(packet) as PubAck
        assertEquals(packet, decoded)
    }

    @Test
    fun pubackWithProperties() {
        val packet =
            PubAck(
                packetIdentifier = 1000,
                reasonCode = ReasonCode.UNSPECIFIED_ERROR,
                properties = MqttProperties(reasonString = "failed"),
            )
        val decoded = roundTrip(packet) as PubAck
        assertEquals(packet, decoded)
    }

    // ===== PUBREC (§3.5) =====

    @Test
    fun pubrecMinimal() {
        val packet = PubRec(packetIdentifier = 5)
        val decoded = roundTrip(packet) as PubRec
        assertEquals(packet, decoded)
    }

    @Test
    fun pubrecWithReasonCode() {
        val packet = PubRec(packetIdentifier = 200, reasonCode = ReasonCode.QUOTA_EXCEEDED)
        val decoded = roundTrip(packet) as PubRec
        assertEquals(packet, decoded)
    }

    @Test
    fun pubrecWithProperties() {
        val packet =
            PubRec(
                packetIdentifier = 99,
                reasonCode = ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR,
                properties = MqttProperties(reasonString = "oops"),
            )
        val decoded = roundTrip(packet) as PubRec
        assertEquals(packet, decoded)
    }

    // ===== PUBREL (§3.6) =====

    @Test
    fun pubrelMinimal() {
        val packet = PubRel(packetIdentifier = 7)
        val decoded = roundTrip(packet) as PubRel
        assertEquals(packet, decoded)
    }

    @Test
    fun pubrelFixedHeaderFlags() {
        // PUBREL must have flags 0b0010
        val bytes = PubRel(packetIdentifier = 1).encode()
        val flags = bytes[0].toInt() and 0x0F
        assertEquals(0x02, flags)
    }

    @Test
    fun pubrelWithReasonCode() {
        val packet = PubRel(packetIdentifier = 300, reasonCode = ReasonCode.PACKET_IDENTIFIER_NOT_FOUND)
        val decoded = roundTrip(packet) as PubRel
        assertEquals(packet, decoded)
    }

    // ===== PUBCOMP (§3.7) =====

    @Test
    fun pubcompMinimal() {
        val packet = PubComp(packetIdentifier = 10)
        val decoded = roundTrip(packet) as PubComp
        assertEquals(packet, decoded)
    }

    @Test
    fun pubcompWithReasonCode() {
        val packet = PubComp(packetIdentifier = 500, reasonCode = ReasonCode.PACKET_IDENTIFIER_NOT_FOUND)
        val decoded = roundTrip(packet) as PubComp
        assertEquals(packet, decoded)
    }

    // ===== SUBSCRIBE (§3.8) =====

    @Test
    fun subscribeSingle() {
        val packet =
            Subscribe(
                packetIdentifier = 1,
                subscriptions =
                    listOf(
                        Subscription(topicFilter = "test/#"),
                    ),
            )
        val decoded = roundTrip(packet) as Subscribe
        assertEquals(packet, decoded)
    }

    @Test
    fun subscribeMultiple() {
        val packet =
            Subscribe(
                packetIdentifier = 42,
                subscriptions =
                    listOf(
                        Subscription(
                            topicFilter = "sensor/+/temp",
                            maxQos = QoS.EXACTLY_ONCE,
                            noLocal = true,
                            retainAsPublished = true,
                            retainHandling = RetainHandling.DO_NOT_SEND,
                        ),
                        Subscription(
                            topicFilter = "status/#",
                            maxQos = QoS.AT_LEAST_ONCE,
                            retainHandling = RetainHandling.SEND_IF_NEW_SUBSCRIPTION,
                        ),
                    ),
            )
        val decoded = roundTrip(packet) as Subscribe
        assertEquals(packet.packetIdentifier, decoded.packetIdentifier)
        assertEquals(packet.subscriptions, decoded.subscriptions)
    }

    @Test
    fun subscribeFixedHeaderFlags() {
        val bytes =
            Subscribe(
                packetIdentifier = 1,
                subscriptions = listOf(Subscription(topicFilter = "t")),
            ).encode()
        val flags = bytes[0].toInt() and 0x0F
        assertEquals(0x02, flags)
    }

    @Test
    fun subscribeWithProperties() {
        val packet =
            Subscribe(
                packetIdentifier = 10,
                subscriptions = listOf(Subscription(topicFilter = "sub/#")),
                properties =
                    MqttProperties(
                        subscriptionIdentifier = listOf(1),
                        userProperties = listOf("sub-key" to "sub-val"),
                    ),
            )
        val decoded = roundTrip(packet) as Subscribe
        assertEquals(packet.properties, decoded.properties)
    }

    // ===== SUBACK (§3.9) =====

    @Test
    fun subackSingle() {
        val packet = SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS))
        val decoded = roundTrip(packet) as SubAck
        assertEquals(packet, decoded)
    }

    @Test
    fun subackMultiple() {
        val packet =
            SubAck(
                packetIdentifier = 42,
                reasonCodes =
                    listOf(
                        ReasonCode.GRANTED_QOS_1,
                        ReasonCode.GRANTED_QOS_2,
                        ReasonCode.UNSPECIFIED_ERROR,
                    ),
            )
        val decoded = roundTrip(packet) as SubAck
        assertEquals(packet, decoded)
    }

    @Test
    fun subackWithProperties() {
        val packet =
            SubAck(
                packetIdentifier = 5,
                reasonCodes = listOf(ReasonCode.SUCCESS),
                properties = MqttProperties(reasonString = "OK"),
            )
        val decoded = roundTrip(packet) as SubAck
        assertEquals(packet, decoded)
    }

    // ===== UNSUBSCRIBE (§3.10) =====

    @Test
    fun unsubscribeSingle() {
        val packet = Unsubscribe(packetIdentifier = 1, topicFilters = listOf("test/#"))
        val decoded = roundTrip(packet) as Unsubscribe
        assertEquals(packet, decoded)
    }

    @Test
    fun unsubscribeMultiple() {
        val packet = Unsubscribe(packetIdentifier = 55, topicFilters = listOf("a/b", "c/d", "e/f"))
        val decoded = roundTrip(packet) as Unsubscribe
        assertEquals(packet, decoded)
    }

    @Test
    fun unsubscribeFixedHeaderFlags() {
        val bytes = Unsubscribe(packetIdentifier = 1, topicFilters = listOf("t")).encode()
        val flags = bytes[0].toInt() and 0x0F
        assertEquals(0x02, flags)
    }

    // ===== UNSUBACK (§3.11) =====

    @Test
    fun unsubackSingle() {
        val packet = UnsubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS))
        val decoded = roundTrip(packet) as UnsubAck
        assertEquals(packet, decoded)
    }

    @Test
    fun unsubackMultiple() {
        val packet =
            UnsubAck(
                packetIdentifier = 77,
                reasonCodes =
                    listOf(
                        ReasonCode.SUCCESS,
                        ReasonCode.NO_SUBSCRIPTION_EXISTED,
                    ),
            )
        val decoded = roundTrip(packet) as UnsubAck
        assertEquals(packet, decoded)
    }

    // ===== PINGREQ (§3.12) =====

    @Test
    fun pingReqEncodeAndDecode() {
        val bytes = PingReq.encode()
        assertEquals(2, bytes.size)
        assertEquals(0xC0.toByte(), bytes[0])
        assertEquals(0x00, bytes[1].toInt())
        val decoded = decodePacket(bytes)
        assertEquals(PingReq, decoded)
    }

    // ===== PINGRESP (§3.13) =====

    @Test
    fun pingRespEncodeAndDecode() {
        val bytes = PingResp.encode()
        assertEquals(2, bytes.size)
        assertEquals(0xD0.toByte(), bytes[0])
        assertEquals(0x00, bytes[1].toInt())
        val decoded = decodePacket(bytes)
        assertEquals(PingResp, decoded)
    }

    // ===== DISCONNECT (§3.14) =====

    @Test
    fun disconnectMinimal() {
        val packet = Disconnect()
        val bytes = packet.encode()
        // Short form: remaining length 0
        assertEquals(2, bytes.size)
        assertEquals(0xE0.toByte(), bytes[0])
        assertEquals(0x00, bytes[1].toInt())
        val decoded = roundTrip(packet) as Disconnect
        assertEquals(packet, decoded)
    }

    @Test
    fun disconnectWithReasonCode() {
        val packet = Disconnect(reasonCode = ReasonCode.SERVER_SHUTTING_DOWN)
        val decoded = roundTrip(packet) as Disconnect
        assertEquals(packet, decoded)
    }

    @Test
    fun disconnectWithProperties() {
        val packet =
            Disconnect(
                reasonCode = ReasonCode.SESSION_TAKEN_OVER,
                properties =
                    MqttProperties(
                        reasonString = "Another client connected",
                        sessionExpiryInterval = 0L,
                    ),
            )
        val decoded = roundTrip(packet) as Disconnect
        assertEquals(packet, decoded)
    }

    // ===== AUTH (§3.15) =====

    @Test
    fun authWithReasonCodeAndProperties() {
        val packet =
            Auth(
                reasonCode = ReasonCode.CONTINUE_AUTHENTICATION,
                properties =
                    MqttProperties(
                        authenticationMethod = "SCRAM-SHA-256",
                        authenticationData = byteArrayOf(0x01, 0x02, 0x03),
                    ),
            )
        val decoded = roundTrip(packet) as Auth
        assertEquals(packet.reasonCode, decoded.reasonCode)
        assertEquals(packet.properties, decoded.properties)
    }

    @Test
    fun authSuccess() {
        val packet = Auth(reasonCode = ReasonCode.SUCCESS)
        val decoded = roundTrip(packet) as Auth
        assertEquals(packet.reasonCode, decoded.reasonCode)
    }

    // ===== Edge case tests =====

    @Test
    fun truncatedPacketThrows() {
        // Just a fixed header byte with no remaining length
        assertFailsWith<IllegalArgumentException> {
            decodePacket(byteArrayOf())
        }
    }

    @Test
    fun wrongRemainingLengthThrows() {
        // PINGREQ with remaining length 5 but no body bytes
        val bytes = byteArrayOf(0xC0.toByte(), 0x05)
        assertFailsWith<IllegalArgumentException> {
            decodePacket(bytes)
        }
    }

    @Test
    fun invalidFlagsThrows() {
        // CONNACK (type 2) with wrong flags (0x01 instead of 0x00)
        // Build: type=2, flags=1 → 0x21, remaining length=3, body=3 bytes
        val bytes = byteArrayOf(0x21, 0x03, 0x00, 0x00, 0x00)
        assertFailsWith<IllegalArgumentException> {
            decodePacket(bytes)
        }
    }

    @Test
    fun utf8MultiByteTopic() {
        val packet = Publish(topicName = "sensor/温度/data", payload = "日本語".encodeToByteArray())
        val decoded = roundTrip(packet) as Publish
        assertEquals(packet.topicName, decoded.topicName)
        assertContentEquals(packet.payload, decoded.payload)
    }

    @Test
    fun connectEmptyWillPayload() {
        val packet =
            Connect(
                clientId = "will-test",
                willTopic = "will/t",
                willPayload = byteArrayOf(),
            )
        val decoded = roundTrip(packet) as Connect
        assertEquals(packet.willTopic, decoded.willTopic)
        assertContentEquals(byteArrayOf(), decoded.willPayload)
    }

    @Test
    fun publishMaxPacketIdentifier() {
        val packet =
            Publish(
                qos = QoS.AT_LEAST_ONCE,
                topicName = "max-id",
                packetIdentifier = 65535,
            )
        val decoded = roundTrip(packet) as Publish
        assertEquals(65535, decoded.packetIdentifier)
    }

    @Test
    fun pubAckLikeRoundTripAllTypes() {
        // Verify all four PubAck-like types decode back correctly
        val types =
            listOf(
                PubAck(packetIdentifier = 1, reasonCode = ReasonCode.SUCCESS),
                PubRec(packetIdentifier = 2, reasonCode = ReasonCode.SUCCESS),
                PubRel(packetIdentifier = 3, reasonCode = ReasonCode.SUCCESS),
                PubComp(packetIdentifier = 4, reasonCode = ReasonCode.SUCCESS),
            )
        for (packet in types) {
            val decoded = roundTrip(packet)
            assertEquals(packet, decoded)
        }
    }

    @Test
    fun connectNoCleanStart() {
        val packet = Connect(cleanStart = false, clientId = "resuming")
        val decoded = roundTrip(packet) as Connect
        assertEquals(false, decoded.cleanStart)
    }

    @Test
    fun publishRetainOnly() {
        val packet = Publish(retain = true, topicName = "retained")
        val decoded = roundTrip(packet) as Publish
        assertTrue(decoded.retain)
        assertEquals(QoS.AT_MOST_ONCE, decoded.qos)
    }

    @Test
    fun subscriptionOptionsRoundTrip() {
        // All retain handling values
        for (rh in RetainHandling.entries) {
            val packet =
                Subscribe(
                    packetIdentifier = 1,
                    subscriptions =
                        listOf(
                            Subscription(
                                topicFilter = "test",
                                maxQos = QoS.EXACTLY_ONCE,
                                noLocal = true,
                                retainAsPublished = true,
                                retainHandling = rh,
                            ),
                        ),
                )
            val decoded = roundTrip(packet) as Subscribe
            assertEquals(rh, decoded.subscriptions[0].retainHandling)
        }
    }

    @Test
    fun emptyPropertiesVsNonEmpty() {
        // Packet with EMPTY properties
        val minimal = ConnAck()
        val minBytes = minimal.encode()
        val minDecoded = roundTrip(minimal) as ConnAck
        assertEquals(MqttProperties.EMPTY, minDecoded.properties)

        // Packet with non-empty properties
        val withProps = ConnAck(properties = MqttProperties(receiveMaximum = 100))
        val decoded = roundTrip(withProps) as ConnAck
        assertEquals(100, decoded.properties.receiveMaximum)
        assertTrue(minBytes.size < withProps.encode().size)
    }

    @Test
    fun disconnectSuccessShortForm() {
        // SUCCESS + EMPTY properties should produce 0-byte body
        val bytes = Disconnect().encode()
        assertEquals(2, bytes.size)
    }

    @Test
    fun disconnectNonSuccessReasonCodeOnly() {
        // Non-SUCCESS reason code with EMPTY properties
        val packet = Disconnect(reasonCode = ReasonCode.UNSPECIFIED_ERROR)
        val decoded = roundTrip(packet) as Disconnect
        assertEquals(ReasonCode.UNSPECIFIED_ERROR, decoded.reasonCode)
        assertEquals(MqttProperties.EMPTY, decoded.properties)
    }

    // ===== Reserved Bits Validation (§3.1.2.3, §3.2.2.1, §3.8.3.1) =====

    @Test
    fun connectFlagsBit0MustBeZero() {
        // Build a valid CONNECT, then corrupt bit 0 of the Connect Flags byte
        val validConnect = Connect(clientId = "test")
        val bytes = validConnect.encode().copyOf()
        // Connect Flags byte is at: fixed header (2 bytes) + protocol name (6) + protocol level (1) = offset 9
        val flagsOffset = 9
        bytes[flagsOffset] = (bytes[flagsOffset].toInt() or 0x01).toByte()
        assertFailsWith<IllegalArgumentException>("Bit 0 of Connect Flags") {
            decodePacket(bytes)
        }
    }

    @Test
    fun connackReservedBitsMustBeZero() {
        // Build a CONNACK with reserved bits set in the acknowledge flags byte
        // CONNACK fixed header: 0x20, remaining length, then ack flags, reason code, properties
        val bytes =
            byteArrayOf(
                0x20, // CONNACK type
                0x03, // remaining length: 3
                0x02, // acknowledge flags: bit 1 set (reserved - should be rejected)
                0x00, // reason code: SUCCESS
                0x00, // properties length: 0
            )
        assertFailsWith<IllegalArgumentException>("Reserved bits in Connect Acknowledge Flags") {
            decodePacket(bytes)
        }
    }

    @Test
    fun subscriptionOptionsReservedBitsMustBeZero() {
        // Build a SUBSCRIBE with reserved bits 6-7 set in subscription options
        val validSub =
            Subscribe(
                packetIdentifier = 1,
                subscriptions = listOf(Subscription(topicFilter = "test")),
            )
        val bytes = validSub.encode().copyOf()
        // The options byte is the last byte before properties end — find it
        // Set bits 6-7 on the last byte (the subscription options byte)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() or 0xC0).toByte()
        assertFailsWith<IllegalArgumentException>("Reserved bits 6-7") {
            decodePacket(bytes)
        }
    }

    // ===== Utility =====

    private fun roundTrip(packet: org.meshtastic.mqtt.packet.MqttPacket): org.meshtastic.mqtt.packet.MqttPacket {
        val bytes = packet.encode()
        return decodePacket(bytes)
    }
}
