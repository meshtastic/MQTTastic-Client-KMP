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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import org.meshtastic.mqtt.packet.connAckReturnCodeFromReasonCode
import org.meshtastic.mqtt.packet.decodePacket
import org.meshtastic.mqtt.packet.encode
import org.meshtastic.mqtt.packet.reasonCodeFromConnAckReturnCode
import org.meshtastic.mqtt.packet.reasonCodeFromSubAckReturnCode
import org.meshtastic.mqtt.packet.subAckReturnCodeFromReasonCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for MQTT 3.1.1 protocol support.
 *
 * Covers:
 * - Encode/decode round-trips for all packet types under V3_1_1
 * - CONNACK and SUBACK return code mapping
 * - Connection-level state machine with FakeTransport
 * - Config validation for 3.1.1-incompatible options
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class Mqtt311Test {
    private val v311 = MqttProtocolVersion.V3_1_1
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    private fun v311Config(
        keepAliveSeconds: Int = 0,
        cleanStart: Boolean = true,
        clientId: String = "test-client",
    ): MqttConfig =
        MqttConfig(
            protocolVersion = MqttProtocolVersion.V3_1_1,
            clientId = clientId,
            keepAliveSeconds = keepAliveSeconds,
            cleanStart = cleanStart,
        )

    private fun roundTrip311(packet: org.meshtastic.mqtt.packet.MqttPacket): org.meshtastic.mqtt.packet.MqttPacket {
        val bytes = packet.encode(v311)
        return decodePacket(bytes, v311)
    }

    // ===== CONNECT (§3.1) =====

    @Test
    fun connectMinimal311() {
        val packet = Connect(protocolLevel = 4, clientId = "")
        val decoded = roundTrip311(packet) as Connect
        assertEquals("MQTT", decoded.protocolName)
        assertEquals(4, decoded.protocolLevel)
        assertEquals(true, decoded.cleanStart)
        assertEquals("", decoded.clientId)
    }

    @Test
    fun connectFull311() {
        val willPayload = byteArrayOf(0x01, 0x02, 0x03)
        val password = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        val packet =
            Connect(
                protocolLevel = 4,
                cleanStart = false,
                keepAliveSeconds = 300,
                clientId = "test-client-311",
                willTopic = "will/topic",
                willPayload = willPayload,
                willQos = QoS.EXACTLY_ONCE,
                willRetain = true,
                username = "user",
                password = password,
            )
        val decoded = roundTrip311(packet) as Connect
        assertEquals(4, decoded.protocolLevel)
        assertEquals(false, decoded.cleanStart)
        assertEquals(300, decoded.keepAliveSeconds)
        assertEquals("test-client-311", decoded.clientId)
        assertEquals("will/topic", decoded.willTopic)
        assertContentEquals(willPayload, decoded.willPayload)
        assertEquals(QoS.EXACTLY_ONCE, decoded.willQos)
        assertEquals(true, decoded.willRetain)
        assertEquals("user", decoded.username)
        assertContentEquals(password, decoded.password)
    }

    @Test
    fun connectV311HasNoProperties() {
        val packet =
            Connect(
                protocolLevel = 4,
                clientId = "test",
                properties = MqttProperties(sessionExpiryInterval = 999L),
            )
        val bytes = packet.encode(v311)
        val decoded = decodePacket(bytes, v311) as Connect
        // Properties should be empty — 3.1.1 doesn't encode them
        assertEquals(MqttProperties.EMPTY, decoded.properties)
    }

    // ===== CONNACK (§3.2) =====

    @Test
    fun connAck311Success() {
        val packet = ConnAck(sessionPresent = false, reasonCode = ReasonCode.SUCCESS)
        val decoded = roundTrip311(packet) as ConnAck
        assertEquals(false, decoded.sessionPresent)
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCode)
    }

    @Test
    fun connAck311SessionPresent() {
        val packet = ConnAck(sessionPresent = true, reasonCode = ReasonCode.SUCCESS)
        val decoded = roundTrip311(packet) as ConnAck
        assertEquals(true, decoded.sessionPresent)
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCode)
    }

    @Test
    fun connAck311ReturnCodes() {
        // Test all 3.1.1 CONNACK return codes
        val cases =
            listOf(
                ReasonCode.SUCCESS to ReasonCode.SUCCESS,
                ReasonCode.UNSUPPORTED_PROTOCOL_VERSION to ReasonCode.UNSUPPORTED_PROTOCOL_VERSION,
                ReasonCode.CLIENT_IDENTIFIER_NOT_VALID to ReasonCode.CLIENT_IDENTIFIER_NOT_VALID,
                ReasonCode.SERVER_UNAVAILABLE to ReasonCode.SERVER_UNAVAILABLE,
                ReasonCode.BAD_USER_NAME_OR_PASSWORD to ReasonCode.BAD_USER_NAME_OR_PASSWORD,
                ReasonCode.NOT_AUTHORIZED to ReasonCode.NOT_AUTHORIZED,
            )
        for ((input, expected) in cases) {
            val packet = ConnAck(reasonCode = input)
            val decoded = roundTrip311(packet) as ConnAck
            assertEquals(expected, decoded.reasonCode, "CONNACK return code for $input")
        }
    }

    // ===== PUBLISH (§3.3) =====

    @Test
    fun publishQos0V311() {
        val packet =
            Publish(
                topicName = "test/topic",
                payload = byteArrayOf(1, 2, 3),
                qos = QoS.AT_MOST_ONCE,
                retain = false,
            )
        val decoded = roundTrip311(packet) as Publish
        assertEquals("test/topic", decoded.topicName)
        assertContentEquals(byteArrayOf(1, 2, 3), decoded.payload)
        assertEquals(QoS.AT_MOST_ONCE, decoded.qos)
        assertEquals(MqttProperties.EMPTY, decoded.properties)
    }

    @Test
    fun publishQos1V311() {
        val packet =
            Publish(
                topicName = "test/topic",
                payload = byteArrayOf(0xAB.toByte()),
                qos = QoS.AT_LEAST_ONCE,
                packetIdentifier = 42,
            )
        val decoded = roundTrip311(packet) as Publish
        assertEquals("test/topic", decoded.topicName)
        assertEquals(QoS.AT_LEAST_ONCE, decoded.qos)
        assertEquals(42, decoded.packetIdentifier)
    }

    @Test
    fun publishQos2V311() {
        val packet =
            Publish(
                topicName = "important",
                payload = byteArrayOf(),
                qos = QoS.EXACTLY_ONCE,
                packetIdentifier = 1000,
                retain = true,
            )
        val decoded = roundTrip311(packet) as Publish
        assertEquals(QoS.EXACTLY_ONCE, decoded.qos)
        assertEquals(1000, decoded.packetIdentifier)
        assertEquals(true, decoded.retain)
    }

    // ===== PUBACK (§3.4), PUBREC (§3.5), PUBREL (§3.6), PUBCOMP (§3.7) =====

    @Test
    fun pubAckV311() {
        val packet = PubAck(packetIdentifier = 123)
        val decoded = roundTrip311(packet) as PubAck
        assertEquals(123, decoded.packetIdentifier)
        // In 3.1.1 there is no reason code — decoder defaults to SUCCESS
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCode)
    }

    @Test
    fun pubRecV311() {
        val packet = PubRec(packetIdentifier = 456)
        val decoded = roundTrip311(packet) as PubRec
        assertEquals(456, decoded.packetIdentifier)
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCode)
    }

    @Test
    fun pubRelV311() {
        val packet = PubRel(packetIdentifier = 789)
        val decoded = roundTrip311(packet) as PubRel
        assertEquals(789, decoded.packetIdentifier)
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCode)
    }

    @Test
    fun pubCompV311() {
        val packet = PubComp(packetIdentifier = 101)
        val decoded = roundTrip311(packet) as PubComp
        assertEquals(101, decoded.packetIdentifier)
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCode)
    }

    // ===== SUBSCRIBE (§3.8) =====

    @Test
    fun subscribeV311() {
        val packet =
            Subscribe(
                packetIdentifier = 10,
                subscriptions = listOf(Subscription("test/#", QoS.AT_LEAST_ONCE)),
            )
        val decoded = roundTrip311(packet) as Subscribe
        assertEquals(10, decoded.packetIdentifier)
        assertEquals(1, decoded.subscriptions.size)
        assertEquals("test/#", decoded.subscriptions[0].topicFilter)
        assertEquals(QoS.AT_LEAST_ONCE, decoded.subscriptions[0].maxQos)
    }

    @Test
    fun subscribeMultipleTopicsV311() {
        val packet =
            Subscribe(
                packetIdentifier = 20,
                subscriptions =
                    listOf(
                        Subscription("a/b", QoS.AT_MOST_ONCE),
                        Subscription("c/d", QoS.EXACTLY_ONCE),
                    ),
            )
        val decoded = roundTrip311(packet) as Subscribe
        assertEquals(2, decoded.subscriptions.size)
        assertEquals(QoS.AT_MOST_ONCE, decoded.subscriptions[0].maxQos)
        assertEquals(QoS.EXACTLY_ONCE, decoded.subscriptions[1].maxQos)
    }

    // ===== SUBACK (§3.9) =====

    @Test
    fun subAckV311() {
        val packet =
            SubAck(
                packetIdentifier = 10,
                reasonCodes = listOf(ReasonCode.SUCCESS, ReasonCode.GRANTED_QOS_1),
            )
        val decoded = roundTrip311(packet) as SubAck
        assertEquals(10, decoded.packetIdentifier)
        assertEquals(2, decoded.reasonCodes.size)
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCodes[0])
        assertEquals(ReasonCode.GRANTED_QOS_1, decoded.reasonCodes[1])
    }

    @Test
    fun subAckFailureV311() {
        val packet =
            SubAck(
                packetIdentifier = 11,
                reasonCodes = listOf(ReasonCode.UNSPECIFIED_ERROR),
            )
        val decoded = roundTrip311(packet) as SubAck
        // 0x80 on wire → UNSPECIFIED_ERROR
        assertEquals(ReasonCode.UNSPECIFIED_ERROR, decoded.reasonCodes[0])
    }

    // ===== UNSUBSCRIBE (§3.10) =====

    @Test
    fun unsubscribeV311() {
        val packet =
            Unsubscribe(
                packetIdentifier = 30,
                topicFilters = listOf("a/b", "c/d"),
            )
        val decoded = roundTrip311(packet) as Unsubscribe
        assertEquals(30, decoded.packetIdentifier)
        assertEquals(listOf("a/b", "c/d"), decoded.topicFilters)
    }

    // ===== UNSUBACK (§3.11) =====

    @Test
    fun unsubAckV311() {
        val packet = UnsubAck(packetIdentifier = 30, reasonCodes = listOf(ReasonCode.SUCCESS))
        val decoded = roundTrip311(packet) as UnsubAck
        assertEquals(30, decoded.packetIdentifier)
        // 3.1.1 UNSUBACK has no return codes; decoder synthesizes SUCCESS
        assertEquals(listOf(ReasonCode.SUCCESS), decoded.reasonCodes)
    }

    // ===== PINGREQ (§3.12) / PINGRESP (§3.13) =====

    @Test
    fun pingReqV311() {
        val packet = PingReq
        val decoded = roundTrip311(packet)
        assertIs<PingReq>(decoded)
    }

    @Test
    fun pingRespV311() {
        val packet = PingResp
        val decoded = roundTrip311(packet)
        assertIs<PingResp>(decoded)
    }

    // ===== DISCONNECT (§3.14) =====

    @Test
    fun disconnectV311() {
        val packet = Disconnect()
        val bytes = packet.encode(v311)
        // MQTT 3.1.1 DISCONNECT is just 0xE0 0x00
        assertEquals(2, bytes.size, "3.1.1 DISCONNECT should be exactly 2 bytes")
        assertEquals(0xE0.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        val decoded = decodePacket(bytes, v311) as Disconnect
        assertEquals(ReasonCode.SUCCESS, decoded.reasonCode)
    }

    // ===== AUTH: should throw =====

    @Test
    fun authThrowsForV311() {
        assertFailsWith<IllegalArgumentException> {
            org.meshtastic.mqtt.packet
                .Auth(reasonCode = ReasonCode.SUCCESS)
                .encode(v311)
        }
    }

    // ===== Return code mapping helpers =====

    @Test
    fun connAckReturnCodeMapping() {
        assertEquals(ReasonCode.SUCCESS, reasonCodeFromConnAckReturnCode(0))
        assertEquals(ReasonCode.UNSUPPORTED_PROTOCOL_VERSION, reasonCodeFromConnAckReturnCode(1))
        assertEquals(ReasonCode.CLIENT_IDENTIFIER_NOT_VALID, reasonCodeFromConnAckReturnCode(2))
        assertEquals(ReasonCode.SERVER_UNAVAILABLE, reasonCodeFromConnAckReturnCode(3))
        assertEquals(ReasonCode.BAD_USER_NAME_OR_PASSWORD, reasonCodeFromConnAckReturnCode(4))
        assertEquals(ReasonCode.NOT_AUTHORIZED, reasonCodeFromConnAckReturnCode(5))
        assertEquals(ReasonCode.UNSPECIFIED_ERROR, reasonCodeFromConnAckReturnCode(99))
    }

    @Test
    fun connAckReturnCodeFromReasonCodeMapping() {
        assertEquals(0, connAckReturnCodeFromReasonCode(ReasonCode.SUCCESS))
        assertEquals(1, connAckReturnCodeFromReasonCode(ReasonCode.UNSUPPORTED_PROTOCOL_VERSION))
        assertEquals(2, connAckReturnCodeFromReasonCode(ReasonCode.CLIENT_IDENTIFIER_NOT_VALID))
        assertEquals(3, connAckReturnCodeFromReasonCode(ReasonCode.SERVER_UNAVAILABLE))
        assertEquals(4, connAckReturnCodeFromReasonCode(ReasonCode.BAD_USER_NAME_OR_PASSWORD))
        assertEquals(5, connAckReturnCodeFromReasonCode(ReasonCode.NOT_AUTHORIZED))
    }

    @Test
    fun subAckReturnCodeMapping() {
        assertEquals(ReasonCode.SUCCESS, reasonCodeFromSubAckReturnCode(0x00))
        assertEquals(ReasonCode.GRANTED_QOS_1, reasonCodeFromSubAckReturnCode(0x01))
        assertEquals(ReasonCode.GRANTED_QOS_2, reasonCodeFromSubAckReturnCode(0x02))
        assertEquals(ReasonCode.UNSPECIFIED_ERROR, reasonCodeFromSubAckReturnCode(0x80))
        assertEquals(ReasonCode.UNSPECIFIED_ERROR, reasonCodeFromSubAckReturnCode(0xFF))
    }

    @Test
    fun subAckReturnCodeFromReasonCodeMapping() {
        assertEquals(0x00, subAckReturnCodeFromReasonCode(ReasonCode.SUCCESS))
        assertEquals(0x01, subAckReturnCodeFromReasonCode(ReasonCode.GRANTED_QOS_1))
        assertEquals(0x02, subAckReturnCodeFromReasonCode(ReasonCode.GRANTED_QOS_2))
        assertEquals(0x80, subAckReturnCodeFromReasonCode(ReasonCode.UNSPECIFIED_ERROR))
    }

    // ===== Config validation for 3.1.1 =====

    @Test
    fun configRejectsSessionExpiryForV311() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(
                protocolVersion = MqttProtocolVersion.V3_1_1,
                clientId = "test",
                sessionExpiryInterval = 60L,
            )
        }
    }

    @Test
    fun configRejectsAuthForV311() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(
                protocolVersion = MqttProtocolVersion.V3_1_1,
                clientId = "test",
                authenticationMethod = "SCRAM-SHA-256",
            )
        }
    }

    @Test
    fun configRejectsPasswordWithoutUsernameForV311() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(
                protocolVersion = MqttProtocolVersion.V3_1_1,
                clientId = "test",
                password = kotlinx.io.bytestring.ByteString("secret".encodeToByteArray()),
            )
        }
    }

    @Test
    fun configAllowsPasswordWithUsernameForV311() {
        val config =
            MqttConfig(
                protocolVersion = MqttProtocolVersion.V3_1_1,
                clientId = "test",
                username = "user",
                password = kotlinx.io.bytestring.ByteString("secret".encodeToByteArray()),
            )
        assertEquals(MqttProtocolVersion.V3_1_1, config.protocolVersion)
    }

    // ===== Connection-level tests =====

    @Test
    fun connectV311Success() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val connAck = connection.connect(endpoint)
            assertEquals(ReasonCode.SUCCESS, connAck.reasonCode)

            // Verify the sent CONNECT packet has protocolLevel 4
            val sentConnect = transport.decodeSentPackets().first() as Connect
            assertEquals(4, sentConnect.protocolLevel)
            assertEquals("MQTT", sentConnect.protocolName)

            connection.disconnect()
        }

    @Test
    fun connectV311Rejected() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.NOT_AUTHORIZED))

            assertFailsWith<MqttConnectionException> {
                connection.connect(endpoint)
            }
        }

    @Test
    fun publishQos1V311Connection() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            // Enqueue PUBACK for the publish
            transport.enqueuePacket(PubAck(packetIdentifier = 1))

            val reasonCode =
                connection.publish(
                    MqttMessage(
                        topic = "test/topic",
                        payload = kotlinx.io.bytestring.ByteString(byteArrayOf(1, 2, 3)),
                        qos = QoS.AT_LEAST_ONCE,
                    ),
                )
            assertEquals(ReasonCode.SUCCESS, reasonCode)

            connection.disconnect()
        }

    @Test
    fun subscribeV311Connection() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            transport.enqueuePacket(SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS)))

            val subAck =
                connection.subscribe(
                    listOf(Subscription("test/#", QoS.AT_LEAST_ONCE)),
                )
            assertEquals(1, subAck.reasonCodes.size)
            assertEquals(ReasonCode.SUCCESS, subAck.reasonCodes[0])

            connection.disconnect()
        }

    @Test
    fun subscribeV311RejectsNoLocal() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            assertFailsWith<IllegalArgumentException> {
                connection.subscribe(
                    listOf(Subscription("test/#", QoS.AT_LEAST_ONCE, noLocal = true)),
                )
            }

            connection.disconnect()
        }

    @Test
    fun subscribeV311RejectsRetainAsPublished() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            assertFailsWith<IllegalArgumentException> {
                connection.subscribe(
                    listOf(Subscription("test/#", QoS.AT_LEAST_ONCE, retainAsPublished = true)),
                )
            }

            connection.disconnect()
        }

    @Test
    fun subscribeV311RejectsRetainHandling() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            assertFailsWith<IllegalArgumentException> {
                connection.subscribe(
                    listOf(
                        Subscription(
                            "test/#",
                            QoS.AT_LEAST_ONCE,
                            retainHandling = RetainHandling.DO_NOT_SEND,
                        ),
                    ),
                )
            }

            connection.disconnect()
        }

    @Test
    fun disconnectV311SendsNoBodyPacket() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            connection.disconnect()

            // Find the DISCONNECT among sent packets (last packet before close)
            val sentBytes = transport.sentPackets.last()
            // MQTT 3.1.1 DISCONNECT is exactly 0xE0 0x00
            assertEquals(2, sentBytes.size)
            assertEquals(0xE0.toByte(), sentBytes[0])
            assertEquals(0x00.toByte(), sentBytes[1])
        }

    @Test
    fun receivePublishV311() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val config = v311Config()
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            val incomingJob =
                launch {
                    val msg = withTimeout(1000) { connection.incomingMessages.first() }
                    assertEquals("test/topic", msg.topic)
                    assertContentEquals(byteArrayOf(0x42), msg.payload.toByteArray())
                    assertEquals(QoS.AT_MOST_ONCE, msg.qos)
                }

            transport.enqueuePacket(
                Publish(
                    topicName = "test/topic",
                    payload = byteArrayOf(0x42),
                    qos = QoS.AT_MOST_ONCE,
                ),
            )

            advanceUntilIdle()
            incomingJob.join()

            connection.disconnect()
        }

    @Test
    fun protocolVersionEnum() {
        assertEquals(4, MqttProtocolVersion.V3_1_1.protocolLevel)
        assertEquals(5, MqttProtocolVersion.V5_0.protocolLevel)
        assertTrue(!MqttProtocolVersion.V3_1_1.supportsProperties)
        assertTrue(MqttProtocolVersion.V5_0.supportsProperties)
        assertEquals(MqttProtocolVersion.V3_1_1, MqttProtocolVersion.fromProtocolLevel(4))
        assertEquals(MqttProtocolVersion.V5_0, MqttProtocolVersion.fromProtocolLevel(5))
        assertFailsWith<IllegalArgumentException> { MqttProtocolVersion.fromProtocolLevel(3) }
    }

    // ===== Version negotiation =====

    @Test
    fun negotiateVersionDefaultIsTrue() {
        val config = MqttConfig(clientId = "test")
        assertEquals(true, config.negotiateVersion)
        assertEquals(MqttProtocolVersion.V5_0, config.protocolVersion)
    }

    @Test
    fun negotiateVersionCanBeDisabled() {
        val config = MqttConfig(clientId = "test", negotiateVersion = false)
        assertEquals(false, config.negotiateVersion)
    }

    @Test
    fun v311CompatibilityValidation() {
        // Should pass — no 5.0-only features
        val basic = MqttConfig(clientId = "test")
        basic.validateV311Compatibility()

        // Should fail — sessionExpiryInterval is 5.0-only
        val withExpiry = MqttConfig(clientId = "test", sessionExpiryInterval = 60L)
        assertFailsWith<IllegalArgumentException> {
            withExpiry.validateV311Compatibility()
        }

        // Should fail — enhanced auth is 5.0-only
        val withAuth = MqttConfig(clientId = "test", authenticationMethod = "SCRAM")
        assertFailsWith<IllegalArgumentException> {
            withAuth.validateV311Compatibility()
        }
    }

    @Test
    fun versionFallbackOnUnsupportedProtocol() =
        runTest {
            // Simulate: first transport rejects V5 with UNSUPPORTED_PROTOCOL_VERSION,
            // second transport accepts V3.1.1
            val v5Transport = FakeTransport(MqttProtocolVersion.V5_0)
            val v311Transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            val transports = mutableListOf(v5Transport, v311Transport)

            // Enqueue rejection for V5
            v5Transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.UNSUPPORTED_PROTOCOL_VERSION))
            // Enqueue success for V3.1.1
            v311Transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val config =
                MqttConfig(
                    clientId = "test-client",
                    keepAliveSeconds = 0,
                    negotiateVersion = true,
                )
            val client = MqttClient(config, this) { transports.removeAt(0) }

            client.connect(endpoint)
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, client.connectionState.value)
            assertEquals(MqttProtocolVersion.V3_1_1, client.negotiatedProtocolVersion)

            // Verify the V3.1.1 CONNECT used protocolLevel 4
            val sentConnect = v311Transport.decodeSentPackets().first() as Connect
            assertEquals(4, sentConnect.protocolLevel)

            client.close()
        }

    @Test
    fun noFallbackWhenNegotiateVersionDisabled() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V5_0)
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.UNSUPPORTED_PROTOCOL_VERSION))

            val config =
                MqttConfig(
                    clientId = "test-client",
                    keepAliveSeconds = 0,
                    negotiateVersion = false,
                )
            val client = MqttClient(config, transport, this)

            assertFailsWith<MqttException.ConnectionRejected> {
                client.connect(endpoint)
            }
            assertEquals(MqttProtocolVersion.V5_0, client.negotiatedProtocolVersion)

            client.close()
        }

    @Test
    fun noFallbackWhenAlreadyV311() =
        runTest {
            val transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.UNSUPPORTED_PROTOCOL_VERSION))

            val config = v311Config()
            val client = MqttClient(config, transport, this)

            assertFailsWith<MqttException.ConnectionRejected> {
                client.connect(endpoint)
            }

            client.close()
        }

    @Test
    fun noFallbackWhenIncompatibleConfig() =
        runTest {
            val v5Transport = FakeTransport(MqttProtocolVersion.V5_0)
            v5Transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.UNSUPPORTED_PROTOCOL_VERSION))

            // Config has sessionExpiryInterval — can't fall back to 3.1.1
            val config =
                MqttConfig(
                    clientId = "test-client",
                    keepAliveSeconds = 0,
                    negotiateVersion = true,
                    sessionExpiryInterval = 60L,
                )
            val client = MqttClient(config, v5Transport, this)

            assertFailsWith<MqttException.ConnectionRejected> {
                client.connect(endpoint)
            }
            // Should still be V5 — fallback was not attempted
            assertEquals(MqttProtocolVersion.V5_0, client.negotiatedProtocolVersion)

            client.close()
        }
}
