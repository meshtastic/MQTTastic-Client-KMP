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

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.meshtastic.mqtt.packet.Subscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Diagnostic test that replicates the **exact** Meshtastic-Android MQTT proxy flow against
 * the Portugal broker (mqtt.meshtastic.pt:8883) with full DEBUG-level logging.
 *
 * Purpose: trace the "Not enough bytes for boolean at offset 4" decode crash that users
 * report after the first publish succeeds but subsequent publishes fail.
 *
 * The test mimics:
 * - MQTTRepositoryImpl: connect → subscribe (QoS 1, noLocal) → fire-and-forget publishes
 * - autoReconnect = true, keepAliveSeconds = 30
 * - Subscribes to real Meshtastic topic patterns so incoming traffic from the mesh is received
 *
 * Run with:
 *   ./gradlew :library:jvmTest --tests "*.MqttPtDiagnosticTest" --info 2>&1 | tee /tmp/pt-diag.log
 */
class MqttPtDiagnosticTest {
    private val brokerHost = "mqtt.meshtastic.pt"
    private val brokerPort = 1883
    private val endpoint = MqttEndpoint.Tcp(brokerHost, brokerPort, tls = false)
    private val username = "meshdev"
    private val password = "large4cats"

    // Meshtastic topic constants (mirrors MQTTRepositoryImpl)
    private val rootTopic = "msh"
    private val topicLevel = "/2/e/"
    private val testChannelId = "LongFast"

    /** Verbose logger that timestamps every line for post-mortem analysis. */
    private val verboseLogger =
        object : MqttLogger {
            override fun log(
                level: MqttLogLevel,
                tag: String,
                message: String,
                throwable: Throwable?,
            ) {
                val ts = java.time.Instant.now()
                println("[$ts][$level] $tag: $message")
                throwable?.let {
                    println("[$ts]  ↳ ${it::class.simpleName}: ${it.message}")
                    it.cause?.let { c ->
                        println("[$ts]  ↳ caused by ${c::class.simpleName}: ${c.message}")
                    }
                }
            }
        }

