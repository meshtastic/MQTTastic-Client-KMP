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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
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
            assertEquals(ConnectionState.Connected, connection.connectionState.value)

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
            assertEquals("goodbye", sent.willPayload.decodeToString())
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
            assertEquals(ConnectionState.Disconnected.Idle, connection.connectionState.value)
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

            assertEquals(ConnectionState.Connected, connection.connectionState.value)

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

            assertEquals(ConnectionState.Disconnected.Idle, connection.connectionState.value)

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

            val state = connection.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            val reason = state.reason
            assertIs<MqttException.ConnectionLost>(reason)
            assertEquals(ReasonCode.SERVER_SHUTTING_DOWN, reason.reasonCode)
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

    // --- QoS 2 Inbound Duplicate Suppression ---

    @Test
    fun qos2InboundDuplicateSuppressed() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            val received = mutableListOf<MqttMessage>()
            val collectJob =
                launch {
                    connection.incomingMessages.collect { received.add(it) }
                }
            advanceUntilIdle()

            // First QoS 2 PUBLISH — stored pending PUBREL, PUBREC sent, NOT yet delivered
            transport.enqueuePacket(
                Publish(
                    topicName = "qos2/test",
                    payload = "first".encodeToByteArray(),
                    qos = QoS.EXACTLY_ONCE,
                    packetIdentifier = 42,
                    properties = MqttProperties.EMPTY,
                ),
            )
            advanceUntilIdle()

            assertEquals(0, received.size, "QoS 2 message should NOT be delivered before PUBREL")

            // Retransmission with same packet ID — should NOT overwrite stored message
            transport.enqueuePacket(
                Publish(
                    topicName = "qos2/test",
                    payload = "first".encodeToByteArray(),
                    qos = QoS.EXACTLY_ONCE,
                    dup = true,
                    packetIdentifier = 42,
                    properties = MqttProperties.EMPTY,
                ),
            )
            advanceUntilIdle()

            assertEquals(0, received.size, "Duplicate QoS 2 PUBLISH should still not deliver before PUBREL")

            // Both should have sent PUBREC
            val pubRecs = transport.decodeSentPackets().filterIsInstance<PubRec>()
            assertEquals(2, pubRecs.size, "PUBREC should be sent for both original and duplicate")

            // PUBREL releases the message — now it should be delivered
            transport.enqueuePacket(PubRel(packetIdentifier = 42))
            advanceUntilIdle()

            assertEquals(1, received.size, "QoS 2 message should be delivered exactly once after PUBREL")
            assertEquals("first", received[0].payload.toByteArray().decodeToString())

            collectJob.cancel()
            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Server Keep Alive Override ---

    @Test
    fun serverKeepAliveOverridesConfig() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(keepAliveSeconds = 60)
            val connection = MqttConnection(transport, config, this)

            // CONNACK with Server Keep Alive of 30 seconds
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.SUCCESS,
                    properties = MqttProperties(serverKeepAlive = 30),
                ),
            )
            val connAck = connection.connect(endpoint)

            // Verify the server keep alive was received (no advanceUntilIdle before
            // disconnect — the keepalive loop runs indefinitely in virtual time)
            assertEquals(30, connAck.properties.serverKeepAlive)

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- QoS 2 PUBREC error code check (§4.3.3) ---

    @Test
    fun qos2PubRecError_doesNotSendPubRel() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Respond with PUBREC containing error reason code (>= 0x80)
            launch {
                while (transport.sentPackets.size < 2) {
                    delay(1)
                }
                val publishPacket = decodePacket(transport.sentPackets.last()) as Publish
                transport.enqueuePacket(
                    PubRec(
                        packetIdentifier = publishPacket.packetIdentifier!!,
                        reasonCode = ReasonCode.QUOTA_EXCEEDED,
                    ),
                )
            }

            val result =
                connection.publish(
                    MqttMessage("test/topic", "payload".encodeToByteArray(), QoS.EXACTLY_ONCE),
                )
            advanceUntilIdle()

            // Should return the error reason code from PUBREC
            assertEquals(ReasonCode.QUOTA_EXCEEDED, result)

            // Should NOT have sent PUBREL — only CONNECT and PUBLISH
            val allSent = transport.decodeSentPackets()
            assertTrue(allSent.none { it is PubRel }, "PUBREL must NOT be sent when PUBREC has error code")

            connection.disconnect()
            advanceUntilIdle()
        }

    // --- disconnect()/abort() fail pending ACKs ---

    @Test
    fun disconnectFailsPendingPublishImmediately() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Start a QoS 1 publish but don't enqueue a PubAck — it will hang
            var publishException: Throwable? = null
            val publishJob =
                launch {
                    try {
                        connection.publish(
                            MqttMessage("test/topic", "data".encodeToByteArray(), QoS.AT_LEAST_ONCE),
                        )
                    } catch (e: Throwable) {
                        publishException = e
                    }
                }
            // Let the publish coroutine send the PUBLISH and reach deferred.await()
            // without advancing virtual time (which would trigger the 30s timeout)
            while (transport.sentPackets.size < 2) {
                yield()
            }

            // disconnect() should fail the pending ACK immediately
            connection.disconnect()
            advanceUntilIdle()

            publishJob.join()
            assertNotNull(publishException, "Pending publish should fail when disconnect() is called")
            assertIs<MqttConnectionException>(publishException)
        }

    @Test
    fun abortFailsPendingSubscribeImmediately() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Start a subscribe but don't enqueue a SubAck — it will hang
            var subscribeException: Throwable? = null
            val subscribeJob =
                launch {
                    try {
                        connection.subscribe(
                            listOf(Subscription(topicFilter = "test/+", maxQos = QoS.AT_LEAST_ONCE)),
                        )
                    } catch (e: Throwable) {
                        subscribeException = e
                    }
                }
            // Let the subscribe coroutine send the SUBSCRIBE and reach deferred.await()
            while (transport.sentPackets.size < 2) {
                yield()
            }

            // abort() should fail the pending ACK immediately
            connection.abort()
            advanceUntilIdle()

            subscribeJob.join()
            assertNotNull(subscribeException, "Pending subscribe should fail when abort() is called")
            assertIs<MqttConnectionException>(subscribeException)
        }

    // --- Inbound topic alias validation (§3.3.2.3.4) ---

    @Test
    fun inboundTopicAliasExceedingMaximum_triggersError() =
        runTest {
            val transport = FakeTransport()
            // Advertise topicAliasMaximum = 5
            val connection = MqttConnection(transport, defaultConfig().copy(topicAliasMaximum = 5), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Send a PUBLISH with alias 10 which exceeds the advertised maximum of 5
            transport.enqueuePacket(
                Publish(
                    topicName = "device/status",
                    payload = "ok".encodeToByteArray(),
                    properties = MqttProperties(topicAlias = 10),
                ),
            )
            advanceUntilIdle()

            // The connection should be in DISCONNECTED state due to the protocol error
            val state = connection.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertIs<MqttException.ConnectionLost>(state.reason)
        }

    // --- Safe ACK casts — wrong packet type for pending ACK ---

    @Test
    fun wrongAckPacketType_throwsProtocolError() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Start a subscribe — expects SubAck
            var subscribeException: Throwable? = null
            val subscribeJob =
                launch {
                    try {
                        connection.subscribe(
                            listOf(Subscription(topicFilter = "test/+", maxQos = QoS.AT_LEAST_ONCE)),
                        )
                    } catch (e: Throwable) {
                        subscribeException = e
                    }
                }
            // Let the subscribe coroutine send the SUBSCRIBE and reach deferred.await()
            while (transport.sentPackets.size < 2) {
                yield()
            }

            // Instead of SubAck, send a PubAck with the same packet ID
            val subPacket = decodePacket(transport.sentPackets.last()) as Subscribe
            transport.enqueuePacket(
                PubAck(packetIdentifier = subPacket.packetIdentifier),
            )
            advanceUntilIdle()

            subscribeJob.join()
            // Should get MqttConnectionException, not ClassCastException
            assertNotNull(subscribeException, "Wrong ACK type should cause an error")
            assertIs<MqttConnectionException>(subscribeException)
            assertEquals(ReasonCode.PROTOCOL_ERROR, (subscribeException as MqttConnectionException).reasonCode)

            // Clean up — disconnect to stop read loop
            connection.disconnect()
            advanceUntilIdle()
        }

    // --- handleFatalError sends DISCONNECT (protocol violation teardown) ---

    @Test
    fun protocolViolation_triggersDisconnectPacket() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            // Sending a CONNECT packet from the broker is a protocol violation
            transport.enqueuePacket(
                ConnAck(reasonCode = ReasonCode.SUCCESS),
            )
            advanceUntilIdle()

            // Connection should be torn down with PROTOCOL_ERROR reason
            val state = connection.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            val reason = state.reason
            assertIs<MqttException.ConnectionLost>(reason)
            assertEquals(ReasonCode.PROTOCOL_ERROR, reason.reasonCode)

            // Verify a DISCONNECT was sent with PROTOCOL_ERROR reason code
            val disconnects = transport.decodeSentPackets().filterIsInstance<Disconnect>()
            assertTrue(disconnects.isNotEmpty(), "Should send DISCONNECT on protocol violation")
            assertEquals(
                ReasonCode.PROTOCOL_ERROR,
                disconnects.last().reasonCode,
                "DISCONNECT should carry PROTOCOL_ERROR reason code",
            )
        }

    // --- AUTH During Connect ---

    @Test
    fun authDuringConnectHandshake() =
        runTest {
            val transport = FakeTransport()
            val config =
                MqttConfig(
                    clientId = "auth-client",
                    keepAliveSeconds = 0,
                    cleanStart = true,
                    authenticationMethod = "SCRAM-SHA-256",
                )
            val connection = MqttConnection(transport, config, this)

            val challenges = mutableListOf<AuthChallenge>()
            val authJob =
                launch {
                    connection.authChallenges.collect { challenge ->
                        challenges.add(challenge)
                        // Respond to auth challenge
                        connection.sendAuthResponse("response-data".encodeToByteArray())
                    }
                }
            // Let the collector coroutine start
            advanceUntilIdle()

            // Enqueue: AUTH challenge then CONNACK
            transport.enqueuePacket(
                Auth(
                    reasonCode = ReasonCode.CONTINUE_AUTHENTICATION,
                    properties =
                        MqttProperties(
                            authenticationMethod = "SCRAM-SHA-256",
                            authenticationData = "challenge-data".encodeToByteArray(),
                        ),
                ),
            )
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val connAck = connection.connect(endpoint)
            advanceUntilIdle()

            assertEquals(ReasonCode.SUCCESS, connAck.reasonCode)
            assertEquals(1, challenges.size)
            assertEquals(ReasonCode.CONTINUE_AUTHENTICATION, challenges[0].reasonCode)

            authJob.cancel()
            connection.disconnect()
            advanceUntilIdle()
        }

    // --- QoS 2 inbound ordering (§4.3.3) ---

    @Test
    fun qos2InboundDeliveredOnPubRelInOrder() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            val received = mutableListOf<MqttMessage>()
            val collectJob =
                launch {
                    connection.incomingMessages.collect { received.add(it) }
                }
            advanceUntilIdle()

            // Send two QoS 2 PUBLISH packets with different packet IDs
            transport.enqueuePacket(
                Publish(
                    topicName = "order/test",
                    payload = "first".encodeToByteArray(),
                    qos = QoS.EXACTLY_ONCE,
                    packetIdentifier = 1,
                    properties = MqttProperties.EMPTY,
                ),
            )
            transport.enqueuePacket(
                Publish(
                    topicName = "order/test",
                    payload = "second".encodeToByteArray(),
                    qos = QoS.EXACTLY_ONCE,
                    packetIdentifier = 2,
                    properties = MqttProperties.EMPTY,
                ),
            )
            advanceUntilIdle()

            // Neither should be delivered yet — both pending PUBREL
            assertEquals(0, received.size, "No messages before PUBREL")

            // Release second first (out of order)
            transport.enqueuePacket(PubRel(packetIdentifier = 2))
            advanceUntilIdle()
            assertEquals(1, received.size)
            assertEquals("second", received[0].payload.toByteArray().decodeToString())

            // Release first
            transport.enqueuePacket(PubRel(packetIdentifier = 1))
            advanceUntilIdle()
            assertEquals(2, received.size)
            assertEquals("first", received[1].payload.toByteArray().decodeToString())

            collectJob.cancel()
            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Connect timeout ---

    @Test
    fun connectTimesOutWithoutConnAck() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            // Don't enqueue a CONNACK — the connection should time out
            val exception =
                assertFailsWith<MqttConnectionException> {
                    connection.connect(endpoint)
                }

            assertTrue(
                exception.message?.contains("timed out") == true,
                "Expected timeout message, got: ${exception.message}",
            )
            assertEquals(ConnectionState.Disconnected.Idle, connection.connectionState.value)
        }

    // --- Stray PUBREL (session resumption) ---

    @Test
    fun strayPubRelSendsPubCompWithoutEmitting() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            val received = mutableListOf<MqttMessage>()
            val collectJob =
                launch {
                    connection.incomingMessages.collect { received.add(it) }
                }
            advanceUntilIdle()

            // Send PUBREL for unknown packet ID (session resumption scenario)
            transport.enqueuePacket(PubRel(packetIdentifier = 99))
            advanceUntilIdle()

            // Should NOT emit any message
            assertEquals(0, received.size, "Stray PUBREL should not emit a message")

            // Should still send PUBCOMP with PACKET_IDENTIFIER_NOT_FOUND (§3.7.2.1)
            val pubComps = transport.decodeSentPackets().filterIsInstance<PubComp>()
            assertEquals(1, pubComps.size, "PUBCOMP should be sent for stray PUBREL")
            assertEquals(99, pubComps.first().packetIdentifier)
            assertEquals(
                ReasonCode.PACKET_IDENTIFIER_NOT_FOUND,
                pubComps.first().reasonCode,
                "PUBCOMP for unknown PUBREL must use PACKET_IDENTIFIER_NOT_FOUND (§3.7.2.1)",
            )

            collectJob.cancel()
            connection.disconnect()
            advanceUntilIdle()
        }

    // --- Keepalive ---

    @Test
    fun keepaliveSendsPingreq() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(keepAliveSeconds = 10)
            val connection = MqttConnection(transport, config, this, timeSource = testScheduler.timeSource)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            // Advance precisely past the 75% keepalive interval (10s * 0.75 = 7.5s).
            // Don't use advanceUntilIdle — it would process the entire keepalive loop to timeout.
            advanceTimeBy(7_600)

            val pingreqs = transport.decodeSentPackets().filterIsInstance<PingReq>()
            assertEquals(1, pingreqs.size, "PINGREQ should be sent after 75% of keepalive interval")

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun keepalivePingrespResetsTimer() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(keepAliveSeconds = 10)
            val connection = MqttConnection(transport, config, this, timeSource = testScheduler.timeSource)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            // Trigger PINGREQ at 75% interval
            advanceTimeBy(7_600)

            val pingreqs = transport.decodeSentPackets().filterIsInstance<PingReq>()
            assertEquals(1, pingreqs.size)

            // Respond with PINGRESP and allow read loop to process it
            transport.enqueuePacket(PingResp)
            advanceTimeBy(100)

            // Connection should still be alive
            assertEquals(ConnectionState.Connected, connection.connectionState.value)

            connection.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun keepalivePingrespTimeoutDisconnects() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(keepAliveSeconds = 10)
            val connection = MqttConnection(transport, config, this, timeSource = testScheduler.timeSource)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)

            // Advance through keepalive loop until PINGRESP timeout fires:
            //   t=7500 → PINGREQ sent, pingSentMark set
            //   t=15000 → elapsed=7500 < deadline=10000, no timeout yet
            //   t=22500 → elapsed=15000 >= deadline=10000 → TIMEOUT
            advanceTimeBy(23_000)

            val state = connection.connectionState.value
            assertIs<ConnectionState.Disconnected>(
                state,
                "Connection should be DISCONNECTED after PINGRESP timeout",
            )
            assertIs<MqttException.ConnectionLost>(state.reason)
        }

    @Test
    fun v5ConnectionFallsBackToV311WhenBrokerSendsV311Packets() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, defaultConfig(), this)

            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            connection.connect(endpoint)
            advanceUntilIdle()

            val v311Publish = Publish(
                topicName = "test/topic",
                payload = "hello".encodeToByteArray(),
                qos = QoS.AT_MOST_ONCE,
            )

            val msgDeferred = async { connection.incomingMessages.first() }
            yield()

            transport.enqueueReceive(v311Publish.encode(MqttProtocolVersion.V3_1_1))
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, connection.connectionState.value)

            val msg = msgDeferred.await()
            assertEquals("test/topic", msg.topic)
            assertEquals("hello", msg.payload.toByteArray().decodeToString())

            connection.disconnect()
            advanceUntilIdle()
        }

    companion object {
        /** Timeout for waiting on async operations in tests. */
        private const val TIMEOUT_MS = 5_000L
    }
}
