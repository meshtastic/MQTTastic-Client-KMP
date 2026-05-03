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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests exercising the MQTTastic client against mqtt.meshtastic.pt (TLS).
 *
 * These verify TLS connectivity, authentication, pub/sub round-trip, and the probe API
 * against a real remote broker. The Portugal team has temporarily disabled geoblocking
 * for this testing window.
 *
 * Run with: ./gradlew :library:jvmTest --tests "*.MqttPortugalIntegrationTest"
 */
class MqttPortugalIntegrationTest {
    private val brokerHost = "mqtt.meshtastic.pt"
    private val brokerPort = 8883
    private val endpoint = MqttEndpoint.Tcp(brokerHost, brokerPort, tls = true)
    private val username = "meshdev"
    private val password = "large4cats"

    private fun createClient(
        clientId: String = "mqttastic-pt-test-${System.nanoTime()}",
        autoReconnect: Boolean = false,
    ): MqttClient =
        MqttClient(clientId) {
            keepAliveSeconds = 30
            cleanStart = true
            this.username = this@MqttPortugalIntegrationTest.username
            password(this@MqttPortugalIntegrationTest.password)
            this.autoReconnect = autoReconnect
            logLevel = MqttLogLevel.DEBUG
            logger =
                object : MqttLogger {
                    override fun log(
                        level: MqttLogLevel,
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        println("[$level] $tag: $message")
                        throwable?.let { println("  cause: $it") }
                    }
                }
        }

