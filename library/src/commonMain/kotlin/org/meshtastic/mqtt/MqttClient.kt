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
import org.meshtastic.mqtt.packet.ReasonCode
import org.meshtastic.mqtt.packet.RetainHandling
import org.meshtastic.mqtt.packet.SubAck
import org.meshtastic.mqtt.packet.Subscription

/**
 * Public API surface for the MQTTtastic MQTT 5.0 client library.
 *
 * This is the primary entry point for interacting with an MQTT broker. It wraps the
 * internal [MqttConnection] state machine with a coroutine-friendly API, providing:
 *
 * - **Connection management** — [connect], [disconnect], and [close] with lifecycle tracking
 *   via the [connectionState] flow.
 * - **Publishing** — [publish] messages with QoS 0/1/2, optional retain, and full MQTT 5.0
 *   properties. Convenience overloads accept [String], [ByteArray], or [MqttMessage].
 * - **Subscribing** — [subscribe] to topic filters with per-subscription QoS, No Local,
 *   Retain As Published, and Retain Handling options.
 * - **Automatic reconnection** — configurable exponential backoff with subscription
 *   re-establishment on reconnect (when [MqttConfig.autoReconnect] is `true`).
 * - **Enhanced authentication** — AUTH packet challenge/response via [authChallenges] flow
 *   and [sendAuthResponse] (§4.12).
 * - **Request/Response** — [publishWithResponse] sets Response Topic and Correlation Data
 *   for the MQTT 5.0 request/response pattern (§4.10).
 *
 * ## Example
 * ```kotlin
 * val client = MqttClient(MqttConfig(clientId = "my-client"))
 *
 * // Observe connection state
 * scope.launch { client.connectionState.collect { println("State: $it") } }
 *
 * // Collect messages
 * scope.launch { client.messages.collect { println("${it.topic}: ${it.payload}") } }
 *
 * // Connect, subscribe, publish
 * client.connect(MqttEndpoint.Tcp("broker.example.com"))
 * client.subscribe("sensors/#", QoS.AT_LEAST_ONCE)
 * client.publish("sensors/temp", "22.5", QoS.AT_LEAST_ONCE)
 *
 * // Cleanup
 * client.close()
 * ```
 *
 * ## Thread Safety
 * All public methods are `suspend` functions safe to call from any coroutine.
 * Internal state is protected by mutexes — concurrent calls to [publish], [subscribe],
 * and [disconnect] are serialized correctly.
 *
 * @param config Client configuration mapping to CONNECT packet fields (§3.1).
 * @param scope Coroutine scope for background jobs. Defaults to a new [SupervisorJob]
 *   on [Dispatchers.Default]. The client creates a child scope, so cancelling the
 *   provided scope also cancels all client operations.
 */