    private fun createClient(
        clientId: String = "pt-diag-${System.nanoTime()}",
        autoReconnect: Boolean = true,
    ): MqttClient =
        MqttClient(clientId) {
            keepAliveSeconds = 30
            cleanStart = true
            this.username = this@MqttPtDiagnosticTest.username
            password(this@MqttPtDiagnosticTest.password)
            this.autoReconnect = autoReconnect
            logLevel = MqttLogLevel.TRACE
            logger = verboseLogger
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

    /**
     * Wraps a test body so that [MqttException.ConnectionRejected] is treated as a skip
     * (the broker is TCP-reachable but rejects the MQTT CONNECT — e.g. credential rotation).
     */
    private fun runOrSkipOnReject(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking {
            try {
                block()
            } catch (e: MqttException.ConnectionRejected) {
                println("SKIPPED: broker rejected MQTT connection (reason=${e.reasonCode})")
            }
        }

    // ─── Test: Full Meshtastic Android proxy flow ───

    /**
     * Replicates the exact flow from MQTTRepositoryImpl.proxyMessageFlow:
     * 1. Create client with autoReconnect=true, keepAlive=30s
     * 2. Collect messages in background (like the Android `launch { messages.collect }`)
     * 3. Collect connection state in background (like the Android state observer)
     * 4. Connect to broker
     * 5. Subscribe to Meshtastic topics with QoS 1 + noLocal
     * 6. Fire-and-forget publishes with delays (simulating mesh packets)
     *
     * Logs every state transition, every incoming message, and every publish result.
     */
    @Test
    fun fullMeshtasticProxyFlow() =
        runOrSkipOnReject {
            if (skipIfNoBroker()) return@runOrSkipOnReject

            val nonce = System.nanoTime()
            val clientId = "MeshtasticAndroidMqttProxy-!diag$nonce"
            val client = createClient(clientId = clientId)

            // Meshtastic-style subscriptions (matches Android MQTTRepositoryImpl)
            val subscriptions =
                listOf(
                    Subscription(
                        "$rootTopic$topicLevel$testChannelId/+",
                        maxQos = QoS.AT_LEAST_ONCE,
                        noLocal = true,
                    ),
                    Subscription(
                        "${rootTopic}${topicLevel}PKI/+",
                        maxQos = QoS.AT_LEAST_ONCE,
                        noLocal = true,
                    ),
                )

            val stateTransitions = mutableListOf<String>()
            val receivedMessages = mutableListOf<String>()
            val publishResults = mutableListOf<String>()

            try {
                // Background: collect messages (mirrors Android's launch { messages.collect })
                val msgJob =
                    launch {
                        client.messages.collect { msg ->
                            val summary =
                                "topic=${msg.topic}, qos=${msg.qos}, " +
                                    "payloadSize=${msg.payload.size}, retain=${msg.retain}"
                            receivedMessages += summary
                            println("  ▶ INCOMING MESSAGE: $summary")
                        }
                    }

                // Background: observe connection state (mirrors Android's state collector)
                val stateJob =
                    launch {
                        client.connectionState.collect { state ->
                            val desc =
                                when (state) {
                                    ConnectionState.Connecting -> {
                                        "Connecting"
                                    }

                                    ConnectionState.Connected -> {
                                        "Connected"
                                    }

                                    is ConnectionState.Reconnecting -> {
                                        "Reconnecting(attempt=${state.attempt}, " +
                                            "error=${state.lastError?.message})"
                                    }

                                    is ConnectionState.Disconnected -> {
                                        "Disconnected(reason=${state.reason?.message})"
                                    }
                                }
                            stateTransitions += desc
                            println("  ★ STATE: $desc")
                        }
                    }

                // Step 1: Connect
                println("\n═══ STEP 1: Connecting to $endpoint ═══")
                client.connect(endpoint)
                withTimeout(15.seconds) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }
                println("  ✓ Connected")

                // Step 2: Subscribe (like Android does immediately after connect)
                println("\n═══ STEP 2: Subscribing to ${subscriptions.size} topics ═══")
                subscriptions.forEach { println("  → ${it.topicFilter} (QoS=${it.maxQos}, noLocal=${it.noLocal})") }
                client.subscribe(subscriptions)
                println("  ✓ Subscribed")

                // Step 3: Wait a moment for any incoming retained/traffic
                println("\n═══ STEP 3: Waiting 3s for incoming traffic ═══")
                delay(3000)
                println("  Received ${receivedMessages.size} messages so far")

                // Step 4: Sequential publishes with delays (simulating mesh packets)
                val publishTopic = "$rootTopic$topicLevel$testChannelId/!diag$nonce"
                val messageCount = 10
                println("\n═══ STEP 4: Publishing $messageCount messages to $publishTopic ═══")

                var successCount = 0
                var failCount = 0
                repeat(messageCount) { i ->
                    val payload = "diag-packet-$i-${System.currentTimeMillis()}"
                    try {
                        val result =
                            client.publish(
                                MqttMessage(
                                    topic = publishTopic,
                                    payload = payload.encodeToByteArray(),
                                    qos = QoS.AT_LEAST_ONCE,
                                    retain = false,
                                ),
                            )
                        successCount++
                        publishResults += "OK($i): reasonCode=$result"
                        println("  ✓ Publish $i OK (reason=$result, state=${client.connectionState.value})")
                    } catch (e: Exception) {
                        failCount++
                        val detail =
                            "${e::class.simpleName}: ${e.message}" +
                                (e.cause?.let { " [caused by ${it::class.simpleName}: ${it.message}]" } ?: "")
                        publishResults += "FAIL($i): $detail"
                        println("  ✗ Publish $i FAILED: $detail")
                        println("    State: ${client.connectionState.value}")
                    }

                    // 2s delay between publishes (realistic mesh packet interval)
                    delay(2000)
                }

                // Step 5: Wait for any late-arriving messages or reconnect cycles
                println("\n═══ STEP 5: Waiting 5s for late traffic / reconnect ═══")
                delay(5000)

                // Summary
                println("\n" + "═".repeat(60))
                println("SUMMARY")
                println("═".repeat(60))
                println("Publishes: $successCount/$messageCount succeeded, $failCount failed")
                println("Incoming messages: ${receivedMessages.size}")
                println("State transitions: ${stateTransitions.joinToString(" → ")}")
                println()
                publishResults.forEachIndexed { idx, r -> println("  publish[$idx]: $r") }
                println()
                stateTransitions.forEachIndexed { idx, s -> println("  state[$idx]: $s") }

                // Assert all publishes succeeded
                assertEquals(
                    messageCount,
                    successCount,
                    "All $messageCount publishes should succeed. " +
                        "Failures: ${publishResults.filter { it.startsWith("FAIL") }}",
                )

                // Assert we stayed connected (no unexpected reconnects after initial connect)
                val reconnectCount = stateTransitions.count { it.startsWith("Reconnecting") }
                assertTrue(
                    reconnectCount == 0,
                    "Should not reconnect during steady-state operation. " +
                        "Reconnects: $reconnectCount. Transitions: $stateTransitions",
                )

                msgJob.cancel()
                stateJob.cancel()
                client.disconnect()
            } finally {
                client.close()
            }
        }

    // ─── Test: Connect + subscribe to real traffic (listen-only) ───

    /**
     * Connects and subscribes to the real LongFast topic, then just listens for 30 seconds.
     * This catches decode errors on incoming PUBLISH packets from real mesh devices.
     */
    @Test
    fun listenToRealMeshTraffic() =
        runOrSkipOnReject {
            if (skipIfNoBroker()) return@runOrSkipOnReject

            val client = createClient(clientId = "pt-listener-${System.nanoTime()}", autoReconnect = true)
            val errors = mutableListOf<String>()

            try {
                val stateJob =
                    launch {
                        client.connectionState.collect { state ->
                            println("  ★ STATE: $state")
                            if (state is ConnectionState.Disconnected && state.reason != null) {
                                errors += "Unexpected disconnect: ${state.reason.message}"
                            }
                            if (state is ConnectionState.Reconnecting) {
                                errors += "Reconnecting(attempt=${state.attempt}): ${state.lastError?.message}"
                            }
                        }
                    }

                var msgCount = 0
                val msgJob =
                    launch {
                        client.messages.collect { msg ->
                            msgCount++
                            println(
                                "  ▶ MSG #$msgCount: topic=${msg.topic}, " +
                                    "qos=${msg.qos}, size=${msg.payload.size}",
                            )
                        }
                    }

                println("Connecting...")
                client.connect(endpoint)
                withTimeout(15.seconds) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }

                // Subscribe to real mesh topics with wildcards
                val subs =
                    listOf(
                        Subscription(
                            "$rootTopic$topicLevel$testChannelId/+",
                            maxQos = QoS.AT_LEAST_ONCE,
                            noLocal = true,
                        ),
                        Subscription(
                            "${rootTopic}${topicLevel}PKI/+",
                            maxQos = QoS.AT_LEAST_ONCE,
                            noLocal = true,
                        ),
                    )
                println("Subscribing to ${subs.map { it.topicFilter }}")
                client.subscribe(subs)

                // Listen for 30 seconds
                println("Listening for 30s...")
                delay(30_000)

                println("\nReceived $msgCount messages, errors: ${errors.size}")
                errors.forEach { println("  ERROR: $it") }

                assertTrue(errors.isEmpty(), "Should have no errors during listen: $errors")

                stateJob.cancel()
                msgJob.cancel()
                client.disconnect()
            } finally {
                client.close()
            }
        }