    private fun brokerAvailable(): Boolean =
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(brokerHost, brokerPort), 5000)
            }
            true
        } catch (_: Exception) {
            false
        }

    private fun skipIfNoBroker(): Boolean {
        if (!brokerAvailable()) {
            println("SKIPPED: $brokerHost:$brokerPort not reachable (geoblocking may be active)")
            return true
        }
        return false
    }

    // --- Probe (one-shot connectivity check) ---

    @Test
    fun probeTlsConnection() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val result =
                MqttClient.probe(endpoint, timeoutMs = 10_000) {
                    this.username = this@MqttPortugalIntegrationTest.username
                    password(this@MqttPortugalIntegrationTest.password)
                }
            assertIs<ProbeResult.Success>(result, "Probe should succeed, got: $result")
            println("Probe OK — server info: ${result.serverInfo}")
        }

    // --- TLS Connect + Disconnect ---

    @Test
    fun tlsConnectAndDisconnect() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val client = createClient()
            try {
                client.connect(endpoint)
                withTimeout(10_000) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }
                assertEquals(ConnectionState.Connected, client.connectionState.value)
                println("TLS connect OK")

                client.disconnect()
                assertEquals(ConnectionState.Disconnected.Idle, client.connectionState.value)
                println("Disconnect OK")
            } finally {
                client.close()
            }
        }

    // --- Publish QoS 0 (fire-and-forget over TLS) ---

    @Test
    fun publishQos0OverTls() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val client = createClient()
            try {
                client.connect(endpoint)
                withTimeout(10_000) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }
                client.publish("msh/test/pt/qos0", "hello from mqttastic qos0", QoS.AT_MOST_ONCE)
                println("QoS 0 publish OK")
                client.disconnect()
            } finally {
                client.close()
            }
        }

    // --- Publish + Subscribe QoS 1 round-trip ---

    @Test
    fun publishAndReceiveQos1OverTls() =
        runTest(timeout = 20.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "msh/test/pt/qos1/${System.nanoTime()}"

            val subscriber = createClient(clientId = "pt-sub-q1-${System.nanoTime()}")
            val publisher = createClient(clientId = "pt-pub-q1-${System.nanoTime()}")
            try {
                // Subscribe first
                subscriber.connect(endpoint)
                withTimeout(10_000) {
                    while (subscriber.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }
                subscriber.subscribe(topic, QoS.AT_LEAST_ONCE)
                delay(500)

                val received = CompletableDeferred<MqttMessage>()
                val collectJob =
                    launch {
                        subscriber.messages.collect { received.complete(it) }
                    }

                // Publish
                publisher.connect(endpoint)
                withTimeout(10_000) {
                    while (publisher.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }
                publisher.publish(topic, "hello from portugal qos1", QoS.AT_LEAST_ONCE)

                // Verify round-trip
                val msg = withTimeout(10_000) { received.await() }
                assertEquals(topic, msg.topic)
                assertEquals("hello from portugal qos1", msg.payload.toByteArray().decodeToString())
                println("QoS 1 pub/sub round-trip OK")

                collectJob.cancel()
                publisher.disconnect()
                subscriber.disconnect()
            } finally {
                publisher.close()
                subscriber.close()
            }
        }

    // --- Publish + Subscribe QoS 2 round-trip ---

    @Test
    fun publishAndReceiveQos2OverTls() =
        runTest(timeout = 20.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "msh/test/pt/qos2/${System.nanoTime()}"

            val subscriber = createClient(clientId = "pt-sub-q2-${System.nanoTime()}")
            val publisher = createClient(clientId = "pt-pub-q2-${System.nanoTime()}")
            try {
                subscriber.connect(endpoint)
                withTimeout(10_000) {
                    while (subscriber.connectionState.value != ConnectionState.Connected) {
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
                withTimeout(10_000) {
                    while (publisher.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }
                publisher.publish(topic, "hello from portugal qos2", QoS.EXACTLY_ONCE)

                val msg = withTimeout(10_000) { received.await() }
                assertEquals(topic, msg.topic)
                assertEquals("hello from portugal qos2", msg.payload.toByteArray().decodeToString())
                println("QoS 2 pub/sub round-trip OK")

                collectJob.cancel()
                publisher.disconnect()
                subscriber.disconnect()
            } finally {
                publisher.close()
                subscriber.close()
            }
        }

    // --- Multiple messages burst ---

    @Test
    fun burstPublishOverTls() =
        runTest(timeout = 25.seconds) {
            if (skipIfNoBroker()) return@runTest
            val topic = "msh/test/pt/burst/${System.nanoTime()}"
            val count = 10

            val subscriber = createClient(clientId = "pt-sub-burst-${System.nanoTime()}")
            val publisher = createClient(clientId = "pt-pub-burst-${System.nanoTime()}")
            try {
                subscriber.connect(endpoint)
                withTimeout(10_000) {
                    while (subscriber.connectionState.value != ConnectionState.Connected) {
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
                            if (messages.size >= count) allReceived.complete(Unit)
                        }
                    }

                publisher.connect(endpoint)
                withTimeout(10_000) {
                    while (publisher.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }

                repeat(count) { i ->
                    publisher.publish(topic, "burst-$i", QoS.AT_LEAST_ONCE)
                }

                withTimeout(15_000) { allReceived.await() }
                assertTrue(messages.size >= count, "Expected $count messages, got ${messages.size}")
                println("Burst publish OK — received ${messages.size}/$count messages")

                collectJob.cancel()
                publisher.disconnect()
                subscriber.disconnect()
            } finally {
                publisher.close()
                subscriber.close()
            }
        }

    // --- Probe with bad credentials should be rejected ---

    @Test
    fun probeWithBadCredentialsIsRejected() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val result =
                MqttClient.probe(endpoint, timeoutMs = 10_000) {
                    this.username = "bogus"
                    password("wrong")
                }
            assertIs<ProbeResult.Rejected>(result, "Bad credentials should be rejected, got: $result")
            println("Bad-credentials probe correctly rejected: ${result.reasonCode}")
        }

    // --- Probe with no TLS to TLS port should fail ---

    @Test
    fun probePlainTextToTlsPortFails() =
        runTest(timeout = 15.seconds) {
            if (skipIfNoBroker()) return@runTest
            val plainEndpoint = MqttEndpoint.Tcp(brokerHost, brokerPort, tls = false)
            val result =
                MqttClient.probe(plainEndpoint, timeoutMs = 10_000) {
                    this.username = this@MqttPortugalIntegrationTest.username
                    password(this@MqttPortugalIntegrationTest.password)
                }
            // Sending plaintext to a TLS port should not succeed
            assertTrue(result !is ProbeResult.Success, "Plaintext to TLS port should fail, got: $result")
            println("Plaintext-to-TLS probe correctly failed: $result")
        }
}