@Suppress("TooManyFunctions")
public class MqttClient
    public constructor(
        private val config: MqttConfig,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) {
        private val transportFactory: (MqttEndpoint) -> MqttTransport = { createPlatformTransport() }
        private val log = MqttLoggerInternal(config.logger, config.logLevel)

        /** Internal constructor for testing with a pre-built transport and injected scope. */
        internal constructor(config: MqttConfig, transport: MqttTransport, scope: CoroutineScope) :
            this(config, scope) {
            transportFactoryOverride = { transport }
        }

        private var transportFactoryOverride: ((MqttEndpoint) -> MqttTransport)? = null

        private val effectiveTransportFactory: (MqttEndpoint) -> MqttTransport
            get() = transportFactoryOverride ?: transportFactory

        private val scope = CoroutineScope(SupervisorJob() + scope.coroutineContext.minusKey(Job))

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

        private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = MESSAGE_BUFFER_CAPACITY)

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

        private val _authChallenges = MutableSharedFlow<AuthChallenge>(extraBufferCapacity = AUTH_BUFFER_CAPACITY)

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

        private var connection: MqttConnection? = null
        private var currentEndpoint: MqttEndpoint? = null
        private var messageForwardJob: Job? = null
        private var stateForwardJob: Job? = null
        private var authForwardJob: Job? = null
        private var redirectForwardJob: Job? = null
        private var reconnectJob: Job? = null
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
         * @throws MqttConnectionException if the broker rejects the connection.
         */
        @Throws(MqttConnectionException::class, kotlin.coroutines.cancellation.CancellationException::class)
        public suspend fun connect(endpoint: MqttEndpoint) {
            connectionMutex.withLock {
                log.info(TAG) { "Connecting to $endpoint" }
                intentionalDisconnect = false
                currentEndpoint = endpoint
                connectWithRedirect(endpoint, redirectsRemaining = MAX_REDIRECTS)
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
         * @throws MqttConnectionException on publish failure (QoS 1/2 only).
         */
        @Throws(
            IllegalStateException::class,
            IllegalArgumentException::class,
            MqttConnectionException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun publish(message: MqttMessage) {
            TopicValidator.validateTopicName(message.topic)
            log.debug(TAG) { "Publishing to '${message.topic}' qos=${message.qos} retain=${message.retain}" }
            requireConnection().publish(message)
        }

        /**
         * Publish a message with a raw [ByteArray] payload.
         *
         * Convenience overload that wraps [payload] in an [MqttMessage].
         *
         * @param topic The topic to publish to.
         * @param payload Raw byte payload.
         * @param qos Quality of service level (default: [QoS.AT_MOST_ONCE]).
         * @param retain Whether the message should be retained (default: false).
         */
        @Throws(
            IllegalStateException::class,
            IllegalArgumentException::class,
            MqttConnectionException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun publish(
            topic: String,
            payload: ByteArray,
            qos: QoS = QoS.AT_MOST_ONCE,
            retain: Boolean = false,
        ) {
            publish(MqttMessage(topic = topic, payload = ByteString(payload), qos = qos, retain = retain))
        }

        /**
         * Publish a message with a [String] payload.
         *
         * Convenience overload that encodes the string as UTF-8 bytes.
         *
         * @param topic The topic to publish to.
         * @param payload String payload (encoded as UTF-8).
         * @param qos Quality of service level (default: [QoS.AT_MOST_ONCE]).
         * @param retain Whether the message should be retained (default: false).
         */
        @Throws(
            IllegalStateException::class,
            IllegalArgumentException::class,
            MqttConnectionException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun publish(
            topic: String,
            payload: String,
            qos: QoS = QoS.AT_MOST_ONCE,
            retain: Boolean = false,
        ) {
            publish(topic, payload.encodeToByteArray(), qos, retain)
        }

        /**
         * Publish a message with MQTT 5.0 request/response pattern properties (§4.10).
         *
         * Sets the [responseTopic] and optional [correlationData] in the publish properties
         * so that receiving clients know where to send their response.
         *
         * @param topic The topic to publish the request to.
         * @param payload Raw byte payload.
         * @param responseTopic The topic the responder should publish its response to.
         * @param correlationData Optional data to correlate request with response (immutable).
         * @param qos Quality of service level (default: [QoS.AT_LEAST_ONCE]).
         * @param retain Whether the message should be retained (default: false).
         */
        @Throws(
            IllegalStateException::class,
            IllegalArgumentException::class,
            MqttConnectionException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun publishWithResponse(
            topic: String,
            payload: ByteArray,
            responseTopic: String,
            correlationData: ByteString? = null,
            qos: QoS = QoS.AT_LEAST_ONCE,
            retain: Boolean = false,
        ) {
            val props =
                PublishProperties(
                    responseTopic = responseTopic,
                    correlationData = correlationData,
                )
            publish(
                MqttMessage(
                    topic = topic,
                    payload = ByteString(payload),
                    qos = qos,
                    retain = retain,
                    properties = props,
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
            MqttConnectionException::class,
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
            val subAck = requireConnection().subscribe(listOf(sub), MqttProperties.EMPTY)
            recordSuccessfulSubscriptions(listOf(sub), subAck)
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
            MqttConnectionException::class,
            kotlin.coroutines.cancellation.CancellationException::class,
        )
        public suspend fun subscribe(topicFilters: Map<String, QoS>) {
            topicFilters.keys.forEach { TopicValidator.validateTopicFilter(it) }
            val subscriptions =
                topicFilters.map { (filter, qos) ->
                    Subscription(topicFilter = filter, maxQos = qos)
                }
            log.debug(TAG) { "Subscribing to ${topicFilters.keys}" }
            val subAck = requireConnection().subscribe(subscriptions, MqttProperties.EMPTY)
            recordSuccessfulSubscriptions(subscriptions, subAck)
            log.info(TAG) { "Subscribed to ${topicFilters.keys}" }
        }

        /**
         * Unsubscribe from one or more topic filters.
         *
         * @param topicFilters The topic filters to unsubscribe from.
         * @throws IllegalStateException if not connected.
         */
        @Throws(IllegalStateException::class, MqttConnectionException::class, kotlin.coroutines.cancellation.CancellationException::class)
        public suspend fun unsubscribe(vararg topicFilters: String) {
            log.debug(TAG) { "Unsubscribing from ${topicFilters.toList()}" }
            requireConnection().unsubscribe(topicFilters.toList(), MqttProperties.EMPTY)
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
         * @param data The authentication response data to send.
         * @throws IllegalStateException if not connected.
         */
        @Throws(IllegalStateException::class, MqttConnectionException::class, kotlin.coroutines.cancellation.CancellationException::class)
        public suspend fun sendAuthResponse(data: ByteArray) {
            requireConnection().sendAuthResponse(data)
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
            connectionMutex.withLock {
                intentionalDisconnect = true
                reconnectJob?.cancel()
                reconnectJob = null
                try {
                    connection?.disconnect()
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
                ) {
                    // Best-effort disconnect during close
                }
                stopForwardJobs()
                connection = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            scope.coroutineContext[Job]?.cancel()
            log.info(TAG) { "Client closed" }
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

        private fun startReconnect() {
            if (reconnectJob?.isActive == true) return
            reconnectJob =
                scope.launch {
                    _connectionState.value = ConnectionState.RECONNECTING
                    var delayMs = config.reconnectBaseDelayMs
                    val endpoint = currentEndpoint ?: return@launch

                    log.warn(TAG) { "Connection lost, starting reconnect to $endpoint" }

                    while (isActive && !intentionalDisconnect) {
                        log.debug(TAG) { "Reconnecting in ${delayMs}ms" }
                        delay(delayMs)
                        try {
                            connectInternal(endpoint)
                            resubscribe()
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
                            log.warn(TAG, throwable = e) { "Reconnect attempt failed" }
                            _connectionState.value = ConnectionState.RECONNECTING
                            delayMs = (delayMs * 2).coerceAtMost(config.reconnectMaxDelayMs)
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
                connection?.subscribe(subs, MqttProperties.EMPTY)
            }
        }

        private fun requireConnection(): MqttConnection = connection ?: throw IllegalStateException("Not connected")

        /**
         * Handle a server redirect received via DISCONNECT (§4.13).
         *
         * Parses the server reference, updates the current endpoint, and triggers
         * reconnection to the new broker address.
         */
        private fun handleServerRedirect(redirect: ServerRedirect) {
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

        internal companion object {
            const val MESSAGE_BUFFER_CAPACITY = 64
            const val AUTH_BUFFER_CAPACITY = 8
            const val MAX_REDIRECTS = 5
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
 * Subscribe to multiple topic filters at the same QoS level.
 *
 * Convenience for the common case of subscribing to several topics with identical QoS:
 *
 * ```kotlin
 * client.subscribe(QoS.AT_LEAST_ONCE, "sensors/temp", "sensors/humidity", "sensors/pressure")
 * ```
 *
 * @param qos QoS level applied to all topic filters.
 * @param topicFilters One or more topic filters to subscribe to.
 */
public suspend fun MqttClient.subscribe(
    qos: QoS,
    vararg topicFilters: String,
) {
    subscribe(topicFilters.associateWith { qos })
}

/**
 * Publish a message with MQTT 5.0 [PublishProperties].
 *
 * Use when you need to set content type, message expiry, user properties, or other
 * MQTT 5.0 publish properties alongside a simple string payload:
 *
 * ```kotlin
 * client.publish(
 *     topic = "sensors/data",
 *     payload = """{"temp": 22.5}""",
 *     qos = QoS.AT_LEAST_ONCE,
 *     properties = PublishProperties(contentType = "application/json"),
 * )
 * ```
 */
public suspend fun MqttClient.publish(
    topic: String,
    payload: String,
    qos: QoS = QoS.AT_MOST_ONCE,
    retain: Boolean = false,
    properties: PublishProperties = PublishProperties(),
) {
    publish(
        MqttMessage(
            topic = topic,
            payload = ByteString(payload.encodeToByteArray()),
            qos = qos,
            retain = retain,
            properties = properties,
        ),
    )
}

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
    val topicLevels = topic.split('/')
    val filterLevels = filter.split('/')

    var ti = 0
    var fi = 0
    while (fi < filterLevels.size) {
        val filterLevel = filterLevels[fi]
        when {
            filterLevel == "#" -> {
                return true
            }

            // Matches everything remaining
            ti >= topicLevels.size -> {
                return false
            }

            // Filter has more levels than topic
            filterLevel == "+" -> {
                ti++
                fi++
            }

            // Matches one level
            filterLevel == topicLevels[ti] -> {
                ti++
                fi++
            }

            // Exact match
            else -> {
                return false
            }
        }
    }
    return ti == topicLevels.size
}
