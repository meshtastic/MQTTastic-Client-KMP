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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.Connect
import org.meshtastic.mqtt.packet.Disconnect
import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.PubAck
import org.meshtastic.mqtt.packet.PubComp
import org.meshtastic.mqtt.packet.PubRec
import org.meshtastic.mqtt.packet.PubRel
import org.meshtastic.mqtt.packet.Publish
import org.meshtastic.mqtt.packet.ReasonCode
import org.meshtastic.mqtt.packet.SubAck
import org.meshtastic.mqtt.packet.Subscribe
import org.meshtastic.mqtt.packet.Subscription
import org.meshtastic.mqtt.packet.UnsubAck
import org.meshtastic.mqtt.packet.Unsubscribe
import org.meshtastic.mqtt.packet.decodePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class MqttConnectionTest {
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    private fun defaultConfig(
        keepAliveSeconds: Int = 0,
        cleanStart: Boolean = true,
        clientId: String = "test-client",
    ): MqttConfig =
        MqttConfig(
            clientId = clientId,
            keepAliveSeconds = keepAliveSeconds,
            cleanStart = cleanStart,
        )

    // --- Connect ---

    @Test
    fun connectSuccess() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val connAck = connection.connect(endpoint)
            advanceUntilIdle()

            assertEquals(ReasonCode.SUCCESS, connAck.reasonCode)
            assertEquals(ConnectionState.CONNECTED, connection.connectionState.value)

            val sentPacket = transport.decodeSentPackets().first()
            assertIs<Connect>(sentPacket)
            assertEquals("test-client", sentPacket.clientId)
            assertEquals(true, sentPacket.cleanStart)

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun connectWithConfigFields() =
        runTest {
            val transport = FakeTransport()
            val config =
                MqttConfig(
                    clientId = "my-client",
                    keepAliveSeconds = 30,
                    cleanStart = false,
                    username = "user",
                    password = ByteString("secret".encodeToByteArray()),
                    sessionExpiryInterval = 3600L,
                    receiveMaximum = 100,
                    maximumPacketSize = 1_048_576L,
                    topicAliasMaximum = 10,
                    requestResponseInformation = true,
                    requestProblemInformation = false,
                )
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            val sent = transport.decodeSentPackets().first()
            assertIs<Connect>(sent)
            assertEquals("my-client", sent.clientId)
            assertEquals(30, sent.keepAliveSeconds)
            assertEquals(false, sent.cleanStart)
            assertEquals("user", sent.username)
            assertNotNull(sent.password)
            assertEquals(3600L, sent.properties.sessionExpiryInterval)
            assertEquals(100, sent.properties.receiveMaximum)
            assertEquals(1_048_576L, sent.properties.maximumPacketSize)
            assertEquals(10, sent.properties.topicAliasMaximum)
            assertEquals(true, sent.properties.requestResponseInformation)
            assertEquals(false, sent.properties.requestProblemInformation)

            connection.disconnect()
        }

    @Test
    fun connectWithWill() =
        runTest {
            val transport = FakeTransport()
            val willConfig =
                WillConfig(
                    topic = "last-will/topic",
                    payload = ByteString("goodbye".encodeToByteArray()),
                    qos = QoS.AT_LEAST_ONCE,
                    retain = true,
                    willDelayInterval = 60L,
                    messageExpiryInterval = 300L,
                    contentType = "text/plain",
                    responseTopic = "response/topic",
                    payloadFormatIndicator = true,
                )
            val config = defaultConfig().copy(will = willConfig)
            val connection = MqttConnection(transport, config, this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            val sent = transport.decodeSentPackets().first()
            assertIs<Connect>(sent)
            assertEquals("last-will/topic", sent.willTopic)
            assertNotNull(sent.willPayload)
            assertEquals("goodbye", sent.willPayload?.decodeToString())
            assertEquals(QoS.AT_LEAST_ONCE, sent.willQos)
            assertEquals(true, sent.willRetain)
            assertEquals(60L, sent.willProperties.willDelayInterval)
            assertEquals(300L, sent.willProperties.messageExpiryInterval)
            assertEquals("text/plain", sent.willProperties.contentType)
            assertEquals("response/topic", sent.willProperties.responseTopic)
            assertEquals(true, sent.willProperties.payloadFormatIndicator)

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun connectFailureThrowsException() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.BAD_USER_NAME_OR_PASSWORD))

            val ex =
                assertFailsWith<MqttConnectionException> {
                    connection.connect(endpoint)
                }
            assertEquals(ReasonCode.BAD_USER_NAME_OR_PASSWORD, ex.reasonCode)
            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        }

    @Test
    fun connectStoresAssignedClientId() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(clientId = ""), this)

            val connAckProps = MqttProperties(assignedClientIdentifier = "server-assigned-id")
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS, properties = connAckProps))

            connection.connect(endpoint)
            advanceUntilIdle()

            assertEquals(ConnectionState.CONNECTED, connection.connectionState.value)

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Disconnect ---

    @Test
    fun disconnectSendsPacketAndSetsState() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            connection.disconnect()
            advanceUntilIdle()

            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)

            val disconnectPackets = transport.decodeSentPackets().filterIsInstance<Disconnect>()
            assertEquals(1, disconnectPackets.size)
            assertEquals(ReasonCode.SUCCESS, disconnectPackets.first().reasonCode)
        }

    @Test
    fun disconnectWithReasonCode() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            connection.disconnect(ReasonCode.DISCONNECT_WITH_WILL)
            advanceUntilIdle()

            val disconnectPackets = transport.decodeSentPackets().filterIsInstance<Disconnect>()
            assertEquals(1, disconnectPackets.size)
            assertEquals(ReasonCode.DISCONNECT_WITH_WILL, disconnectPackets.first().reasonCode)
        }

    // --- Publish QoS 0 ---

    @Test
    fun publishQos0SendsPacketWithoutPacketId() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            val result =
                connection.publish(
                    MqttMessage("test/topic", "hello".encodeToByteArray(), QoS.AT_MOST_ONCE),
                )
            advanceUntilIdle()

            assertNull(result)

            val publishPackets = transport.decodeSentPackets().filterIsInstance<Publish>()
            assertEquals(1, publishPackets.size)
            val pub = publishPackets.first()
            assertEquals("test/topic", pub.topicName)
            assertEquals("hello", pub.payload.decodeToString())
            assertEquals(QoS.AT_MOST_ONCE, pub.qos)
            assertNull(pub.packetIdentifier)

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Publish QoS 1 ---

    @Test
    fun publishQos1WaitsForPubAck() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Launch a coroutine to respond with PubAck after the PUBLISH is sent
            launch {
                // Wait for the PUBLISH to be sent (CONNECT is packet 0)
                while (transport.sentPackets.size < 2) {
                    delay(1)
                }
                val publishPacket = decodePacket(transport.sentPackets.last()) as Publish
                transport.enqueuePacket(PubAck(packetIdentifier = publishPacket.packetIdentifier!!))
            }

            val result =
                connection.publish(
                    MqttMessage("test/topic", "hello".encodeToByteArray(), QoS.AT_LEAST_ONCE),
                )
            advanceUntilIdle()

            assertEquals(ReasonCode.SUCCESS, result)

            val publishPackets = transport.decodeSentPackets().filterIsInstance<Publish>()
            assertEquals(1, publishPackets.size)
            assertNotNull(publishPackets.first().packetIdentifier)

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Publish QoS 2 ---

    @Test
    fun publishQos2FullFlow() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Respond with PubRec after PUBLISH, then PubComp after PubRel
            launch {
                // Wait for PUBLISH
                while (transport.sentPackets.size < 2) {
                    delay(1)
                }
                val publishPacket = decodePacket(transport.sentPackets.last()) as Publish
                val packetId = publishPacket.packetIdentifier!!
                transport.enqueuePacket(PubRec(packetIdentifier = packetId))

                // Wait for PUBREL
                while (transport.sentPackets.size < 3) {
                    delay(1)
                }
                val pubRelPacket = decodePacket(transport.sentPackets.last()) as PubRel
                assertEquals(packetId, pubRelPacket.packetIdentifier)
                transport.enqueuePacket(PubComp(packetIdentifier = packetId))
            }

            val result =
                connection.publish(
                    MqttMessage("test/topic", "qos2-payload".encodeToByteArray(), QoS.EXACTLY_ONCE),
                )
            advanceUntilIdle()

            assertEquals(ReasonCode.SUCCESS, result)

            // Verify the full flow: CONNECT, PUBLISH, PUBREL
            val allSent = transport.decodeSentPackets()
            assertTrue(allSent.any { it is Publish })
            assertTrue(allSent.any { it is PubRel })

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Receive messages ---

    @Test
    fun receiveQos0Message() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Enqueue an incoming PUBLISH from the broker
            transport.enqueuePacket(
                Publish(
                    topicName = "incoming/topic",
                    payload = "world".encodeToByteArray(),
                    qos = QoS.AT_MOST_ONCE,
                ),
            )

            val message =
                withTimeout(TIMEOUT_MS) {
                    connection.incomingMessages.first()
                }

            assertEquals("incoming/topic", message.topic)
            assertEquals("world", message.payload.toByteArray().decodeToString())
            assertEquals(QoS.AT_MOST_ONCE, message.qos)

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun receiveQos1MessageSendsPubAck() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            transport.enqueuePacket(
                Publish(
                    topicName = "incoming/qos1",
                    payload = "data".encodeToByteArray(),
                    qos = QoS.AT_LEAST_ONCE,
                    packetIdentifier = 42,
                ),
            )

            val message =
                withTimeout(TIMEOUT_MS) {
                    connection.incomingMessages.first()
                }

            assertEquals("incoming/qos1", message.topic)
            advanceUntilIdle()

            // Verify PubAck was sent back
            val pubAcks = transport.decodeSentPackets().filterIsInstance<PubAck>()
            assertEquals(1, pubAcks.size)
            assertEquals(42, pubAcks.first().packetIdentifier)

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Subscribe ---

    @Test
    fun subscribeReturnsSubAck() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Respond to SUBSCRIBE with SUBACK
            launch {
                // Wait for SUBSCRIBE to be sent
                while (transport.sentPackets.size < 2) {
                    delay(1)
                }
                val subPacket = decodePacket(transport.sentPackets.last()) as Subscribe
                transport.enqueuePacket(
                    SubAck(
                        packetIdentifier = subPacket.packetIdentifier,
                        reasonCodes = listOf(ReasonCode.SUCCESS),
                    ),
                )
            }

            val subAck =
                connection.subscribe(
                    listOf(Subscription(topicFilter = "test/+", maxQos = QoS.AT_LEAST_ONCE)),
                )
            advanceUntilIdle()

            assertEquals(1, subAck.reasonCodes.size)
            assertEquals(ReasonCode.SUCCESS, subAck.reasonCodes.first())

            // Verify SUBSCRIBE was sent
            val subPackets = transport.decodeSentPackets().filterIsInstance<Subscribe>()
            assertEquals(1, subPackets.size)
            assertEquals(
                "test/+",
                subPackets
                    .first()
                    .subscriptions
                    .first()
                    .topicFilter,
            )

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Unsubscribe ---

    @Test
    fun unsubscribeReturnsUnsubAck() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Respond to UNSUBSCRIBE with UNSUBACK
            launch {
                while (transport.sentPackets.size < 2) {
                    delay(1)
                }
                val unsubPacket = decodePacket(transport.sentPackets.last()) as Unsubscribe
                transport.enqueuePacket(
                    UnsubAck(
                        packetIdentifier = unsubPacket.packetIdentifier,
                        reasonCodes = listOf(ReasonCode.SUCCESS),
                    ),
                )
            }

            val unsubAck = connection.unsubscribe(listOf("test/+"))
            advanceUntilIdle()

            assertEquals(1, unsubAck.reasonCodes.size)
            assertEquals(ReasonCode.SUCCESS, unsubAck.reasonCodes.first())

            val unsubPackets = transport.decodeSentPackets().filterIsInstance<Unsubscribe>()
            assertEquals(1, unsubPackets.size)
            assertEquals("test/+", unsubPackets.first().topicFilters.first())

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Server disconnect ---

    @Test
    fun serverDisconnectSetsState() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Server sends DISCONNECT
            transport.enqueuePacket(Disconnect(reasonCode = ReasonCode.SERVER_SHUTTING_DOWN))
            advanceUntilIdle()

            assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        }

    // --- PubRel from server (incoming QoS 2) ---

    @Test
    fun serverPubRelTriggersPubComp() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Server sends PubRel (step 2 of server-side QoS 2)
            transport.enqueuePacket(PubRel(packetIdentifier = 99))
            advanceUntilIdle()

            // Verify PubComp was sent back
            val pubComps = transport.decodeSentPackets().filterIsInstance<PubComp>()
            assertEquals(1, pubComps.size)
            assertEquals(99, pubComps.first().packetIdentifier)

            connection.disconnect()
            advanceUntilIdle()
        }

    companion object {
        /** Timeout for waiting on async operations in tests. */
        private const val TIMEOUT_MS = 5_000L
    }
}
