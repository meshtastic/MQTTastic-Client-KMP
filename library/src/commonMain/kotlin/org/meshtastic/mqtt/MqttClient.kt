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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.SubAck
import org.meshtastic.mqtt.packet.Subscription
import kotlin.concurrent.Volatile

/**
 * MQTT 5.0 client for Kotlin Multiplatform.
 *
 * `MqttClient` is the primary entry point for connecting to MQTT brokers,
 * publishing messages, and receiving subscriptions. It wraps the internal
 * connection state machine with a coroutine-friendly, Flow-based API.
 *
 * ## Creating a client
 *
 * Use the [MqttClient] factory function with a client ID and optional
 * configuration block (similar to Ktor's `HttpClient { }` pattern):
 *
 * ```kotlin
 * val client = MqttClient("sensor-hub") {
 *     keepAliveSeconds = 30
 *     autoReconnect = true
 * }
 * ```
 *
 * Or construct directly with an [MqttConfig]:
 *
 * ```kotlin
 * val client = MqttClient(MqttConfig(clientId = "sensor-hub", keepAliveSeconds = 30))
 * ```
 *
 * ## Connecting to a broker
 *
 * ```kotlin
 * // TCP (JVM, Android, iOS, macOS, Linux, Windows)
 * client.connect(MqttEndpoint.Tcp("broker.example.com", port = 1883))
 *
 * // TCP with TLS
 * client.connect(MqttEndpoint.Tcp("broker.example.com", port = 8883, tls = true))
 *
 * // WebSocket (wasmJs / browser)
 * client.connect(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt"))
 *
 * // Parse from URI string
 * client.connect(MqttEndpoint.parse("ssl://broker.example.com:8883"))
 * ```
 *
 * ## Publishing messages
 *
 * ```kotlin
 * // String payload (convenience)
 * client.publish("sensors/temp", "22.5", qos = QoS.AT_LEAST_ONCE)
 *
 * // Full control with MqttMessage
 * client.publish(MqttMessage(
 *     topic = "sensors/data",
 *     payload = ByteString(sensorBytes),
 *     qos = QoS.EXACTLY_ONCE,
 *     retain = true,
 * ))
 *
 * // With MQTT 5.0 properties
 * client.publish("commands/response", responseJson,
 *     properties = PublishProperties(
 *         contentType = "application/json",
 *         messageExpiryInterval = 3600,
 *     ),
 * )
 * ```
 *
 * ## Default publish options
 *
 * Set client-level defaults so you don't repeat QoS/retain on every call
 * (similar to Ktor's `defaultRequest {}`):
 *
 * ```kotlin
 * val client = MqttClient("sensor") {
 *     defaultQos = QoS.AT_LEAST_ONCE
 *     defaultRetain = true
 * }
 * client.publish("sensors/temp", "22.5")  // uses QoS 1 + retain
 * ```
 *
 * ## Subscribing and receiving messages
 *
 * Messages arrive via the [messages] flow. Start collecting **before** subscribing
 * to avoid missing messages:
 *
 * ```kotlin
 * // Collect all messages
 * launch { client.messages.collect { msg -> println("${msg.topic}: ${msg.payloadAsString()}") } }
 *
 * // Filter by exact topic
 * launch { client.messagesForTopic("sensors/temp").collect { /* ... */ } }
 *
 * // Filter by wildcard pattern
 * launch { client.messagesMatching("sensors/+/temp").collect { /* ... */ } }
 *
 * // Subscribe with QoS
 * client.subscribe("sensors/#", QoS.AT_LEAST_ONCE)
 *
 * // Bulk subscribe
 * client.subscribe(mapOf("sensors/#" to QoS.AT_LEAST_ONCE, "commands/#" to QoS.EXACTLY_ONCE))
 * ```
 *
 * ## Connection state
 *
 * Observe lifecycle transitions via [connectionState]:
 *
 * ```kotlin
 * client.connectionState.collect { state ->
 *     when (state) {
 *         ConnectionState.CONNECTED -> println("Online")
 *         ConnectionState.RECONNECTING -> println("Reconnecting...")
 *         ConnectionState.DISCONNECTED -> println("Offline")
 *         else -> { /* CONNECTING */ }
 *     }
 * }
 * ```
 *
 * ## Will messages
 *
 * Configure a will message using the DSL block in the config builder:
 *
 * ```kotlin
 * val client = MqttClient("sensor") {
 *     will {
 *         topic = "sensors/status"
 *         payload("offline")
 *         qos = QoS.AT_LEAST_ONCE
 *         retain = true
 *     }
 * }
 * ```
 *
 * ## Logging
 *
 * ```kotlin
 * val client = MqttClient("sensor") {
 *     logger = MqttLogger.println()
 *     logLevel = MqttLogLevel.DEBUG
 * }
 * ```
 *
 * ## Automatic reconnection
 *
 * Enabled by default. The client reconnects with exponential backoff and
 * re-establishes subscriptions automatically:
 *
 * ```kotlin
 * val client = MqttClient("sensor") {
 *     autoReconnect = true           // default
 *     reconnectBaseDelayMs = 1000    // initial delay
 *     reconnectMaxDelayMs = 30000    // max backoff
 * }
 * ```
 *
 * ## Enhanced authentication (§4.12)
 *
 * For SASL-style challenge/response flows:
 *
 * ```kotlin
 * val client = MqttClient("secure-client") {
 *     authenticationMethod = "SCRAM-SHA-256"
 *     authenticationData = ByteString(initialResponse)
 * }
 *
 * launch { client.authChallenges.collect { challenge -> client.sendAuthResponse(processChallenge(challenge)) } }
 * ```
 *
 * ## Cleanup
 *
 * Always close the client when done to release resources:
 *
 * ```kotlin
 * client.close()
 * ```
 *
 * Or use the scoped [use] extension for automatic cleanup:
 *
 * ```kotlin
 * MqttClient("sensor").use(MqttEndpoint.parse("tcp://broker:1883")) { client ->
 *     client.subscribe("sensors/#", QoS.AT_LEAST_ONCE)
 *     client.publish("sensors/temp", "22.5")
 *     delay(10_000)
 * }
 * ```
 *
 * ## Thread safety
 *
 * All public methods are `suspend` functions safe to call from any coroutine.
 * Internal state is protected by mutexes — concurrent calls to [publish], [subscribe],
 * and [disconnect] are serialized correctly.
 *
 * @param config Client configuration mapping to CONNECT packet fields (§3.1).
 * @param scope Coroutine scope for background jobs. Defaults to a new [SupervisorJob]
 *   on [Dispatchers.Default]. The client creates a child scope, so cancelling the
 *   provided scope also cancels all client operations.
 * @see MqttConfig
 * @see MqttEndpoint
 * @see MqttMessage
 */
@Suppress("TooManyFunctions")
public class MqttClient
    public constructor(
        private val config: MqttConfig,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) {
        private val log = MqttLoggerInternal(config.logger, config.logLevel)
        private var effectiveTransportFactory: (MqttEndpoint) -> MqttTransport = { createPlatformTransport(it) }

        /** Internal constructor for testing with a pre-built transport and injected scope. */
        internal constructor(config: MqttConfig, transport: MqttTransport, scope: CoroutineScope) :
            this(config, scope) {
            effectiveTransportFactory = { transport }
        }

        @Volatile
        private var closed = false

        private val scope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + scope.coroutineContext.minusKey(Job))

        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

        /**
         * Observable connection lifecycle state.
         *
         * Emits [ConnectionState] transitions as the client connects, disconnects,
         * and reconnects. Starts as [ConnectionState.DISCONNECTED].
         *
         * @see ConnectionState
         */
        public val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _messages =
            MutableSharedFlow<MqttMessage>(
                extraBufferCapacity = MESSAGE_BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /**
         * Flow of incoming MQTT messages from subscribed topics.
         *
         * Messages are emitted as they arrive from the broker. Each message includes
         * the topic, payload, QoS level, retain flag, and any MQTT 5.0 properties.
         * The flow uses a buffer of [MESSAGE_BUFFER_CAPACITY] messages; if the collector
         * is too slow, the oldest undelivered messages are dropped.
         *
         * Start collecting **before** calling [subscribe] to avoid missing messages.
         */
        public val messages: SharedFlow<MqttMessage> = _messages.asSharedFlow()

        private val _authChallenges =
            MutableSharedFlow<AuthChallenge>(
                extraBufferCapacity = AUTH_BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /**
         * Flow of authentication challenges from the broker during enhanced auth (§4.12).
         *
         * When [MqttConfig.authenticationMethod] is set, the broker may send AUTH packets
         * during the CONNECT handshake or at any point during the session. Each challenge
         * is emitted here. Respond by calling [sendAuthResponse] with the appropriate data.
         *
         * @see sendAuthResponse
         * @see AuthChallenge
         */
        public val authChallenges: SharedFlow<AuthChallenge> = _authChallenges.asSharedFlow()

        @Volatile
        private var connection: MqttConnection? = null

        @Volatile
        private var currentEndpoint: MqttEndpoint? = null
        private var messageForwardJob: Job? = null
        private var stateForwardJob: Job? = null
        private var authForwardJob: Job? = null
        private var redirectForwardJob: Job? = null
        private var reconnectJob: Job? = null

        @Volatile
        private var intentionalDisconnect = false

        private val subscriptionsMutex = Mutex()
        private val activeSubscriptions = mutableMapOf<String, Subscription>()
        private val connectionMutex = Mutex()

        /**
         * Connect to the MQTT broker at the specified [endpoint].
         *
         * Performs the CONNECT/CONNACK handshake. On success, starts forwarding
         * incoming messages to [messages] and connection state changes to [connectionState].
         *
         * If the broker responds with a server redirect (reason code `USE_ANOTHER_SERVER` or
         * `SERVER_MOVED` with a Server Reference property), the client automatically follows
         * the redirect up to [MAX_REDIRECTS] times (§4.13).
         *
         * @param endpoint Broker endpoint (TCP or WebSocket).
         * @throws MqttException.ConnectionRejected if the broker rejects the connection.
         */
        @Throws(MqttException::class, kotlin.coroutines.cancellation.CancellationException::class)
        public suspend fun connect(endpoint: MqttEndpoint) {
            try {
                connectionMutex.withLock {
                    check(!closed) { "Client has been closed and cannot be reused" }
                    // Close any existing connection to prevent resource leaks
                    connection?.let { existing ->
                        log.warn(TAG) { "Already connected — closing existing connection before reconnecting" }
                        try {
                            existing.disconnect()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
                        ) {
                            // Best-effort close of old connection
                        } finally {
                            stopForwardJobs()
                            connection = null
                        }
                    }

                    log.info(TAG) { "Connecting to $endpoint" }
                    intentionalDisconnect = false
                    currentEndpoint = endpoint
                    connectWithRedirect(endpoint, redirectsRemaining = MAX_REDIRECTS)
                }
            } catch (e: MqttConnectionException) {
                throw MqttException.ConnectionRejected(
                    reasonCode = e.reasonCode,
                    message = e.message ?: "Connection failed",
                    cause = e.cause,
                    serverReference = e.serverReference,
                )
            }
        }

        /**
         * Gracefully disconnect from the broker.
         *
         * Cancels any active reconnection attempts and sends a DISCONNECT packet
         * with [ReasonCode.SUCCESS].
         */
        public suspend fun disconnect() {
            connectionMutex.withLock {
                log.info(TAG) { "Disconnecting" }
                intentionalDisconnect = true
                reconnectJob?.cancel()
                reconnectJob = null
                try {
                    connection?.disconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
                ) {
                    // Best-effort disconnect — transport may already be closed
                } finally {
                    stopForwardJobs()
                    connection = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                    log.info(TAG) { "Disconnected" }
                }
            }
        }

        /**
         * Publish an [MqttMessage] to the broker.
         *
         * @param message The message to publish, including topic, payload, QoS, and properties.
         * @throws IllegalStateException if not connected.
         * @throws IllegalArgumentException if the topic name is invalid.
         * @throws MqttException.ConnectionLost on publish failure (QoS 1/2 only).
         */
        @Throws(
            IllegalStateException::class,
            IllegalArgumentException::class,
            MqttException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun publish(message: MqttMessage) {
            TopicValidator.validateTopicName(message.topic)
            log.debug(TAG) { "Publishing to '${message.topic}' qos=${message.qos} retain=${message.retain}" }
            wrapConnectionErrors { requireConnection().publish(message) }
        }

        /**
         * Publish a message with a [String] payload and optional MQTT 5.0 [properties].
         *
         * This is the primary convenience overload for string payloads. For binary
         * payloads, construct an [MqttMessage] directly with a [ByteArray] or [ByteString].
         *
         * When [qos] or [retain] are omitted (null), the client-level defaults from
         * [MqttConfig.defaultQos] and [MqttConfig.defaultRetain] are used — similar
         * to Ktor's `defaultRequest {}` pattern. This lets you configure publish
         * defaults once at client creation:
         *
         * ```kotlin
         * val client = MqttClient("sensor") {
         *     defaultQos = QoS.AT_LEAST_ONCE
         *     defaultRetain = true
         * }
         * // All convenience publishes now default to QoS 1 + retain
         * client.publish("sensors/temp", "22.5")
         * // Override per-call when needed
         * client.publish("sensors/temp", "22.5", qos = QoS.AT_MOST_ONCE, retain = false)
         * ```
         *
         * For the MQTT 5.0 request/response pattern (§4.10), set [PublishProperties.responseTopic]
         * and [PublishProperties.correlationData] in the [properties] parameter.
         *
         * @param topic The topic to publish to.
         * @param payload String payload (encoded as UTF-8).
         * @param qos Quality of service level, or `null` to use [MqttConfig.defaultQos].
         * @param retain Whether the message should be retained, or `null` to use [MqttConfig.defaultRetain].
         * @param properties MQTT 5.0 publish properties (default: none).
         */
        @Throws(
            IllegalStateException::class,
            IllegalArgumentException::class,
            MqttException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun publish(
            topic: String,
            payload: String,
            qos: QoS? = null,
            retain: Boolean? = null,
            properties: PublishProperties = PublishProperties(),
        ) {
            publish(
                MqttMessage(
                    topic = topic,
                    payload = ByteString(payload.encodeToByteArray()),
                    qos = qos ?: config.defaultQos,
                    retain = retain ?: config.defaultRetain,
                    properties = properties,
                ),
            )
        }

        /**
         * Subscribe to a single topic filter with the specified [qos] and MQTT 5.0 subscription options.
         *
         * Only records subscriptions whose SUBACK reason code indicates success.
         *
         * @param topicFilter The MQTT topic filter (e.g. "sensors/#").
         * @param qos Maximum QoS level for messages on this subscription.
         * @param noLocal If true, the server will not forward messages published by this client (§3.8.3.1).
         * @param retainAsPublished If true, retain flag from original publish is preserved (§3.8.3.1).
         * @param retainHandling Controls when retained messages are sent (§3.8.3.1).
         * @throws IllegalArgumentException if the topic filter is invalid (§4.7).
         * @throws IllegalStateException if not connected.
         */
        @Throws(
            IllegalArgumentException::class,
            IllegalStateException::class,
            MqttException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun subscribe(
            topicFilter: String,
            qos: QoS = QoS.AT_MOST_ONCE,
            noLocal: Boolean = false,
            retainAsPublished: Boolean = false,
            retainHandling: RetainHandling = RetainHandling.SEND_AT_SUBSCRIBE,
        ) {
            TopicValidator.validateTopicFilter(topicFilter)
            val sub =
                Subscription(
                    topicFilter = topicFilter,
                    maxQos = qos,
                    noLocal = noLocal,
                    retainAsPublished = retainAsPublished,
                    retainHandling = retainHandling,
                )
            log.debug(TAG) { "Subscribing to '$topicFilter' qos=$qos" }
            wrapConnectionErrors {
                val subAck = requireConnection().subscribe(listOf(sub), MqttProperties.EMPTY)
                recordSuccessfulSubscriptions(listOf(sub), subAck)
            }
            log.info(TAG) { "Subscribed to '$topicFilter'" }
        }

        /**
         * Subscribe to multiple topic filters with per-topic QoS levels.
         *
         * Only records subscriptions whose SUBACK reason code indicates success.
         *
         * @param topicFilters Map of topic filter to maximum QoS level.
         * @throws IllegalArgumentException if any topic filter is invalid (§4.7).
         * @throws IllegalStateException if not connected.
         */
        @Throws(
            IllegalArgumentException::class,
            IllegalStateException::class,
            MqttException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun subscribe(topicFilters: Map<String, QoS>) {
            topicFilters.keys.forEach { TopicValidator.validateTopicFilter(it) }
            val subscriptions =
                topicFilters.map { (filter, qos) ->
                    Subscription(topicFilter = filter, maxQos = qos)
                }
            log.debug(TAG) { "Subscribing to ${topicFilters.keys}" }
            wrapConnectionErrors {
                val subAck = requireConnection().subscribe(subscriptions, MqttProperties.EMPTY)
                recordSuccessfulSubscriptions(subscriptions, subAck)
            }
            log.info(TAG) { "Subscribed to ${topicFilters.keys}" }
        }

        /**
         * Subscribe to multiple topic filters with full subscription options.
         *
         * Unlike [subscribe] with `Map<String, QoS>`, this overload preserves all
         * per-topic MQTT 5.0 subscription options (noLocal, retainAsPublished, retainHandling).
         *
         * @param subscriptions List of [Subscription] objects with per-topic options.
         * @throws IllegalArgumentException if any topic filter is invalid (§4.7).
         * @throws IllegalStateException if not connected.
         */
        @Throws(
            IllegalArgumentException::class,
            IllegalStateException::class,
            MqttException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun subscribe(subscriptions: List<Subscription>) {
            subscriptions.forEach { TopicValidator.validateTopicFilter(it.topicFilter) }
            log.debug(TAG) { "Subscribing to ${subscriptions.map { it.topicFilter }}" }
            wrapConnectionErrors {
                val subAck = requireConnection().subscribe(subscriptions, MqttProperties.EMPTY)
                recordSuccessfulSubscriptions(subscriptions, subAck)
            }
            log.info(TAG) { "Subscribed to ${subscriptions.map { it.topicFilter }}" }
        }

        /**
         * Unsubscribe from one or more topic filters.
         *
         * @param topicFilters The topic filters to unsubscribe from.
         * @throws IllegalStateException if not connected.
         */
        @Throws(IllegalStateException::class, MqttException::class, kotlin.coroutines.cancellation.CancellationException::class)
        public suspend fun unsubscribe(vararg topicFilters: String) {
            log.debug(TAG) { "Unsubscribing from ${topicFilters.toList()}" }
            wrapConnectionErrors {
                requireConnection().unsubscribe(topicFilters.toList(), MqttProperties.EMPTY)
            }
            subscriptionsMutex.withLock {
                topicFilters.forEach { activeSubscriptions.remove(it) }
            }
            log.info(TAG) { "Unsubscribed from ${topicFilters.toList()}" }
        }

        /**
         * Send an AUTH response to the broker during enhanced authentication (§4.12).
         *
         * Collect [authChallenges] to receive challenges and call this method to respond.
         *
         * **Note:** Enhanced authentication during the CONNECT handshake (§4.12.1) is not
         * yet supported. Auth challenges are only delivered after the connection is established.
         * SASL-style challenge/response during CONNECT will be supported in a future release.
         *
         * @param data The authentication response data to send.
         * @throws IllegalStateException if not connected.
         */
        @Throws(IllegalStateException::class, MqttException::class, kotlin.coroutines.cancellation.CancellationException::class)
        public suspend fun sendAuthResponse(data: ByteString) {
            wrapConnectionErrors { requireConnection().sendAuthResponse(data.toByteArray()) }
        }

        /**
         * Close the client and release all resources.
         *
         * Gracefully disconnects if connected, cancels all background coroutines
         * including reconnection attempts. After calling this method, the client
         * cannot be reused.
         */
        public suspend fun close() {
            log.info(TAG) { "Closing client" }
            closed = true
            connectionMutex.withLock {
                intentionalDisconnect = true
                reconnectJob?.cancel()
                reconnectJob = null
                try {
                    connection?.disconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
                ) {
                    // Best-effort disconnect during close
                } finally {
                    stopForwardJobs()
                    connection = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
            scope.coroutineContext[Job]?.cancel()
            log.info(TAG) { "Client closed" }
        }

        // --- Internal helpers ---

        /**
         * Abort the connection without sending a DISCONNECT packet.
         *
         * This causes the broker to treat the disconnection as unexpected and
         * publish any configured Will Message (§3.1.2.5). Used for testing
         * will message delivery.
         */
        internal suspend fun abortConnection() {
            connectionMutex.withLock {
                intentionalDisconnect = true
                reconnectJob?.cancel()
                reconnectJob = null
                try {
                    connection?.abort()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
                ) {
                    // Best-effort abort
                } finally {
                    stopForwardJobs()
                    connection = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
            scope.coroutineContext[Job]?.cancel()
        }

        // --- Private helpers ---

        private suspend fun connectInternal(endpoint: MqttEndpoint) {
            val transport = effectiveTransportFactory(endpoint)
            val conn = MqttConnection(transport, config, scope, log)
            conn.connect(endpoint)
            connection = conn
            startForwarding(conn)
            log.info(TAG) { "Connected to $endpoint" }
        }

        /**
         * Attempt connection with automatic redirect following (§4.13).
         *
         * If the broker responds with USE_ANOTHER_SERVER (0x9C) or SERVER_MOVED (0x9D)
         * and includes a Server Reference property, parse the reference and retry.
         */
        private suspend fun connectWithRedirect(
            endpoint: MqttEndpoint,
            redirectsRemaining: Int,
        ) {
            try {
                connectInternal(endpoint)
            } catch (e: MqttConnectionException) {
                val isRedirect =
                    e.reasonCode == ReasonCode.USE_ANOTHER_SERVER ||
                        e.reasonCode == ReasonCode.SERVER_MOVED
                val ref = e.serverReference

                if (isRedirect && ref != null && redirectsRemaining > 0) {
                    val newEndpoint =
                        try {
                            parseServerReference(ref, endpoint)
                        } catch (
                            @Suppress("SwallowedException") parseErr: IllegalArgumentException,
                        ) {
                            log.error(TAG) { "Failed to parse server reference '$ref': ${parseErr.message}" }
                            throw e
                        }
                    log.info(TAG) {
                        "Server redirect (${e.reasonCode}): following to $newEndpoint " +
                            "(${redirectsRemaining - 1} redirects remaining)"
                    }
                    currentEndpoint = newEndpoint
                    connectWithRedirect(newEndpoint, redirectsRemaining - 1)
                } else {
                    throw e
                }
            }
        }

        private fun startForwarding(conn: MqttConnection) {
            stopForwardJobs()

            stateForwardJob =
                scope.launch {
                    conn.connectionState.collect { state ->
                        _connectionState.value = state
                        if (state == ConnectionState.DISCONNECTED &&
                            config.autoReconnect &&
                            !intentionalDisconnect
                        ) {
                            startReconnect()
                        }
                    }
                }

            messageForwardJob =
                scope.launch {
                    conn.incomingMessages.collect { message ->
                        _messages.emit(message)
                    }
                }

            authForwardJob =
                scope.launch {
                    conn.authChallenges.collect { challenge ->
                        _authChallenges.emit(challenge)
                    }
                }

            redirectForwardJob =
                scope.launch {
                    conn.serverRedirect.collect { redirect ->
                        handleServerRedirect(redirect)
                    }
                }
        }

        private fun stopForwardJobs() {
            stateForwardJob?.cancel()
            messageForwardJob?.cancel()
            authForwardJob?.cancel()
            redirectForwardJob?.cancel()
            stateForwardJob = null
            messageForwardJob = null
            authForwardJob = null
            redirectForwardJob = null
        }

        private suspend fun startReconnect() {
            connectionMutex.withLock {
                if (reconnectJob?.isActive == true) return
                reconnectJob =
                    scope.launch {
                        _connectionState.value = ConnectionState.RECONNECTING
                        var delayMs = config.reconnectBaseDelayMs
                        var attempts = 0

                        log.warn(TAG) { "Connection lost, starting reconnect" }

                        while (isActive && !intentionalDisconnect) {
                            // Enforce max reconnect attempts if configured
                            if (config.maxReconnectAttempts > 0 && attempts >= config.maxReconnectAttempts) {
                                log.error(TAG) {
                                    "Max reconnect attempts ($attempts) exhausted — giving up"
                                }
                                _connectionState.value = ConnectionState.DISCONNECTED
                                return@launch
                            }

                            // Read endpoint each attempt to honor mid-flight redirects
                            val endpoint = currentEndpoint ?: return@launch

                            log.debug(TAG) { "Reconnecting to $endpoint in ${delayMs}ms" }
                            delay(delayMs)
                            try {
                                connectionMutex.withLock {
                                    if (intentionalDisconnect) return@launch
                                    connectInternal(endpoint)
                                    resubscribe()
                                }
                                log.info(TAG) { "Reconnected to $endpoint" }
                                reconnectJob = null
                                return@launch
                            } catch (
                                @Suppress("SwallowedException") _: CancellationException,
                            ) {
                                return@launch
                            } catch (
                                @Suppress("TooGenericExceptionCaught") e: Exception,
                            ) {
                                attempts++
                                log.warn(TAG, throwable = e) { "Reconnect attempt $attempts failed" }
                                _connectionState.value = ConnectionState.RECONNECTING
                                delayMs = (delayMs * 2).coerceAtMost(config.reconnectMaxDelayMs)
                            }
                        }
                    }
            }
        }

        /** Record only successfully acknowledged subscriptions from the SUBACK response. */
        private suspend fun recordSuccessfulSubscriptions(
            subscriptions: List<Subscription>,
            subAck: SubAck,
        ) {
            subscriptionsMutex.withLock {
                subscriptions.zip(subAck.reasonCodes).forEach { (sub, code) ->
                    if (isSuccessfulSubAck(code)) {
                        activeSubscriptions[sub.topicFilter] = sub
                    }
                }
            }
        }

        private fun isSuccessfulSubAck(code: ReasonCode): Boolean =
            code == ReasonCode.SUCCESS ||
                code == ReasonCode.GRANTED_QOS_1 ||
                code == ReasonCode.GRANTED_QOS_2

        private suspend fun resubscribe() {
            val subs =
                subscriptionsMutex.withLock {
                    activeSubscriptions.values.toList()
                }
            if (subs.isNotEmpty()) {
                val subAck = connection?.subscribe(subs, MqttProperties.EMPTY) ?: return
                // Remove subscriptions that failed on reconnect
                subscriptionsMutex.withLock {
                    subs.zip(subAck.reasonCodes).forEach { (sub, code) ->
                        if (!isSuccessfulSubAck(code)) {
                            activeSubscriptions.remove(sub.topicFilter)
                            log.warn(TAG) {
                                "Subscription '${sub.topicFilter}' failed on resubscribe: $code"
                            }
                        }
                    }
                }
            }
        }

        private fun requireConnection(): MqttConnection {
            check(!closed) { "Client has been closed and cannot be reused" }
            return connection ?: throw IllegalStateException("Not connected")
        }

        /** Wrap internal [MqttConnectionException] into the public [MqttException] hierarchy. */
        private suspend inline fun wrapConnectionErrors(block: () -> Unit) {
            try {
                block()
            } catch (e: MqttConnectionException) {
                if (e.reasonCode == ReasonCode.PROTOCOL_ERROR) {
                    throw MqttException.ProtocolError(
                        reasonCode = e.reasonCode,
                        message = e.message ?: "Protocol error",
                        cause = e.cause,
                    )
                }
                throw MqttException.ConnectionLost(
                    reasonCode = e.reasonCode,
                    message = e.message ?: "Connection lost",
                    cause = e.cause,
                )
            }
        }

        /**
         * Handle a server redirect received via DISCONNECT (§4.13).
         *
         * Parses the server reference, updates the current endpoint, and triggers
         * reconnection to the new broker address.
         */
        private suspend fun handleServerRedirect(redirect: ServerRedirect) {
            val currentEp = currentEndpoint ?: return
            val newEndpoint =
                try {
                    parseServerReference(redirect.serverReference, currentEp)
                } catch (
                    @Suppress("SwallowedException") e: IllegalArgumentException,
                ) {
                    log.error(TAG) { "Failed to parse redirect reference '${redirect.serverReference}': ${e.message}" }
                    return
                }
            log.info(TAG) {
                "Handling server redirect (${redirect.reasonCode}): reconnecting to $newEndpoint"
            }
            currentEndpoint = newEndpoint
            if (config.autoReconnect && !intentionalDisconnect) {
                startReconnect()
            }
        }

        private companion object {
            private const val MESSAGE_BUFFER_CAPACITY = 64
            private const val AUTH_BUFFER_CAPACITY = 8
            private const val MAX_REDIRECTS = 5
            private const val TAG = "MqttClient"
        }
    }

/**
 * Parse a Server Reference string from CONNACK/DISCONNECT (§4.13) into an [MqttEndpoint].
 *
 * The spec states the Server Reference is a UTF-8 string whose value the client
 * may use to identify another server. Common formats:
 * - `host:port` — inherits TLS and transport type from original endpoint
 * - URI (e.g. `tcp://host:port`, `ssl://host:port`) — fully specified
 *
 * @param reference The Server Reference string from the broker.
 * @param original The original endpoint, used to inherit TLS/transport settings for `host:port` format.
 * @throws IllegalArgumentException if the reference cannot be parsed.
 */
