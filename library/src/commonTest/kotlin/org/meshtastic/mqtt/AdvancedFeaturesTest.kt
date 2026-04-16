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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.PubAck
import org.meshtastic.mqtt.packet.Publish
import org.meshtastic.mqtt.packet.SubAck
import org.meshtastic.mqtt.packet.Subscribe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
class AdvancedFeaturesTest {
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    private fun defaultConfig(
        keepAliveSeconds: Int = 0,
        cleanStart: Boolean = true,
        clientId: String = "test-client",
        topicAliasMaximum: Int = 0,
        authenticationMethod: String? = null,
    ): MqttConfig =
        MqttConfig(
            clientId = clientId,
            keepAliveSeconds = keepAliveSeconds,
            cleanStart = cleanStart,
            topicAliasMaximum = topicAliasMaximum,
            authenticationMethod = authenticationMethod,
        )

    // ==================== 1. Topic Aliases (§3.3.2.3.4) ====================

    @Test
    fun topicAliasOutbound_secondPublishUsesAlias() =
        runTest {
            val transport = FakeTransport()
            // CONNACK with topicAliasMaximum=10 to enable outbound aliases
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.SUCCESS,
                    properties = MqttProperties(topicAliasMaximum = 10),
                ),
            )

            val connection = MqttConnection(transport, defaultConfig(), this)
            connection.connect(endpoint)
            advanceUntilIdle()

            // First publish: should send topic name AND alias
            connection.publish(
                MqttMessage(topic = "sensor/temp", payload = ByteString(byteArrayOf(1))),
            )
            advanceUntilIdle()

            val firstPublish =
                transport
                    .decodeSentPackets()
                    .filterIsInstance<Publish>()
                    .first()
            assertEquals("sensor/temp", firstPublish.topicName)
            val assignedAlias = assertNotNull(firstPublish.properties.topicAlias)

            // Second publish to same topic: should send empty topic + alias
            connection.publish(
                MqttMessage(topic = "sensor/temp", payload = ByteString(byteArrayOf(2))),
            )
            advanceUntilIdle()

            val secondPublish =
                transport
                    .decodeSentPackets()
                    .filterIsInstance<Publish>()
                    .last()
            assertEquals("", secondPublish.topicName)
            assertEquals(assignedAlias, secondPublish.properties.topicAlias)

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun topicAliasOutbound_noAliasWhenServerDisallows() =
        runTest {
            val transport = FakeTransport()
            // CONNACK with topicAliasMaximum=0 (no aliases)
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.SUCCESS,
                    properties = MqttProperties(topicAliasMaximum = 0),
                ),
            )

            val connection = MqttConnection(transport, defaultConfig(), this)
            connection.connect(endpoint)
            advanceUntilIdle()

            connection.publish(
                MqttMessage(topic = "sensor/temp", payload = ByteString(byteArrayOf(1))),
            )
            advanceUntilIdle()

            val publish =
                transport
                    .decodeSentPackets()
                    .filterIsInstance<Publish>()
                    .first()
            assertEquals("sensor/temp", publish.topicName)
            // topicAlias should not be set (either null or absent)
            assertEquals(null, publish.properties.topicAlias)

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun topicAliasInbound_resolvesFromStoredMapping() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val connection = MqttConnection(transport, defaultConfig(topicAliasMaximum = 10), this)
            connection.connect(endpoint)
            advanceUntilIdle()

            val receivedMessages = mutableListOf<MqttMessage>()
            val job =
                launch {
                    connection.incomingMessages.collect { receivedMessages.add(it) }
                }

            // First incoming: topic + alias — establishes mapping
            transport.enqueuePacket(
                Publish(
                    topicName = "device/status",
                    payload = "online".encodeToByteArray(),
                    properties = MqttProperties(topicAlias = 5),
                ),
            )
            advanceUntilIdle()

            // Second incoming: empty topic + alias — should resolve
            transport.enqueuePacket(
                Publish(
                    topicName = "",
                    payload = "offline".encodeToByteArray(),
                    properties = MqttProperties(topicAlias = 5),
                ),
            )
            advanceUntilIdle()

            assertEquals(2, receivedMessages.size)
            assertEquals("device/status", receivedMessages[0].topic)
            assertEquals("device/status", receivedMessages[1].topic)
            assertEquals(
                "online",
                receivedMessages[0].payload.toByteArray().decodeToString(),
            )
            assertEquals(
                "offline",
                receivedMessages[1].payload.toByteArray().decodeToString(),
            )

            job.cancel()
            connection.disconnect()
            advanceUntilIdle()
        }

    // ==================== 2. Flow Control (§3.3.4) ====================

    @Test
    fun flowControl_blocksWhenReceiveMaximumReached() =
        runTest {
            val transport = FakeTransport()
            // CONNACK with receiveMaximum=2
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.SUCCESS,
                    properties = MqttProperties(receiveMaximum = 2),
                ),
            )

            val connection = MqttConnection(transport, defaultConfig(), this)
            connection.connect(endpoint)
            advanceUntilIdle()

            // Launch a coroutine to respond with PubAcks as PUBLISH packets are sent
            val connectPacketCount = 1 // CONNECT already sent
            launch {
                for (expectedIdx in 1..3) {
                    val targetCount = connectPacketCount + expectedIdx
                    while (transport.sentPackets.size < targetCount) {
                        kotlinx.coroutines.delay(1)
                    }
                    val publishPacket =
                        org.meshtastic.mqtt.packet.decodePacket(
                            transport.sentPackets[targetCount - 1],
                        ) as Publish
                    transport.enqueuePacket(
                        PubAck(packetIdentifier = publishPacket.packetIdentifier!!),
                    )
                }
            }

            // Publish 3 QoS 1 messages sequentially — semaphore permits=2
            val results = mutableListOf<ReasonCode?>()
            for (i in 1..3) {
                val result =
                    connection.publish(
                        MqttMessage(
                            topic = "t$i",
                            payload = ByteString(byteArrayOf(i.toByte())),
                            qos = QoS.AT_LEAST_ONCE,
                        ),
                    )
                results.add(result)
            }
            advanceUntilIdle()

            // All three should have completed successfully
            assertEquals(3, results.size)
            results.forEach { assertEquals(ReasonCode.SUCCESS, it) }

            val publishes =
                transport.decodeSentPackets().filterIsInstance<Publish>()
            assertEquals(3, publishes.size)

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun flowControl_qos0BypassesSemaphore() =
        runTest {
            val transport = FakeTransport()
            // CONNACK with receiveMaximum=1
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.SUCCESS,
                    properties = MqttProperties(receiveMaximum = 1),
                ),
            )

            val connection = MqttConnection(transport, defaultConfig(), this)
            connection.connect(endpoint)
            advanceUntilIdle()

            // QoS 0 publishes should not be limited by flow control
            connection.publish(
                MqttMessage(topic = "t1", payload = ByteString(byteArrayOf(1))),
            )
            connection.publish(
                MqttMessage(topic = "t2", payload = ByteString(byteArrayOf(2))),
            )
            advanceUntilIdle()

            val publishes =
                transport.decodeSentPackets().filterIsInstance<Publish>()
            assertEquals(2, publishes.size)

            connection.disconnect()
            advanceUntilIdle()
        }

    // ==================== 3. Subscription Options (§3.8.3.1) ====================

    @Test
    fun subscriptionOptions_noLocalAndRetainHandling() =
        runTest {
            val transport = FakeTransport()
            val client = createConnectedClient(transport, scope = this)

            // Enqueue SubAck
            transport.enqueuePacket(
                SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS)),
            )

            client.subscribe(
                topicFilter = "home/lights",
                qos = QoS.AT_LEAST_ONCE,
                noLocal = true,
                retainAsPublished = true,
                retainHandling = RetainHandling.DO_NOT_SEND,
            )
            advanceUntilIdle()

            val sentSubscribe =
                transport.decodeSentPackets().filterIsInstance<Subscribe>().first()
            val sub = sentSubscribe.subscriptions.first()

            assertEquals("home/lights", sub.topicFilter)
            assertEquals(QoS.AT_LEAST_ONCE, sub.maxQos)
            assertTrue(sub.noLocal)
            assertTrue(sub.retainAsPublished)
            assertEquals(RetainHandling.DO_NOT_SEND, sub.retainHandling)

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    @Test
    fun subscriptionOptions_defaultValues() =
        runTest {
            val transport = FakeTransport()
            val client = createConnectedClient(transport, scope = this)

            transport.enqueuePacket(
                SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS)),
            )

            // Default subscribe — noLocal=false, retainAsPublished=false, SEND_AT_SUBSCRIBE
            client.subscribe("topic/default", QoS.AT_MOST_ONCE)
            advanceUntilIdle()

            val sentSubscribe =
                transport.decodeSentPackets().filterIsInstance<Subscribe>().first()
            val sub = sentSubscribe.subscriptions.first()

            assertEquals("topic/default", sub.topicFilter)
            assertEquals(false, sub.noLocal)
            assertEquals(false, sub.retainAsPublished)
            assertEquals(RetainHandling.SEND_AT_SUBSCRIBE, sub.retainHandling)

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // ==================== 4. Server Redirect (§4.13) ====================

    @Test
    fun serverRedirect_connackWithServerReference() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.USE_ANOTHER_SERVER,
                    properties = MqttProperties(serverReference = "broker2.example.com:1883"),
                ),
            )

            val connection = MqttConnection(transport, defaultConfig(), this)

            val exception =
                assertFailsWith<MqttConnectionException> {
                    connection.connect(endpoint)
                }

            assertEquals(ReasonCode.USE_ANOTHER_SERVER, exception.reasonCode)
            assertEquals("broker2.example.com:1883", exception.serverReference)
        }

    @Test
    fun serverRedirect_serverMovedWithReference() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.SERVER_MOVED,
                    properties = MqttProperties(serverReference = "new-broker.example.com:8883"),
                ),
            )

            val connection = MqttConnection(transport, defaultConfig(), this)

            val exception =
                assertFailsWith<MqttConnectionException> {
                    connection.connect(endpoint)
                }

            assertEquals(ReasonCode.SERVER_MOVED, exception.reasonCode)
            assertEquals("new-broker.example.com:8883", exception.serverReference)
        }

    // ==================== 5. Request/Response Pattern (§4.10) ====================

    @Test
    fun publishWithResponse_setsResponseTopicAndCorrelationData() =
        runTest {
            val transport = FakeTransport()
            val client = createConnectedClient(transport, scope = this)

            // Enqueue PubAck for QoS 1
            transport.enqueuePacket(PubAck(packetIdentifier = 1))

            val correlationData = ByteString(byteArrayOf(0x01, 0x02, 0x03))
            client.publish(
                MqttMessage(
                    topic = "request/topic",
                    payload = ByteString("request body".encodeToByteArray()),
                    qos = QoS.AT_LEAST_ONCE,
                    properties =
                        PublishProperties(
                            responseTopic = "response/topic",
                            correlationData = correlationData,
                        ),
                ),
            )
            advanceUntilIdle()

            val sentPublish =
                transport.decodeSentPackets().filterIsInstance<Publish>().first()
            assertEquals("request/topic", sentPublish.topicName)
            assertEquals("response/topic", sentPublish.properties.responseTopic)
            assertTrue(
                sentPublish.properties.correlationData?.contentEquals(byteArrayOf(0x01, 0x02, 0x03)) == true,
            )

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    @Test
    fun publishWithResponse_withoutCorrelationData() =
        runTest {
            val transport = FakeTransport()
            val client = createConnectedClient(transport, scope = this)

            // QoS 0 — no ack needed
            client.publish(
                MqttMessage(
                    topic = "cmd/execute",
                    payload = ByteString("run".encodeToByteArray()),
                    qos = QoS.AT_MOST_ONCE,
                    properties =
                        PublishProperties(
                            responseTopic = "cmd/result",
                        ),
                ),
            )
            advanceUntilIdle()

            val sentPublish =
                transport.decodeSentPackets().filterIsInstance<Publish>().first()
            assertEquals("cmd/execute", sentPublish.topicName)
            assertEquals("cmd/result", sentPublish.properties.responseTopic)
            assertEquals(null, sentPublish.properties.correlationData)

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // ==================== 6. Enhanced Authentication (§4.12) ====================

    @Test
    fun enhancedAuth_authChallengeEmitted() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(authenticationMethod = "SCRAM-SHA-256")
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val connection = MqttConnection(transport, config, this)
            connection.connect(endpoint)
            advanceUntilIdle()

            val receivedChallenges = mutableListOf<AuthChallenge>()
            val job =
                launch {
                    connection.authChallenges.collect { receivedChallenges.add(it) }
                }

            // Simulate AUTH packet from server
            transport.enqueuePacket(
                org.meshtastic.mqtt.packet.Auth(
                    reasonCode = ReasonCode.CONTINUE_AUTHENTICATION,
                    properties =
                        MqttProperties(
                            authenticationMethod = "SCRAM-SHA-256",
                            authenticationData = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
                        ),
                ),
            )
            advanceUntilIdle()

            assertEquals(1, receivedChallenges.size)
            assertEquals(ReasonCode.CONTINUE_AUTHENTICATION, receivedChallenges[0].reasonCode)
            assertEquals("SCRAM-SHA-256", receivedChallenges[0].authenticationMethod)
            assertEquals(
                ByteString(byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
                receivedChallenges[0].authenticationData,
            )

            job.cancel()
            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun enhancedAuth_sendAuthResponse() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(authenticationMethod = "SCRAM-SHA-256")
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val connection = MqttConnection(transport, config, this)
            connection.connect(endpoint)
            advanceUntilIdle()

            connection.sendAuthResponse(byteArrayOf(0xCC.toByte(), 0xDD.toByte()))
            advanceUntilIdle()

            val sentAuth =
                transport
                    .decodeSentPackets()
                    .filterIsInstance<org.meshtastic.mqtt.packet.Auth>()
                    .first()
            assertEquals(ReasonCode.CONTINUE_AUTHENTICATION, sentAuth.reasonCode)
            assertEquals("SCRAM-SHA-256", sentAuth.properties.authenticationMethod)
            assertTrue(
                sentAuth.properties.authenticationData?.contentEquals(
                    byteArrayOf(0xCC.toByte(), 0xDD.toByte()),
                ) == true,
            )

            connection.disconnect()
            advanceUntilIdle()
        }

    // ==================== Helpers ====================

    private suspend fun createConnectedClient(
        transport: FakeTransport,
        config: MqttConfig = defaultConfig(),
        scope: kotlinx.coroutines.CoroutineScope,
    ): MqttClient {
        transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
        val client = MqttClient(config, transport, scope)
        client.connect(endpoint)
        return client
    }
}
