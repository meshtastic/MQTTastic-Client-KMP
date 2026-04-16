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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that verify the MQTT 5.0 client against a real Mosquitto broker.
 *
 * These tests require a running MQTT broker on localhost:1883. Start one with:
 * ```
 * docker compose -f docker/docker-compose.yml up -d
 * ```
 *
 * Tests skip gracefully when no broker is available.
 */
@Suppress("TooManyFunctions")
class MqttIntegrationTest {
    private val brokerHost = System.getenv("MQTT_BROKER_HOST") ?: "localhost"
    private val brokerPort = System.getenv("MQTT_BROKER_PORT")?.toIntOrNull() ?: 1883
    private val endpoint = MqttEndpoint.Tcp(brokerHost, brokerPort)

    private fun createClient(config: MqttConfig = defaultConfig()): MqttClient = MqttClient(config)

    private fun defaultConfig(
        clientId: String = "test-${System.nanoTime()}",
        autoReconnect: Boolean = false,
    ): MqttConfig =
        MqttConfig(
            clientId = clientId,
            keepAliveSeconds = 30,
            cleanStart = true,
            autoReconnect = autoReconnect,
        )

    private fun brokerAvailable(): Boolean =
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(brokerHost, brokerPort), 1000)
            }
            true
        } catch (_: Exception) {
            false
        }

    private fun skipIfNoBroker(): Boolean {
        if (!brokerAvailable()) {
            println("SKIPPED: MQTT broker not available at $brokerHost:$brokerPort")
            return true
        }
        return false
    }

    // --- Connect and Disconnect ---

    @Test
    fun connectAndDisconnect() =
        runTest(timeout = 10.seconds) {
            if (skipIfNoBroker()) return@runTest
            val client = createClient()
            try {
                client.connect(endpoint)
                withTimeout(5_000) {
                    while (client.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                assertEquals(ConnectionState.CONNECTED, client.connectionState.value)

                client.disconnect()
                assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
            } finally {
                client.close()
            }
        }

    // --- Publish QoS 0 ---

    @Test
    fun publishQos0() =
        runTest(timeout = 10.seconds) {
            if (skipIfNoBroker()) return@runTest
            val client = createClient()
            try {
                client.connect(endpoint)
                withTimeout(5_000) {
                    while (client.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }

                // QoS 0 is fire-and-forget — no exception means success
                client.publish("integration/test/qos0", "hello qos0", QoS.AT_MOST_ONCE)

                client.disconnect()
            } finally {
                client.close()
            }
        }

    // --- Publish and Receive QoS 1 ---

    @Test
    fun publishAndReceiveQos1() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "integration/test/qos1/${System.nanoTime()}"

            val subscriber = createClient(defaultConfig(clientId = "sub-qos1-${System.nanoTime()}"))
            val publisher = createClient(defaultConfig(clientId = "pub-qos1-${System.nanoTime()}"))
            try {
                // Connect subscriber and subscribe
                subscriber.connect(endpoint)
                withTimeout(5_000) {
                    while (subscriber.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                subscriber.subscribe(topic, QoS.AT_LEAST_ONCE)
                delay(500) // Allow subscription to propagate

                // Collect messages in background
                val received = CompletableDeferred<MqttMessage>()
                val collectJob =
                    launch {
                        subscriber.messages.collect { received.complete(it) }
                    }

                // Connect publisher and publish
                publisher.connect(endpoint)
                withTimeout(5_000) {
                    while (publisher.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                publisher.publish(topic, "hello qos1", QoS.AT_LEAST_ONCE)

                // Wait for message
                val msg = withTimeout(5_000) { received.await() }
                assertEquals(topic, msg.topic)
                assertEquals("hello qos1", msg.payload.toByteArray().decodeToString())

                collectJob.cancel()
                publisher.disconnect()
                subscriber.disconnect()
            } finally {
                publisher.close()
                subscriber.close()
            }
        }

    // --- Publish and Receive QoS 2 ---

    @Test
    fun publishAndReceiveQos2() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "integration/test/qos2/${System.nanoTime()}"

            val subscriber = createClient(defaultConfig(clientId = "sub-qos2-${System.nanoTime()}"))
            val publisher = createClient(defaultConfig(clientId = "pub-qos2-${System.nanoTime()}"))
            try {
                subscriber.connect(endpoint)
                withTimeout(5_000) {
                    while (subscriber.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                subscriber.subscribe(topic, QoS.EXACTLY_ONCE)
                delay(500)

                val received = CompletableDeferred<MqttMessage>()
                val collectJob =
                    launch {
                        subscriber.messages.collect { received.complete(it) }
                    }

                publisher.connect(endpoint)
                withTimeout(5_000) {
                    while (publisher.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                publisher.publish(topic, "hello qos2", QoS.EXACTLY_ONCE)

                val msg = withTimeout(5_000) { received.await() }
                assertEquals(topic, msg.topic)
                assertEquals("hello qos2", msg.payload.toByteArray().decodeToString())

                collectJob.cancel()
                publisher.disconnect()
                subscriber.disconnect()
            } finally {
                publisher.close()
                subscriber.close()
            }
        }

    // --- Subscribe and Unsubscribe ---

    @Test
    fun subscribeAndUnsubscribe() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "integration/test/unsub/${System.nanoTime()}"

            val client = createClient()
            try {
                client.connect(endpoint)
                withTimeout(5_000) {
                    while (client.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }

                // Subscribe — should complete without error
                client.subscribe(topic, QoS.AT_LEAST_ONCE)
                delay(500)

                // Unsubscribe — should complete without error
                client.unsubscribe(topic)
                delay(500)

                // Publish after unsubscribe — should not receive
                val received = CompletableDeferred<MqttMessage>()
                val collectJob =
                    launch {
                        client.messages.collect { received.complete(it) }
                    }

                client.publish(topic, "after-unsub", QoS.AT_MOST_ONCE)
                delay(1_000)

                assertTrue(!received.isCompleted, "Should not receive messages after unsubscribe")

                collectJob.cancel()
                client.disconnect()
            } finally {
                client.close()
            }
        }

    // --- Retained Message ---

    @Test
    fun retainedMessage() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "integration/test/retained/${System.nanoTime()}"

            val publisher = createClient(defaultConfig(clientId = "pub-retain-${System.nanoTime()}"))
            try {
                // Publish a retained message
                publisher.connect(endpoint)
                withTimeout(5_000) {
                    while (publisher.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                publisher.publish(topic, "retained-payload", QoS.AT_LEAST_ONCE, retain = true)
                delay(500)
                publisher.disconnect()
            } finally {
                publisher.close()
            }

            // New subscriber should receive the retained message
            val subscriber = createClient(defaultConfig(clientId = "sub-retain-${System.nanoTime()}"))
            try {
                subscriber.connect(endpoint)
                withTimeout(5_000) {
                    while (subscriber.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }

                val received = CompletableDeferred<MqttMessage>()
                val collectJob =
                    launch {
                        subscriber.messages.collect { received.complete(it) }
                    }

                subscriber.subscribe(topic, QoS.AT_LEAST_ONCE)

                val msg = withTimeout(5_000) { received.await() }
                assertEquals(topic, msg.topic)
                assertEquals("retained-payload", msg.payload.toByteArray().decodeToString())
                assertTrue(msg.retain, "Message should be marked as retained")

                collectJob.cancel()
                subscriber.disconnect()
            } finally {
                subscriber.close()
                // Clean up the retained message by publishing an empty retained message
                val cleaner = createClient(defaultConfig(clientId = "cleaner-${System.nanoTime()}"))
                try {
                    cleaner.connect(endpoint)
                    withTimeout(5_000) {
                        while (cleaner.connectionState.value != ConnectionState.CONNECTED) {
                            delay(50)
                        }
                    }
                    cleaner.publish(
                        MqttMessage(
                            topic = topic,
                            payload = byteArrayOf(),
                            qos = QoS.AT_LEAST_ONCE,
                            retain = true,
                        ),
                    )
                    delay(500)
                    cleaner.disconnect()
                } finally {
                    cleaner.close()
                }
            }
        }

    // --- Will Message ---

    @Test
    fun willMessage() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val willTopic = "integration/test/will/${System.nanoTime()}"

            val subscriber = createClient(defaultConfig(clientId = "sub-will-${System.nanoTime()}"))
            try {
                // Subscribe to the will topic
                subscriber.connect(endpoint)
                withTimeout(5_000) {
                    while (subscriber.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                subscriber.subscribe(willTopic, QoS.AT_LEAST_ONCE)
                delay(500)

                val received = CompletableDeferred<MqttMessage>()
                val collectJob =
                    launch {
                        subscriber.messages.collect { received.complete(it) }
                    }

                // Connect a client with a will, then abruptly close
                val willConfig =
                    WillConfig(
                        topic = willTopic,
                        payload = "client-died".encodeToByteArray(),
                        qos = QoS.AT_LEAST_ONCE,
                    )
                val willClient =
                    createClient(
                        MqttConfig(
                            clientId = "will-client-${System.nanoTime()}",
                            keepAliveSeconds = 2,
                            cleanStart = true,
                            autoReconnect = false,
                            will = willConfig,
                        ),
                    )
                try {
                    willClient.connect(endpoint)
                    withTimeout(5_000) {
                        while (willClient.connectionState.value != ConnectionState.CONNECTED) {
                            delay(50)
                        }
                    }
                    // Abrupt close without DISCONNECT — broker should publish the will
                    willClient.close()
                } catch (_: Exception) {
                    // Expected — we're forcefully closing
                }

                // Wait for the will message (broker publishes after keepalive * 1.5 timeout)
                val msg = withTimeout(10_000) { received.await() }
                assertEquals(willTopic, msg.topic)
                assertEquals("client-died", msg.payload.toByteArray().decodeToString())

                collectJob.cancel()
                subscriber.disconnect()
            } finally {
                subscriber.close()
            }
        }

    // --- Multiple Messages on Same Topic ---

    @Test
    fun multipleMessagesOnSameTopic() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "integration/test/multi/${System.nanoTime()}"
            val messageCount = 5

            val subscriber = createClient(defaultConfig(clientId = "sub-multi-${System.nanoTime()}"))
            val publisher = createClient(defaultConfig(clientId = "pub-multi-${System.nanoTime()}"))
            try {
                subscriber.connect(endpoint)
                withTimeout(5_000) {
                    while (subscriber.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }
                subscriber.subscribe(topic, QoS.AT_LEAST_ONCE)
                delay(500)

                val messages = mutableListOf<MqttMessage>()
                val allReceived = CompletableDeferred<Unit>()
                val collectJob =
                    launch {
                        subscriber.messages.collect { msg ->
                            messages.add(msg)
                            if (messages.size >= messageCount) {
                                allReceived.complete(Unit)
                            }
                        }
                    }

                publisher.connect(endpoint)
                withTimeout(5_000) {
                    while (publisher.connectionState.value != ConnectionState.CONNECTED) {
                        delay(50)
                    }
                }

                repeat(messageCount) { i ->
                    publisher.publish(topic, "message-$i", QoS.AT_LEAST_ONCE)
                }

                withTimeout(5_000) { allReceived.await() }
                assertTrue(messages.size >= messageCount)

                collectJob.cancel()
                publisher.disconnect()
                subscriber.disconnect()
            } finally {
                publisher.close()
                subscriber.close()
            }
        }
}