internal fun parseServerReference(
    reference: String,
    original: MqttEndpoint,
): MqttEndpoint {
    // If it looks like a URI (contains "://"), delegate to MqttEndpoint.parse()
    if ("://" in reference) {
        return MqttEndpoint.parse(reference)
    }

    // Otherwise, parse as "host:port" and inherit transport type from original
    val colonIdx = reference.lastIndexOf(':')
    val host: String
    val port: Int
    if (colonIdx > 0) {
        host = reference.substring(0, colonIdx)
        port = reference.substring(colonIdx + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid port in server reference: $reference")
    } else {
        host = reference
        port =
            when (original) {
                is MqttEndpoint.Tcp -> original.port

                is MqttEndpoint.WebSocket -> throw IllegalArgumentException(
                    "Cannot derive WebSocket URL from bare hostname: $reference",
                )
            }
    }

    return when (original) {
        is MqttEndpoint.Tcp -> MqttEndpoint.Tcp(host = host, port = port, tls = original.tls)

        is MqttEndpoint.WebSocket -> throw IllegalArgumentException(
            "Cannot derive WebSocket URL from bare host:port: $reference",
        )
    }
}

/**
 * Creates an [MqttClient] with the given [clientId] and optional configuration.
 *
 * Shorthand factory that combines client ID with the [MqttConfig.Builder] DSL:
 *
 * ```kotlin
 * val client = MqttClient("sensor-hub") {
 *     keepAliveSeconds = 30
 *     autoReconnect = true
 *     logger = MqttLogger.println()
 *     logLevel = MqttLogLevel.DEBUG
 * }
 * ```
 *
 * @param clientId Client identifier sent to the broker.
 * @param scope Coroutine scope for background jobs.
 * @param configure Optional configuration block.
 */
public fun MqttClient(
    clientId: String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    configure: MqttConfig.Builder.() -> Unit = {},
): MqttClient {
    val config =
        MqttConfig
            .Builder()
            .apply {
                this.clientId = clientId
                configure()
            }.build()
    return MqttClient(config, scope)
}

// --- Extension functions for consumer convenience ---

/**
 * Returns a [Flow] of messages filtered to a specific [topic].
 *
 * Performs an exact match on [MqttMessage.topic]. For wildcard filtering,
 * use [messagesMatching] instead.
 *
 * ## Example
 * ```kotlin
 * client.messagesForTopic("sensors/temperature").collect { msg ->
 *     println("Temp: ${msg.payloadAsString()}")
 * }
 * ```
 */
public fun MqttClient.messagesForTopic(topic: String): Flow<MqttMessage> = messages.filter { it.topic == topic }

/**
 * Returns a [Flow] of messages whose topic matches the given MQTT [topicFilter].
 *
 * Supports MQTT wildcard patterns:
 * - `+` matches a single topic level
 * - `#` matches any number of remaining levels (must be last)
 *
 * ## Example
 * ```kotlin
 * client.messagesMatching("sensors/+/temperature").collect { msg ->
 *     println("${msg.topic}: ${msg.payloadAsString()}")
 * }
 * ```
 */
public fun MqttClient.messagesMatching(topicFilter: String): Flow<MqttMessage> =
    messages.filter { topicMatchesFilter(it.topic, topicFilter) }

/**
 * Connect, execute [block], then close — structured resource management.
 *
 * Ensures [MqttClient.close] is called even if [block] throws, similar to
 * `java.io.Closeable.use {}` or Ktor's `HttpClient.use {}`.
 *
 * ```kotlin
 * MqttClient("sensor").use(MqttEndpoint.parse("tcp://broker:1883")) { client ->
 *     client.subscribe("sensors/#", QoS.AT_LEAST_ONCE)
 *     client.publish("sensors/temp", "22.5")
 *     delay(10_000)
 * }
 * ```
 *
 * @param endpoint Broker endpoint to connect to.
 * @param block Suspend lambda receiving the connected client.
 * @return The result of [block].
 */
public suspend fun <T> MqttClient.use(
    endpoint: MqttEndpoint,
    block: suspend (MqttClient) -> T,
): T {
    try {
        connect(endpoint)
        return block(this)
    } finally {
        close()
    }
}

/**
 * Check whether a topic name matches an MQTT topic filter (§4.7).
 *
 * - `+` matches exactly one topic level
 * - `#` matches zero or more remaining levels (must be last segment)
 */
internal fun topicMatchesFilter(
    topic: String,
    filter: String,
): Boolean {
    // §4.7.2: Wildcard filters must not match $-prefixed system topics
    if (topic.startsWith('$') && (filter.startsWith('+') || filter.startsWith('#'))) return false

    val topicLevels = topic.split('/')
    val filterLevels = filter.split('/')

    var ti = 0
    var fi = 0
    while (fi < filterLevels.size) {
        val filterLevel = filterLevels[fi]
        when {
            // Matches everything remaining
            filterLevel == "#" -> {
                return true
            }

            // Filter has more levels than topic
            ti >= topicLevels.size -> {
                return false
            }

            // Matches one level
            filterLevel == "+" -> {
                ti++
                fi++
            }

            // Exact match
            filterLevel == topicLevels[ti] -> {
                ti++
                fi++
            }

            else -> {
                return false
            }
        }
    }
    return ti == topicLevels.size
}
