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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Public API surface for the MQTT 5.0 client library.
 *
 * Wraps [MqttConnection] with transport factory, automatic reconnection with
 * exponential backoff, and a coroutine-friendly API. This is the only public
 * entry point — all protocol logic is internal.
 *
 * @param config Client configuration mapping to CONNECT packet fields (§3.1).
 */
@Suppress("TooManyFunctions")
public class MqttClient
    public constructor(
        private val config: MqttConfig,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) {
        private val transportFactory: (MqttEndpoint) -> MqttTransport = { createPlatformTransport() }

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

        /** Observable connection state. */
        public val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = MESSAGE_BUFFER_CAPACITY)

        /** Flow of incoming messages from subscribed topics. */
        public val messages: SharedFlow<MqttMessage> = _messages.asSharedFlow()

        private val _authChallenges = MutableSharedFlow<AuthChallenge>(extraBufferCapacity = AUTH_BUFFER_CAPACITY)

        /** Flow of authentication challenges from the broker during enhanced auth (§4.12). */
        public val authChallenges: SharedFlow<AuthChallenge> = _authChallenges.asSharedFlow()

        private var connection: MqttConnection? = null
        private var currentEndpoint: MqttEndpoint? = null
        private var messageForwardJob: Job? = null
        private var stateForwardJob: Job? = null
        private var authForwardJob: Job? = null
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
         * @param endpoint Broker endpoint (TCP or WebSocket).
         * @throws MqttConnectionException if the broker rejects the connection.
         */
        public suspend fun connect(endpoint: MqttEndpoint) {
            connectionMutex.withLock {
                intentionalDisconnect = false
                currentEndpoint = endpoint
                connectInternal(endpoint)
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
                intentionalDisconnect = true
                reconnectJob?.cancel()
                reconnectJob = null
                try {
                    connection?.disconnect()
                } finally {
                    stopForwardJobs()
                    connection = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        /**
         * Publish an [MqttMessage] to the broker.
         *
         * @param message The message to publish, including topic, payload, QoS, and properties.
         * @throws IllegalStateException if not connected.
         */
        public suspend fun publish(message: MqttMessage) {
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
         */
        public suspend fun subscribe(
            topicFilter: String,
            qos: QoS = QoS.AT_MOST_ONCE,
            noLocal: Boolean = false,
            retainAsPublished: Boolean = false,
            retainHandling: RetainHandling = RetainHandling.SEND_AT_SUBSCRIBE,
        ) {
            val sub =
                Subscription(
                    topicFilter = topicFilter,
                    maxQos = qos,
                    noLocal = noLocal,
                    retainAsPublished = retainAsPublished,
                    retainHandling = retainHandling,
                )
            val subAck = requireConnection().subscribe(listOf(sub), MqttProperties.EMPTY)
            recordSuccessfulSubscriptions(listOf(sub), subAck)
        }

        /**
         * Subscribe to multiple topic filters with per-topic QoS levels.
         *
         * Only records subscriptions whose SUBACK reason code indicates success.
         *
         * @param topicFilters Map of topic filter to maximum QoS level.
         */
        public suspend fun subscribe(topicFilters: Map<String, QoS>) {
            val subscriptions =
                topicFilters.map { (filter, qos) ->
                    Subscription(topicFilter = filter, maxQos = qos)
                }
            val subAck = requireConnection().subscribe(subscriptions, MqttProperties.EMPTY)
            recordSuccessfulSubscriptions(subscriptions, subAck)
        }

        /**
         * Unsubscribe from one or more topic filters.
         *
         * @param topicFilters The topic filters to unsubscribe from.
         */
        public suspend fun unsubscribe(vararg topicFilters: String) {
            requireConnection().unsubscribe(topicFilters.toList(), MqttProperties.EMPTY)
            subscriptionsMutex.withLock {
                topicFilters.forEach { activeSubscriptions.remove(it) }
            }
        }

        /**
         * Send an AUTH response to the broker during enhanced authentication (§4.12).
         *
         * Collect [authChallenges] to receive challenges and call this method to respond.
         *
         * @param data The authentication response data to send.
         */
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
        }

        // --- Private helpers ---

        private suspend fun connectInternal(endpoint: MqttEndpoint) {
            val transport = effectiveTransportFactory(endpoint)
            val conn = MqttConnection(transport, config, scope)
            conn.connect(endpoint)
            connection = conn
            startForwarding(conn)
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
        }

        private fun stopForwardJobs() {
            stateForwardJob?.cancel()
            messageForwardJob?.cancel()
            authForwardJob?.cancel()
            stateForwardJob = null
            messageForwardJob = null
            authForwardJob = null
        }

        private fun startReconnect() {
            if (reconnectJob?.isActive == true) return
            reconnectJob =
                scope.launch {
                    _connectionState.value = ConnectionState.RECONNECTING
                    var delayMs = config.reconnectBaseDelayMs
                    val endpoint = currentEndpoint ?: return@launch

                    while (isActive && !intentionalDisconnect) {
                        delay(delayMs)
                        try {
                            connectInternal(endpoint)
                            resubscribe()
                            reconnectJob = null
                            return@launch
                        } catch (
                            @Suppress("SwallowedException") _: CancellationException,
                        ) {
                            return@launch
                        } catch (
                            @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
                        ) {
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

        internal companion object {
            const val MESSAGE_BUFFER_CAPACITY = 64
            const val AUTH_BUFFER_CAPACITY = 8
        }
    }