    // ─── Test: Fire-and-forget concurrent publishes (mirrors Android's scope.launch pattern) ───

    /**
     * Replicates the Android fire-and-forget pattern where publish() is called from
     * scope.launch without awaiting the result, and multiple publishes may overlap.
     */
    @Test
    fun fireAndForgetConcurrentPublishes() =
        runOrSkipOnReject {
            if (skipIfNoBroker()) return@runOrSkipOnReject

            val nonce = System.nanoTime()
            val client = createClient(clientId = "pt-fandf-$nonce", autoReconnect = true)
            val errors = mutableListOf<String>()

            try {
                val stateJob =
                    launch {
                        client.connectionState.collect { state ->
                            println("  ★ STATE: $state")
                        }
                    }

                client.connect(endpoint)
                withTimeout(15.seconds) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }

                // Subscribe first (like Android does)
                client.subscribe(
                    listOf(
                        Subscription(
                            "$rootTopic$topicLevel$testChannelId/+",
                            maxQos = QoS.AT_LEAST_ONCE,
                            noLocal = true,
                        ),
                    ),
                )

                // Fire-and-forget: launch 10 concurrent publishes (like Android scope.launch)
                val publishTopic = "$rootTopic$topicLevel$testChannelId/!fandf$nonce"
                val jobs =
                    (0 until 10).map { i ->
                        launch {
                            try {
                                client.publish(
                                    MqttMessage(
                                        topic = publishTopic,
                                        payload = "concurrent-$i".encodeToByteArray(),
                                        qos = QoS.AT_LEAST_ONCE,
                                        retain = false,
                                    ),
                                )
                                println("  ✓ Concurrent publish $i OK")
                            } catch (e: Exception) {
                                errors += "publish-$i: ${e::class.simpleName}: ${e.message}"
                                println("  ✗ Concurrent publish $i FAILED: ${e.message}")
                            }
                        }
                    }

                // Wait for all fire-and-forget publishes to complete
                jobs.forEach { it.join() }

                // Wait a beat for any broker-side reactions
                delay(3000)

                println("\nConcurrent publish errors: ${errors.size}")
                errors.forEach { println("  $it") }

                assertTrue(errors.isEmpty(), "All concurrent publishes should succeed: $errors")

                stateJob.cancel()
                client.disconnect()
            } finally {
                client.close()
            }
        }

    // ─── Test: Publish then immediately subscribe (order-of-operations stress) ───

    /**
     * Verifies that a publish immediately after subscribe doesn't race with SUBACK processing.
     */
    @Test
    fun publishImmediatelyAfterSubscribe() =
        runOrSkipOnReject {
            if (skipIfNoBroker()) return@runOrSkipOnReject

            val nonce = System.nanoTime()
            val client = createClient(clientId = "pt-immed-$nonce", autoReconnect = false)

            try {
                client.connect(endpoint)
                withTimeout(15.seconds) {
                    while (client.connectionState.value != ConnectionState.Connected) {
                        delay(50)
                    }
                }

                val topic = "$rootTopic$topicLevel$testChannelId/!immed$nonce"

                // Subscribe and publish with zero gap between them
                client.subscribe(
                    listOf(
                        Subscription(
                            "$rootTopic$topicLevel$testChannelId/+",
                            maxQos = QoS.AT_LEAST_ONCE,
                            noLocal = true,
                        ),
                    ),
                )

                // Immediately publish — no delay
                val result =
                    client.publish(
                        MqttMessage(
                            topic = topic,
                            payload = "immediate-after-sub".encodeToByteArray(),
                            qos = QoS.AT_LEAST_ONCE,
                            retain = false,
                        ),
                    )
                println("Immediate publish after subscribe: reason=$result")

                // Second publish right after
                val result2 =
                    client.publish(
                        MqttMessage(
                            topic = topic,
                            payload = "immediate-after-sub-2".encodeToByteArray(),
                            qos = QoS.AT_LEAST_ONCE,
                            retain = false,
                        ),
                    )
                println("Second immediate publish: reason=$result2")

                client.disconnect()
            } finally {
                client.close()
            }
        }
}
