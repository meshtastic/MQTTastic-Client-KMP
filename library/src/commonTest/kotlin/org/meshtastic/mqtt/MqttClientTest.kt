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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.Connect
import org.meshtastic.mqtt.packet.Disconnect
import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.PingResp
import org.meshtastic.mqtt.packet.PubAck
import org.meshtastic.mqtt.packet.Publish
import org.meshtastic.mqtt.packet.SubAck
import org.meshtastic.mqtt.packet.Subscribe
import org.meshtastic.mqtt.packet.UnsubAck
import org.meshtastic.mqtt.packet.Unsubscribe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MqttClientTest {
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    private fun defaultConfig(autoReconnect: Boolean = false): MqttConfig =
        MqttConfig(
            clientId = "test-client",
            keepAliveSeconds = 0,
            cleanStart = true,
            autoReconnect = autoReconnect,
            reconnectBaseDelayMs = 100,
            reconnectMaxDelayMs = 500,
        )

    private fun connectedClient(
        transport: FakeTransport,
        config: MqttConfig = defaultConfig(),
        scope: kotlinx.coroutines.CoroutineScope,
    ): MqttClient {
        transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
        return MqttClient(config, transport, scope)
    }

    // --- Connect and Disconnect ---

    @Test
    fun connectAndDisconnect() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)

            client.connect(endpoint)
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, client.connectionState.value)

            val sentConnect = transport.decodeSentPackets().first()
            assertIs<Connect>(sentConnect)
            assertEquals("test-client", sentConnect.clientId)

            client.disconnect()
            advanceUntilIdle()

            assertEquals(ConnectionState.Disconnected.Idle, client.connectionState.value)

            val sentDisconnect = transport.decodeSentPackets().last()
            assertIs<Disconnect>(sentDisconnect)

            client.close()
        }

    // --- Publish QoS 0 ---

    @Test
    fun publishQos0() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            val message =
                MqttMessage(
                    topic = "test/topic",
                    payload = ByteString(byteArrayOf(1, 2, 3)),
                    qos = QoS.AT_MOST_ONCE,
                )
            client.publish(message)
            advanceUntilIdle()

            val sentPublish = transport.decodeSentPackets().last()
            assertIs<Publish>(sentPublish)
            assertEquals("test/topic", sentPublish.topicName)
            assertEquals(QoS.AT_MOST_ONCE, sentPublish.qos)
            assertTrue(sentPublish.payload.contentEquals(byteArrayOf(1, 2, 3)))

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Publish String Convenience ---

    @Test
    fun publishStringConvenience() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            client.publish("greet/topic", "hello")
            advanceUntilIdle()

            val sentPublish = transport.decodeSentPackets().last()
            assertIs<Publish>(sentPublish)
            assertEquals("greet/topic", sentPublish.topicName)
            assertTrue(sentPublish.payload.contentEquals("hello".encodeToByteArray()))

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Subscribe Single Topic ---

    @Test
    fun subscribeSingleTopic() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            // Enqueue SubAck before subscribe call
            transport.enqueuePacket(
                SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS)),
            )

            client.subscribe("sensors/#", QoS.AT_LEAST_ONCE)
            advanceUntilIdle()

            val sentSubscribe =
                transport.decodeSentPackets().filterIsInstance<Subscribe>().first()
            assertEquals(1, sentSubscribe.subscriptions.size)
            assertEquals("sensors/#", sentSubscribe.subscriptions[0].topicFilter)
            assertEquals(QoS.AT_LEAST_ONCE, sentSubscribe.subscriptions[0].maxQos)

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Subscribe Multiple Topics ---

    @Test
    fun subscribeMultipleTopics() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            transport.enqueuePacket(
                SubAck(
                    packetIdentifier = 1,
                    reasonCodes = listOf(ReasonCode.SUCCESS, ReasonCode.GRANTED_QOS_1),
                ),
            )

            client.subscribe(
                mapOf(
                    "topic/a" to QoS.AT_MOST_ONCE,
                    "topic/b" to QoS.AT_LEAST_ONCE,
                ),
            )
            advanceUntilIdle()

            val sentSubscribe =
                transport.decodeSentPackets().filterIsInstance<Subscribe>().first()
            assertEquals(2, sentSubscribe.subscriptions.size)

            val filters = sentSubscribe.subscriptions.map { it.topicFilter }.toSet()
            assertTrue(filters.contains("topic/a"))
            assertTrue(filters.contains("topic/b"))

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Unsubscribe ---

    @Test
    fun unsubscribe() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            // Subscribe first
            transport.enqueuePacket(
                SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS)),
            )
            client.subscribe("topic/remove", QoS.AT_MOST_ONCE)
            advanceUntilIdle()

            // Unsubscribe
            transport.enqueuePacket(
                UnsubAck(packetIdentifier = 2, reasonCodes = listOf(ReasonCode.SUCCESS)),
            )
            client.unsubscribe("topic/remove")
            advanceUntilIdle()

            val sentUnsub =
                transport.decodeSentPackets().filterIsInstance<Unsubscribe>().first()
            assertEquals(listOf("topic/remove"), sentUnsub.topicFilters)

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Receive Messages ---

    @Test
    fun receiveMessages() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            // Subscribe
            transport.enqueuePacket(
                SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.SUCCESS)),
            )
            client.subscribe("data/#")
            advanceUntilIdle()

            // Simulate incoming publish from broker
            transport.enqueuePacket(
                Publish(
                    topicName = "data/temp",
                    payload = "25.5".encodeToByteArray(),
                    qos = QoS.AT_MOST_ONCE,
                    properties = MqttProperties.EMPTY,
                ),
            )

            val received =
                withTimeout(5_000) {
                    val msg = kotlinx.coroutines.CompletableDeferred<MqttMessage>()
                    val job =
                        launch {
                            client.messages.collect {
                                msg.complete(it)
                            }
                        }
                    advanceUntilIdle()
                    val result = msg.await()
                    job.cancel()
                    result
                }

            assertEquals("data/temp", received.topic)
            assertEquals(
                "25.5",
                received.payload.toByteArray().decodeToString(),
            )

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Close Releases Resources ---

    @Test
    fun closeSendsDisconnectAndClosesTransport() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, client.connectionState.value)

            client.close()
            advanceUntilIdle()

            assertEquals(ConnectionState.Disconnected.Idle, client.connectionState.value)
            // close() should have sent DISCONNECT and closed the transport
            val sentDisconnect = transport.decodeSentPackets().last()
            assertIs<Disconnect>(sentDisconnect)
            assertTrue(!transport.isConnected, "Transport should be closed after close()")
        }

    // --- Publish ByteArray Convenience ---

    @Test
    fun publishByteArrayConvenience() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
            client.publish(MqttMessage(topic = "bytes/topic", payload = payload))
            advanceUntilIdle()

            val sentPublish = transport.decodeSentPackets().last()
            assertIs<Publish>(sentPublish)
            assertEquals("bytes/topic", sentPublish.topicName)
            assertTrue(sentPublish.payload.contentEquals(payload))

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Manual Disconnect Does Not Auto-Reconnect ---

    @Test
    fun manualDisconnectDoesNotAutoReconnect() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(autoReconnect = true)
            val client = connectedClient(transport, config = config, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, client.connectionState.value)

            // Manual disconnect should NOT trigger reconnect even with autoReconnect=true
            client.disconnect()
            advanceUntilIdle()

            assertEquals(ConnectionState.Disconnected.Idle, client.connectionState.value)

            // Give time for a reconnect to potentially trigger (it shouldn't)
            advanceUntilIdle()
            assertEquals(ConnectionState.Disconnected.Idle, client.connectionState.value)

            client.close()
        }

    // --- Subscribe Only Records Successful SUBACK ---

    @Test
    fun subscribeIgnoresFailedSubAck() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(autoReconnect = false)
            val client = connectedClient(transport, config = config, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            // SUBACK with failure reason code
            transport.enqueuePacket(
                SubAck(
                    packetIdentifier = 1,
                    reasonCodes = listOf(ReasonCode.NOT_AUTHORIZED),
                ),
            )
            client.subscribe("secret/topic", QoS.AT_LEAST_ONCE)
            advanceUntilIdle()

            // Verify the SUBSCRIBE was sent
            val sentSubscribe =
                transport.decodeSentPackets().filterIsInstance<Subscribe>().first()
            assertEquals("secret/topic", sentSubscribe.subscriptions[0].topicFilter)

            client.disconnect()
            advanceUntilIdle()
            client.close()
        }

    // --- Use-after-close guard ---

    @Test
    fun connectAfterCloseThrowsIllegalState() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            client.close()
            advanceUntilIdle()

            assertFailsWith<IllegalStateException> {
                client.connect(endpoint)
            }
        }

    @Test
    fun publishAfterCloseThrowsIllegalState() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)
            client.connect(endpoint)
            advanceUntilIdle()

            client.close()
            advanceUntilIdle()

            assertFailsWith<IllegalStateException> {
                client.publish(MqttMessage(topic = "t", payload = ByteString()))
            }
        }

    // --- CancellationException preservation in connect() ---

    @Test
    fun cancelledConnectPropagatesCancellationException() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig()
            val client = MqttClient(config, transport, this)

            // Don't enqueue a ConnAck so connect() blocks waiting
            var caughtException: Throwable? = null
            val connectJob =
                launch {
                    try {
                        client.connect(endpoint)
                    } catch (e: Throwable) {
                        caughtException = e
                    }
                }
            // Yield to let the connect coroutine start and reach transport.receive()
            // without advancing virtual time past the awaitConnAck timeout
            yield()

            // Cancel the coroutine — should propagate CancellationException
            connectJob.cancel()
            advanceUntilIdle()
            connectJob.join()

            // Should be CancellationException, not MqttConnectionException
            assertTrue(
                caughtException is kotlinx.coroutines.CancellationException,
                "Expected CancellationException but got ${caughtException?.let { it::class.simpleName }}",
            )

            client.close()
        }

    // --- ProtocolError surfacing (F24) ---

    @Test
    fun connectionRejectedSurfacedFromBadConnAck() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig()
            val client = MqttClient(config, transport, this)

            // CONNACK with non-success reason code
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.NOT_AUTHORIZED,
                ),
            )

            val exception =
                assertFailsWith<MqttException.ConnectionRejected> {
                    client.connect(endpoint)
                }
            assertEquals(ReasonCode.NOT_AUTHORIZED, exception.reasonCode)

            client.close()
        }

    // --- Concurrent publish + subscribe (F25) ---

    @Test
    fun concurrentPublishAndSubscribe() =
        runTest {
            val transport = FakeTransport()
            val client = connectedClient(transport, scope = this)

            client.connect(endpoint)
            advanceUntilIdle()

            // Launch concurrent publish and subscribe
            val publishJob =
                launch {
                    // Respond to PUBLISH with PUBACK
                    launch {
                        while (true) {
                            yield()
                            val packets = transport.decodeSentPackets()
                            val publish = packets.filterIsInstance<Publish>().firstOrNull()
                            if (publish != null && publish.packetIdentifier != null) {
                                transport.enqueuePacket(
                                    org.meshtastic.mqtt.packet.PubAck(
                                        packetIdentifier = publish.packetIdentifier,
                                    ),
                                )
                                break
                            }
                        }
                    }
                    client.publish("concurrent/pub", "data", QoS.AT_LEAST_ONCE)
                }

            val subscribeJob =
                launch {
                    // Respond to SUBSCRIBE with SUBACK
                    launch {
                        while (true) {
                            yield()
                            val packets = transport.decodeSentPackets()
                            val subscribe = packets.filterIsInstance<Subscribe>().firstOrNull()
                            if (subscribe != null) {
                                transport.enqueuePacket(
                                    SubAck(
                                        packetIdentifier = subscribe.packetIdentifier,
                                        reasonCodes = listOf(ReasonCode.GRANTED_QOS_1),
                                    ),
                                )
                                break
                            }
                        }
                    }
                    client.subscribe("concurrent/sub", QoS.AT_LEAST_ONCE)
                }

            advanceUntilIdle()
            publishJob.join()
            subscribeJob.join()

            // Both operations should complete without deadlock or error
            val sentPackets = transport.decodeSentPackets()
            assertTrue(
                sentPackets.any { it is Publish && it.topicName == "concurrent/pub" },
                "PUBLISH should have been sent",
            )
            assertTrue(
                sentPackets.any { it is Subscribe },
                "SUBSCRIBE should have been sent",
            )

            client.close()
        }

    // --- Auto-reconnect ---

    @Test
    fun autoReconnectAfterConnectionLoss() =
        runTest {
            val transport = FakeTransport()
            val config = defaultConfig(autoReconnect = true)
            val client = connectedClient(transport, config, scope = this)

            // Initial connect
            client.connect(endpoint)
            advanceUntilIdle()
            assertEquals(ConnectionState.Connected, client.connectionState.value)

            // Subscribe so we can verify resubscription after reconnect
            transport.enqueuePacket(
                SubAck(packetIdentifier = 1, reasonCodes = listOf(ReasonCode.GRANTED_QOS_1)),
            )
            client.subscribe("test/topic", QoS.AT_LEAST_ONCE)
            advanceUntilIdle()

            // Set up callback to enqueue CONNACK + SUBACK when reconnect calls transport.connect()
            transport.onConnect = {
                transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
                transport.enqueuePacket(
                    SubAck(packetIdentifier = 2, reasonCodes = listOf(ReasonCode.GRANTED_QOS_1)),
                )
            }

            // Simulate connection loss: set the error, then wake up the read loop with a
            // dummy packet so it re-enters receive() and hits the error on the next call.
            transport.receiveError = RuntimeException("Connection reset")
            transport.enqueuePacket(PingResp)
            advanceUntilIdle()

            // Advance past reconnect base delay (100ms) to trigger reconnect attempt
            advanceTimeBy(200)
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, client.connectionState.value)

            // Verify reconnect sent a new CONNECT
            val connectPackets = transport.decodeSentPackets().filterIsInstance<Connect>()
            assertTrue(
                connectPackets.size >= 2,
                "Should have sent at least 2 CONNECT packets (initial + reconnect)",
            )

            transport.onConnect = null
            client.close()
            advanceUntilIdle()
        }
}
