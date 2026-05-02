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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import org.meshtastic.mqtt.packet.Auth
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.Connect
import org.meshtastic.mqtt.packet.Disconnect
import org.meshtastic.mqtt.packet.MqttPacket
import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.PacketIdAllocator
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
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Represents an authentication challenge received from the broker during enhanced authentication (§4.12).
 *
 * @property reasonCode The reason code from the AUTH packet.
 * @property authenticationMethod The authentication method in use.
 * @property authenticationData The challenge data from the broker.
 */
public data class AuthChallenge(
    val reasonCode: ReasonCode,
    val authenticationMethod: String?,
    val authenticationData: ByteString?,
)

/**
 * Internal connection manager implementing the MQTT 5.0 client state machine.
 *
 * Manages the full connection lifecycle: CONNECT/CONNACK handshake, keepalive via PINGREQ/PINGRESP,
 * background read loop, QoS 0/1/2 publish flows, subscribe/unsubscribe, and graceful disconnect.
 *
 * Supports advanced MQTT 5.0 features:
 * - **Topic Aliases** — bidirectional client↔server alias mapping (§3.3.2.3.4)
 * - **Flow Control** — semaphore-based Receive Maximum enforcement (§3.3.4)
 * - **Enhanced Authentication** — AUTH packet challenge/response flow (§4.12)
 * - **Server Redirect** — Server Reference handling in CONNACK/DISCONNECT (§4.13)
 *
 * Not part of the public API — consumers use [MqttClient] instead.
 *
 * @param transport The byte-level transport to use for sending/receiving packets.
 * @param config Client configuration mapping to CONNECT packet fields (§3.1).
 * @param scope Coroutine scope for background jobs (read loop, keepalive).
 */
@Suppress("TooManyFunctions")
internal class MqttConnection(
    private val transport: MqttTransport,
    private val config: MqttConfig,
    private val scope: CoroutineScope,
    private val log: MqttLoggerInternal = MqttLoggerInternal.NOOP,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** The MQTT protocol version for this connection (may downgrade at runtime). */
    private var version: MqttProtocolVersion = config.protocolVersion

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected.Idle)

    /** Observable connection state. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages =
        MutableSharedFlow<MqttMessage>(
            extraBufferCapacity = INCOMING_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Flow of incoming PUBLISH messages received from the broker. */
    val incomingMessages: SharedFlow<MqttMessage> = _incomingMessages.asSharedFlow()

    private val _authChallenges =
        MutableSharedFlow<AuthChallenge>(
            extraBufferCapacity = AUTH_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Flow of authentication challenges from the broker during enhanced auth (§4.12). */
    val authChallenges: SharedFlow<AuthChallenge> = _authChallenges.asSharedFlow()

    private val packetIdAllocator = PacketIdAllocator()
    private val sendMutex = Mutex()
    private val acksMutex = Mutex()
    private val pendingAcks = mutableMapOf<Int, CompletableDeferred<MqttPacket>>()
    private val inboundQos2PacketIds = mutableSetOf<Int>()

    // QoS 2 inbound: store resolved messages until PUBREL arrives (§4.3.3)
    private val pendingQos2Messages = mutableMapOf<Int, MqttMessage>()

    private var readLoopJob: Job? = null
    private var keepAliveJob: Job? = null
    private var lastSendMark: TimeMark = timeSource.markNow()

    @Volatile
    private var awaitingPingResp = false
    private var pingSentMark: TimeMark = timeSource.markNow()

    @Volatile
    private var handlingFatalError = false

    // Server-assigned values from CONNACK
    private var serverReceiveMaximum: Int = DEFAULT_RECEIVE_MAXIMUM
    private var assignedClientId: String? = null
    private var effectiveKeepAliveSeconds: Int = 0

    // Topic alias state (§3.3.2.3.4)
    private val aliasMutex = Mutex()
    private val outboundTopicAliases = mutableMapOf<String, Int>()
    private val inboundTopicAliases = mutableMapOf<Int, String>()
    private var nextOutboundAlias = 1
    private var serverTopicAliasMaximum = 0

    // Flow control — limits concurrent in-flight QoS 1/2 publishes (§3.3.4)
    private var inflightSemaphore: Semaphore? = null

    // Server capabilities from CONNACK properties (§3.2.2.3)
    private var serverSharedSubscriptionAvailable: Boolean = true
    private var serverMaximumQos: QoS = QoS.EXACTLY_ONCE
    private var serverRetainAvailable: Boolean = true
    private var serverWildcardSubscriptionAvailable: Boolean = true
    private var serverSubscriptionIdentifiersAvailable: Boolean = true
    private var serverMaximumPacketSize: Long? = null

    private val _serverRedirect =
        MutableSharedFlow<ServerRedirect>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Emitted when the broker sends a DISCONNECT with server redirect reason code (§4.13). */
    val serverRedirect: SharedFlow<ServerRedirect> = _serverRedirect.asSharedFlow()

    /**
     * Establish an MQTT 5.0 connection to the broker.
     *
     * Performs the CONNECT/CONNACK handshake per §3.1/§3.2, then starts the background
     * read loop and keepalive timer.
     *
     * @param endpoint Broker endpoint (TCP or WebSocket).
     * @return The CONNACK packet from the broker.
     * @throws MqttConnectionException if the broker rejects the connection.
     */
    suspend fun connect(endpoint: MqttEndpoint): ConnAck {
        _connectionState.value = ConnectionState.Connecting

        if (!config.cleanStart) {
            log.warn(TAG) {
                "cleanStart=false: client-side session persistence is not yet implemented. " +
                    "In-flight QoS 1/2 messages may be lost across reconnects."
            }
        }

        log.debug(TAG) { "Establishing transport connection to $endpoint" }

        try {
            transport.connect(endpoint)
            val connectPacket = buildConnectPacket()
            log.debug(TAG) { "Sending CONNECT (clientId='${config.clientId}', cleanStart=${config.cleanStart})" }
            sendPacket(connectPacket)

            // Read responses until CONNACK; AUTH packets may interleave for enhanced auth (§4.12)
            val connAck = awaitConnAck()

            if (connAck.reasonCode != ReasonCode.SUCCESS) {
                log.error(TAG) { "Connection refused: ${connAck.reasonCode}" }
                transport.close()
                _connectionState.value = ConnectionState.Disconnected.Idle
                val serverRef = connAck.properties.serverReference
                throw MqttConnectionException(
                    connAck.reasonCode,
                    "Connection refused: ${connAck.reasonCode}",
                    serverReference = serverRef,
                )
            }

            log.debug(TAG) { "Received CONNACK: reasonCode=${connAck.reasonCode}" }

            if (version.supportsProperties) {
                // Store server-assigned values from CONNACK properties
                connAck.properties.receiveMaximum?.let { serverReceiveMaximum = it }
                connAck.properties.assignedClientIdentifier?.let { assignedClientId = it }
                connAck.properties.topicAliasMaximum?.let { serverTopicAliasMaximum = it }
                connAck.properties.sharedSubscriptionAvailable?.let { serverSharedSubscriptionAvailable = it }
                connAck.properties.maximumQos?.let { value ->
                    serverMaximumQos =
                        when (value) {
                            0 -> {
                                QoS.AT_MOST_ONCE
                            }

                            1 -> {
                                QoS.AT_LEAST_ONCE
                            }

                            else -> {
                                log.warn(TAG) { "Invalid Maximum QoS value $value in CONNACK, treating as protocol error" }
                                throw MqttConnectionException(
                                    ReasonCode.PROTOCOL_ERROR,
                                    "Invalid Maximum QoS value $value in CONNACK (§3.2.2.3.4)",
                                )
                            }
                        }
                }
                connAck.properties.retainAvailable?.let { serverRetainAvailable = it }
                connAck.properties.wildcardSubscriptionAvailable?.let { serverWildcardSubscriptionAvailable = it }
                connAck.properties.subscriptionIdentifiersAvailable?.let { serverSubscriptionIdentifiersAvailable = it }
                connAck.properties.maximumPacketSize?.let { serverMaximumPacketSize = it }

                // Honor Server Keep Alive override (§3.2.2.3.14)
                effectiveKeepAliveSeconds =
                    connAck.properties.serverKeepAlive ?: config.keepAliveSeconds

                // Initialize flow control semaphore based on server's Receive Maximum (§3.3.4)
                inflightSemaphore = Semaphore(serverReceiveMaximum)
            } else {
                // MQTT 3.1.1: no properties in CONNACK, use config values
                effectiveKeepAliveSeconds = config.keepAliveSeconds
            }

            log.debug(TAG) {
                "Session established: keepAlive=${effectiveKeepAliveSeconds}s, " +
                    "serverReceiveMax=$serverReceiveMaximum, " +
                    "topicAliasMax=$serverTopicAliasMaximum" +
                    (assignedClientId?.let { ", assignedClientId=$it" } ?: "")
            }

            _connectionState.value = ConnectionState.Connected
            startReadLoop()
            startKeepAlive()

            return connAck
        } catch (e: MqttConnectionException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            // awaitConnAck timed out — map to a meaningful connection error
            log.error(TAG) { "Connection timed out waiting for CONNACK" }
            try {
                transport.close()
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
            ) {
                // Best-effort transport close
            }
            _connectionState.value = ConnectionState.Disconnected.Idle
            throw MqttConnectionException(
                ReasonCode.UNSPECIFIED_ERROR,
                "Connection timed out: no CONNACK received within ${ACK_TIMEOUT_MS}ms",
                e,
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Preserve structured concurrency — do not wrap cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error(TAG, throwable = e) { "Connection failed: ${e.message}" }
            try {
                transport.close()
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
            ) {
                // Best-effort transport close on handshake failure
            }
            _connectionState.value = ConnectionState.Disconnected.Idle
            throw MqttConnectionException(ReasonCode.UNSPECIFIED_ERROR, "Connection failed: ${e.message}", e)
        }
    }

    /**
     * Read packets from the transport until CONNACK is received.
     * Handles interleaved AUTH packets for enhanced authentication (§4.12).
     * Times out after [ACK_TIMEOUT_MS] to prevent hanging on unresponsive brokers.
     */
    private suspend fun awaitConnAck(): ConnAck =
        withTimeout(ACK_TIMEOUT_MS) {
            while (true) {
                val responseBytes = transport.receive()
                val response = decodePacket(responseBytes, version)

                when (response) {
                    is ConnAck -> {
                        return@withTimeout response
                    }

                    is Auth -> {
                        // AUTH is only valid in MQTT 5.0
                        if (!version.supportsProperties) {
                            throw MqttConnectionException(
                                ReasonCode.PROTOCOL_ERROR,
                                "Received AUTH packet during MQTT 3.1.1 handshake",
                            )
                        }
                        _authChallenges.emit(
                            AuthChallenge(
                                reasonCode = response.reasonCode,
                                authenticationMethod = response.properties.authenticationMethod,
                                authenticationData = response.properties.authenticationData?.let { ByteString(it) },
                            ),
                        )
                        // Application must call sendAuthResponse() to continue the handshake
                    }

                    else -> {
                        throw MqttConnectionException(
                            ReasonCode.PROTOCOL_ERROR,
                            "Expected CONNACK or AUTH during handshake, got ${response.packetType}",
                        )
                    }
                }
            }
            error("Unreachable")
        }

    /**
     * Gracefully disconnect from the broker per §3.14.
     *
     * Stops keepalive and read loop, sends a DISCONNECT packet, then closes the transport.
     */
    suspend fun disconnect(reasonCode: ReasonCode = ReasonCode.SUCCESS) {
        log.debug(TAG) { "Disconnecting with reasonCode=$reasonCode" }
        failAllPendingAcks()
        stopBackgroundJobs()

        try {
            if (transport.isConnected) {
                // MQTT 3.1.1: DISCONNECT has no variable header/payload — always send empty
                // MQTT 5.0: send with reason code and optional properties
                sendPacket(Disconnect(reasonCode = reasonCode))
                transport.close()
            }
        } finally {
            resetConnectionState()
        }
    }

    /**
     * Abort the connection without sending a DISCONNECT packet.
     *
     * This triggers the broker to publish the client's Will Message (§3.1.2.5),
     * since an abrupt close without a clean DISCONNECT is treated as an unexpected
     * disconnection. Used for testing and force-close scenarios.
     */
    suspend fun abort() {
        log.debug(TAG) { "Aborting connection (no DISCONNECT)" }
        failAllPendingAcks()
        stopBackgroundJobs()

        try {
            if (transport.isConnected) {
                transport.close()
            }
        } finally {
            resetConnectionState()
        }
    }

    private suspend fun resetConnectionState() {
        // Caller-initiated graceful disconnect — no failure reason.
        _connectionState.value = ConnectionState.Disconnected.Idle
        if (config.cleanStart) {
            packetIdAllocator.reset()
        }
        aliasMutex.withLock {
            outboundTopicAliases.clear()
            inboundTopicAliases.clear()
            nextOutboundAlias = 1
            serverTopicAliasMaximum = 0
        }
        acksMutex.withLock {
            inboundQos2PacketIds.clear()
            pendingQos2Messages.clear()
        }
        inflightSemaphore = null
        awaitingPingResp = false
        handlingFatalError = false
        // Reset server capabilities to defaults — they will be restored from CONNACK on reconnect
        serverMaximumQos = QoS.EXACTLY_ONCE
        serverRetainAvailable = true
        serverWildcardSubscriptionAvailable = true
        serverSubscriptionIdentifiersAvailable = true
        serverMaximumPacketSize = null
        log.debug(TAG) { "Connection state reset" }
    }

    /**
     * Publish a message to the broker.
     *
     * - **QoS 0**: Fire-and-forget, returns `null`.
     * - **QoS 1**: Waits for PUBACK, returns the reason code.
     * - **QoS 2**: Full 4-step handshake (PUBLISH → PUBREC → PUBREL → PUBCOMP), returns the reason code.
     *
     * @param message The message to publish.
     * @return Reason code from the acknowledgement, or `null` for QoS 0.
     */
    suspend fun publish(message: MqttMessage): ReasonCode? {
        check(_connectionState.value is ConnectionState.Connected) { "Not connected" }

        // Enforce server Maximum QoS (§3.2.2.3.4)
        require(message.qos <= serverMaximumQos) {
            "Server maximum QoS is $serverMaximumQos, cannot publish with ${message.qos} (§3.2.2.3.4)"
        }

        // Enforce server Retain Available (§3.2.2.3.7)
        if (message.retain) {
            require(serverRetainAvailable) {
                "Server does not support retained messages (§3.2.2.3.7)"
            }
        }

        val publishProperties = if (version.supportsProperties) buildPublishProperties(message.properties) else MqttProperties.EMPTY
        val (resolvedTopic, resolvedProperties) =
            if (version.supportsProperties) {
                applyOutboundTopicAlias(message.topic, publishProperties)
            } else {
                message.topic to MqttProperties.EMPTY
            }

        return when (message.qos) {
            QoS.AT_MOST_ONCE -> {
                log.debug(TAG) { "Sending PUBLISH QoS 0 to '${message.topic}'" }
                sendPacket(
                    Publish(
                        topicName = resolvedTopic,
                        payload = message.payload.toByteArray(),
                        qos = QoS.AT_MOST_ONCE,
                        retain = message.retain,
                        properties = resolvedProperties,
                    ),
                )
                null
            }

            QoS.AT_LEAST_ONCE -> {
                log.debug(TAG) { "Sending PUBLISH QoS 1 to '${message.topic}'" }
                publishQos1(message, resolvedTopic, resolvedProperties)
            }

            QoS.EXACTLY_ONCE -> {
                log.debug(TAG) { "Sending PUBLISH QoS 2 to '${message.topic}'" }
                publishQos2(message, resolvedTopic, resolvedProperties)
            }
        }
    }

    /**
     * Subscribe to one or more topic filters per §3.8.
     *
     * @param subscriptions List of topic subscriptions with QoS and options.
     * @param properties Optional MQTT 5.0 properties for the SUBSCRIBE packet.
     * @return The SUBACK packet from the broker.
     */
    suspend fun subscribe(
        subscriptions: List<Subscription>,
        properties: MqttProperties = MqttProperties.EMPTY,
    ): SubAck {
        check(_connectionState.value is ConnectionState.Connected) { "Not connected" }

        if (version.supportsProperties) {
            // Enforce shared subscription availability (§4.8.2)
            if (!serverSharedSubscriptionAvailable) {
                subscriptions.forEach { sub ->
                    require(!TopicValidator.isSharedSubscription(sub.topicFilter)) {
                        "Server does not support shared subscriptions (§4.8.2): '${sub.topicFilter}'"
                    }
                }
            }

            // Enforce wildcard subscription availability (§3.2.2.3.12)
            if (!serverWildcardSubscriptionAvailable) {
                subscriptions.forEach { sub ->
                    require(!TopicValidator.containsWildcards(sub.topicFilter)) {
                        "Server does not support wildcard subscriptions (§3.2.2.3.12): '${sub.topicFilter}'"
                    }
                }
            }

            // Enforce subscription identifier availability (§3.2.2.3.14)
            if (!serverSubscriptionIdentifiersAvailable) {
                require(properties.subscriptionIdentifier.isEmpty()) {
                    "Server does not support subscription identifiers (§3.2.2.3.14)"
                }
            }
        } else {
            // MQTT 3.1.1: validate no 5.0-only subscription options
            subscriptions.forEach { sub ->
                require(!sub.noLocal) {
                    "noLocal subscription option is not supported in MQTT 3.1.1"
                }
                require(!sub.retainAsPublished) {
                    "retainAsPublished subscription option is not supported in MQTT 3.1.1"
                }
                require(sub.retainHandling == RetainHandling.SEND_AT_SUBSCRIBE) {
                    "retainHandling subscription option is not supported in MQTT 3.1.1"
                }
            }
            require(properties == MqttProperties.EMPTY) {
                "Subscribe properties are not supported in MQTT 3.1.1"
            }
        }

        val packetId = packetIdAllocator.allocate()
        val deferred = CompletableDeferred<MqttPacket>()
        registerPendingAck(packetId, deferred)

        try {
            log.debug(TAG) {
                "Sending SUBSCRIBE packetId=$packetId filters=${subscriptions.map { it.topicFilter }}"
            }
            sendPacket(
                Subscribe(
                    packetIdentifier = packetId,
                    subscriptions = subscriptions,
                    properties = properties,
                ),
            )

            val ack =
                withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as? SubAck
                    ?: throw MqttConnectionException(
                        ReasonCode.PROTOCOL_ERROR,
                        "Expected SUBACK for packetId=$packetId",
                    )
            log.debug(TAG) { "Received SUBACK packetId=$packetId reasonCodes=${ack.reasonCodes}" }
            return ack
        } finally {
            removePendingAck(packetId)
            packetIdAllocator.release(packetId)
        }
    }

    /**
     * Unsubscribe from one or more topic filters per §3.10.
     *
     * @param topicFilters List of topic filters to unsubscribe from.
     * @param properties Optional MQTT 5.0 properties for the UNSUBSCRIBE packet.
     * @return The UNSUBACK packet from the broker.
     */
    suspend fun unsubscribe(
        topicFilters: List<String>,
        properties: MqttProperties = MqttProperties.EMPTY,
    ): UnsubAck {
        check(_connectionState.value is ConnectionState.Connected) { "Not connected" }

        val packetId = packetIdAllocator.allocate()
        val deferred = CompletableDeferred<MqttPacket>()
        registerPendingAck(packetId, deferred)

        try {
            log.debug(TAG) { "Sending UNSUBSCRIBE packetId=$packetId filters=$topicFilters" }
            sendPacket(
                Unsubscribe(
                    packetIdentifier = packetId,
                    topicFilters = topicFilters,
                    properties = properties,
                ),
            )

            val ack =
                withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as? UnsubAck
                    ?: throw MqttConnectionException(
                        ReasonCode.PROTOCOL_ERROR,
                        "Expected UNSUBACK for packetId=$packetId",
                    )
            log.debug(TAG) { "Received UNSUBACK packetId=$packetId" }
            return ack
        } finally {
            removePendingAck(packetId)
            packetIdAllocator.release(packetId)
        }
    }

    /**
     * Send an AUTH response to the broker during enhanced authentication (§4.12).
     *
     * Called by the application when it receives an [AuthChallenge] and needs to respond.
     *
     * @param data The authentication response data to send.
     */
    suspend fun sendAuthResponse(data: ByteArray) {
        sendPacket(
            Auth(
                reasonCode = ReasonCode.CONTINUE_AUTHENTICATION,
                properties =
                    MqttProperties(
                        authenticationMethod = config.authenticationMethod,
                        authenticationData = data,
                    ),
            ),
        )
    }

    // --- Private: Packet sending ---

    /** Send a packet over the transport, guarded by [sendMutex] for wire-level serialization. */
    private suspend fun sendPacket(packet: MqttPacket) {
        sendMutex.withLock {
            val bytes = packet.encode(version)

            // Enforce broker's Maximum Packet Size on outbound packets (§3.2.2.3.6)
            serverMaximumPacketSize?.let { maxSize ->
                require(bytes.size <= maxSize) {
                    "Outbound ${packet.packetType} (${bytes.size} bytes) exceeds server " +
                        "Maximum Packet Size ($maxSize) (§3.2.2.3.6)"
                }
            }

            log.trace(TAG) { "Sending ${packet.packetType} (${bytes.size} bytes)" }
            transport.send(bytes)
            lastSendMark = timeSource.markNow()
        }
    }

    /** Register a pending ack deferred for the given packet ID, guarded by [acksMutex]. */
    private suspend fun registerPendingAck(
        packetId: Int,
        deferred: CompletableDeferred<MqttPacket>,
    ) {
        acksMutex.withLock { pendingAcks[packetId] = deferred }
    }

    /** Remove a pending ack for the given packet ID, guarded by [acksMutex]. */
    private suspend fun removePendingAck(packetId: Int) {
        acksMutex.withLock { pendingAcks.remove(packetId) }
    }

    /** Complete a pending ack if one exists for the given packet ID, guarded by [acksMutex]. */
    private suspend fun completePendingAck(
        packetId: Int,
        packet: MqttPacket,
    ) {
        acksMutex.withLock { pendingAcks[packetId]?.complete(packet) }
    }

    // --- Private: QoS flows ---

    private suspend fun publishQos1(
        message: MqttMessage,
        resolvedTopic: String,
        publishProperties: MqttProperties,
    ): ReasonCode {
        val sem = inflightSemaphore
        sem?.acquire()
        try {
            val packetId = packetIdAllocator.allocate()
            val deferred = CompletableDeferred<MqttPacket>()
            registerPendingAck(packetId, deferred)

            try {
                log.debug(TAG) { "QoS 1 PUBLISH packetId=$packetId to '$resolvedTopic'" }
                sendPacket(
                    Publish(
                        topicName = resolvedTopic,
                        payload = message.payload.toByteArray(),
                        qos = QoS.AT_LEAST_ONCE,
                        retain = message.retain,
                        packetIdentifier = packetId,
                        properties = publishProperties,
                    ),
                )

                val ack =
                    withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as? PubAck
                        ?: throw MqttConnectionException(
                            ReasonCode.PROTOCOL_ERROR,
                            "Expected PUBACK for packetId=$packetId",
                        )
                log.debug(TAG) { "Received PUBACK packetId=$packetId reasonCode=${ack.reasonCode}" }
                return ack.reasonCode
            } finally {
                removePendingAck(packetId)
                packetIdAllocator.release(packetId)
            }
        } finally {
            sem?.release()
        }
    }

    private suspend fun publishQos2(
        message: MqttMessage,
        resolvedTopic: String,
        publishProperties: MqttProperties,
    ): ReasonCode {
        val sem = inflightSemaphore
        sem?.acquire()
        try {
            val packetId = packetIdAllocator.allocate()
            val pubRecDeferred = CompletableDeferred<MqttPacket>()
            registerPendingAck(packetId, pubRecDeferred)

            try {
                log.debug(TAG) { "QoS 2 PUBLISH packetId=$packetId to '$resolvedTopic'" }
                sendPacket(
                    Publish(
                        topicName = resolvedTopic,
                        payload = message.payload.toByteArray(),
                        qos = QoS.EXACTLY_ONCE,
                        retain = message.retain,
                        packetIdentifier = packetId,
                        properties = publishProperties,
                    ),
                )

                // Step 1: Wait for PUBREC
                val pubRec =
                    withTimeout(ACK_TIMEOUT_MS) { pubRecDeferred.await() } as? PubRec
                        ?: throw MqttConnectionException(
                            ReasonCode.PROTOCOL_ERROR,
                            "Expected PUBREC for packetId=$packetId",
                        )
                log.debug(TAG) { "QoS 2 received PUBREC packetId=$packetId reasonCode=${pubRec.reasonCode}" }
                removePendingAck(packetId)

                // §4.3.3: If PUBREC reason code >= 0x80, MUST NOT send PUBREL
                if (pubRec.reasonCode.value >= 0x80) {
                    log.warn(TAG) { "QoS 2 PUBREC error packetId=$packetId: ${pubRec.reasonCode}" }
                    return pubRec.reasonCode
                }

                // Step 2: Send PUBREL and wait for PUBCOMP
                val pubCompDeferred = CompletableDeferred<MqttPacket>()
                registerPendingAck(packetId, pubCompDeferred)
                log.debug(TAG) { "QoS 2 sending PUBREL packetId=$packetId" }
                sendPacket(PubRel(packetIdentifier = packetId))

                val pubComp =
                    withTimeout(ACK_TIMEOUT_MS) { pubCompDeferred.await() } as? PubComp
                        ?: throw MqttConnectionException(
                            ReasonCode.PROTOCOL_ERROR,
                            "Expected PUBCOMP for packetId=$packetId",
                        )
                log.debug(TAG) { "QoS 2 received PUBCOMP packetId=$packetId reasonCode=${pubComp.reasonCode}" }
                return pubComp.reasonCode
            } finally {
                removePendingAck(packetId)
                packetIdAllocator.release(packetId)
            }
        } finally {
            sem?.release()
        }
    }

    // --- Private: Read loop ---

    /** Start the background coroutine that continuously reads and dispatches incoming packets. */
    private fun startReadLoop() {
        readLoopJob =
            scope.launch {
                log.debug(TAG) { "Read loop started" }
                try {
                    while (isActive && transport.isConnected) {
                        val bytes = transport.receive()
                        log.trace(TAG) { "Received ${bytes.size} bytes from transport" }

                        // Enforce Maximum Packet Size advertised in CONNECT (§3.1.2.11.5)
                        config.maximumPacketSize?.let { maxSize ->
                            if (bytes.size > maxSize) {
                                log.error(TAG) {
                                    "Received packet (${bytes.size} bytes) exceeds " +
                                        "Maximum Packet Size ($maxSize) — disconnecting"
                                }
                                handleFatalError(ReasonCode.PACKET_TOO_LARGE)
                                return@launch
                            }
                        }

                        val packet = try {
                            decodePacket(bytes, version)
                        } catch (e: IllegalArgumentException) {
                            if (version == MqttProtocolVersion.V5_0) {
                                // Broker may have accepted v5 CONNECT but sends v3.1.1 packets
                                val fallbackPacket = runCatching {
                                    decodePacket(bytes, MqttProtocolVersion.V3_1_1)
                                }.getOrNull()
                                if (fallbackPacket != null) {
                                    log.warn(TAG) {
                                        "Packet decoded as MQTT 3.1.1 (v5 decode failed: ${e.message}). " +
                                            "Downgrading connection to 3.1.1."
                                    }
                                    version = MqttProtocolVersion.V3_1_1
                                    fallbackPacket
                                } else {
                                    throw e
                                }
                            } else {
                                throw e
                            }
                        }
                        log.debug(TAG) { "Received ${packet.packetType}" }
                        handlePacket(packet)
                    }
                } catch (
                    @Suppress("SwallowedException") _: kotlin.coroutines.cancellation.CancellationException,
                ) {
                    // Job was cancelled — expected during disconnect
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    if (isActive) {
                        log.error(TAG, throwable = e) { "Read loop error: ${e.message}" }
                        handleFatalError(cause = e)
                    }
                }
                log.debug(TAG) { "Read loop stopped" }
            }
    }

    /** Centralized fatal error handler: tear down resources and fail all pending waiters. */
    private suspend fun handleFatalError(
        reasonCode: ReasonCode = ReasonCode.UNSPECIFIED_ERROR,
        cause: Throwable? = null,
    ) {
        // Guard against re-entrant calls (e.g., handlePacket → handleFatalError → sendPacket fails)
        if (handlingFatalError) return
        handlingFatalError = true

        // Coerce the cause to a public MqttException to derive the reason code and reason value.
        val mappedReason: MqttException? = cause?.toMqttException(defaultReasonCode = reasonCode)
        // Prefer the explicit reasonCode argument; fall back to the cause's reasonCode if present.
        val effectiveReasonCode =
            if (reasonCode == ReasonCode.UNSPECIFIED_ERROR && mappedReason != null) {
                mappedReason.reasonCode
            } else {
                reasonCode
            }

        log.error(TAG) { "Fatal error — tearing down connection" }
        stopBackgroundJobs()
        try {
            if (transport.isConnected) {
                if (version.supportsProperties) {
                    // §4.13: Send DISCONNECT with appropriate reason code before closing
                    sendPacket(Disconnect(reasonCode = effectiveReasonCode))
                }
                // MQTT 3.1.1: no DISCONNECT with reason code — just close the transport
            }
        } catch (
            @Suppress("SwallowedException") _: kotlin.coroutines.cancellation.CancellationException,
        ) {
            // Scope cancelled during best-effort DISCONNECT — continue cleanup
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
        ) {
            // Best-effort DISCONNECT — transport may already be broken
        }
        try {
            transport.close()
        } catch (
            @Suppress("SwallowedException") _: kotlin.coroutines.cancellation.CancellationException,
        ) {
            // Scope cancelled during best-effort close — continue cleanup
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
        ) {
            // Best-effort transport close
        }
        // Use NonCancellable to ensure cleanup completes even when the calling job
        // (read loop or keepalive) was cancelled by stopBackgroundJobs() above.
        withContext(NonCancellable) {
            failAllPendingAcks()
            val reason =
                mappedReason
                    ?: MqttException.ConnectionLost(
                        reasonCode = effectiveReasonCode,
                        message = "Connection lost (${effectiveReasonCode.name})",
                    )
            _connectionState.value = ConnectionState.Disconnected(reason = reason)
        }
    }

    /** Complete all pending ACK deferreds exceptionally so callers don't hang. */
    private suspend fun failAllPendingAcks() {
        val error = MqttConnectionException(ReasonCode.UNSPECIFIED_ERROR, "Connection lost")
        acksMutex.withLock {
            pendingAcks.values.forEach { it.completeExceptionally(error) }
            pendingAcks.clear()
        }
    }

    /** Dispatch an incoming packet to the appropriate handler. */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun handlePacket(packet: MqttPacket) {
        when (packet) {
            is PubAck -> {
                log.debug(TAG) { "Received PUBACK packetId=${packet.packetIdentifier}" }
                completePendingAck(packet.packetIdentifier, packet)
            }

            is PubRec -> {
                log.debug(TAG) { "Received PUBREC packetId=${packet.packetIdentifier}" }
                completePendingAck(packet.packetIdentifier, packet)
            }

            is PubComp -> {
                log.debug(TAG) { "Received PUBCOMP packetId=${packet.packetIdentifier}" }
                completePendingAck(packet.packetIdentifier, packet)
            }

            is SubAck -> {
                log.debug(TAG) { "Received SUBACK packetId=${packet.packetIdentifier}" }
                completePendingAck(packet.packetIdentifier, packet)
            }

            is UnsubAck -> {
                log.debug(TAG) { "Received UNSUBACK packetId=${packet.packetIdentifier}" }
                completePendingAck(packet.packetIdentifier, packet)
            }

            is PubRel -> {
                log.debug(TAG) { "Received PUBREL packetId=${packet.packetIdentifier}" }
                val deferredMessage =
                    acksMutex.withLock {
                        inboundQos2PacketIds.remove(packet.packetIdentifier)
                        pendingQos2Messages.remove(packet.packetIdentifier)
                    }
                // §3.7.2.1: PACKET_IDENTIFIER_NOT_FOUND if we don't recognize this ID
                val pubCompReasonCode =
                    if (deferredMessage != null) {
                        ReasonCode.SUCCESS
                    } else {
                        ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
                    }
                sendPacket(
                    PubComp(
                        packetIdentifier = packet.packetIdentifier,
                        reasonCode = pubCompReasonCode,
                    ),
                )
                // §3.6.2.1: Only deliver message if PUBREL reason code indicates success
                if (deferredMessage != null && packet.reasonCode.value < 0x80) {
                    _incomingMessages.emit(deferredMessage)
                } else if (deferredMessage == null) {
                    log.warn(TAG) {
                        "PUBREL for unknown packetId=${packet.packetIdentifier} — no stored message"
                    }
                } else {
                    log.warn(TAG) {
                        "PUBREL error for packetId=${packet.packetIdentifier}: " +
                            "${packet.reasonCode} — discarding message"
                    }
                }
            }

            is Publish -> {
                handleIncomingPublish(packet)
            }

            is Disconnect -> {
                if (!version.supportsProperties) {
                    // MQTT 3.1.1: server should never send DISCONNECT
                    log.error(TAG) { "Protocol violation: received DISCONNECT from broker in MQTT 3.1.1" }
                    handleFatalError(ReasonCode.PROTOCOL_ERROR)
                    return
                }
                log.warn(TAG) { "Received DISCONNECT from broker: reasonCode=${packet.reasonCode}" }
                val isRedirect =
                    packet.reasonCode == ReasonCode.USE_ANOTHER_SERVER ||
                        packet.reasonCode == ReasonCode.SERVER_MOVED
                val serverRef = packet.properties.serverReference
                if (isRedirect && serverRef != null) {
                    log.info(TAG) {
                        "Server redirect (${packet.reasonCode}): $serverRef"
                    }
                    _serverRedirect.emit(
                        ServerRedirect(
                            reasonCode = packet.reasonCode,
                            serverReference = serverRef,
                            permanent = packet.reasonCode == ReasonCode.SERVER_MOVED,
                        ),
                    )
                }
                stopBackgroundJobs()
                transport.close()
                failAllPendingAcks()
                _connectionState.value =
                    ConnectionState.Disconnected(
                        reason =
                            MqttException.ConnectionLost(
                                reasonCode = packet.reasonCode,
                                message = "Server initiated disconnect (${packet.reasonCode})",
                            ),
                    )
            }

            is ConnAck, is Connect, is Subscribe, is Unsubscribe -> {
                log.error(TAG) {
                    "Protocol violation: unexpected ${packet.packetType} from broker — disconnecting"
                }
                handleFatalError(ReasonCode.PROTOCOL_ERROR)
            }

            is PingReq -> {
                log.error(TAG) { "Protocol violation: received PINGREQ from broker — disconnecting" }
                handleFatalError(ReasonCode.PROTOCOL_ERROR)
            }

            is PingResp -> {
                log.trace(TAG) { "Received PINGRESP" }
                awaitingPingResp = false
            }

            is Auth -> {
                if (!version.supportsProperties) {
                    // MQTT 3.1.1: AUTH packet does not exist
                    log.error(TAG) { "Protocol violation: received AUTH packet in MQTT 3.1.1" }
                    handleFatalError(ReasonCode.PROTOCOL_ERROR)
                    return
                }
                log.debug(TAG) { "Received AUTH: reasonCode=${packet.reasonCode}" }
                handleAuthPacket(packet)
            }
        }
    }

    /**
     * Handle an incoming PUBLISH packet: resolve topic aliases, send QoS acks, emit the message.
     *
     * For QoS 2, the message is stored and NOT emitted until PUBREL is received (§4.3.3).
     * This ensures exactly-once delivery semantics — the message is only delivered to the
     * application after the sender has committed (PUBREL).
     */
    private suspend fun handleIncomingPublish(packet: Publish) {
        // QoS 2 duplicate suppression — track packet IDs to prevent double delivery (§4.3.3)
        if (packet.qos == QoS.EXACTLY_ONCE && packet.packetIdentifier != null) {
            val isDuplicate =
                acksMutex.withLock {
                    !inboundQos2PacketIds.add(packet.packetIdentifier)
                }
            if (isDuplicate) {
                log.debug(TAG) {
                    "QoS 2 duplicate PUBLISH packetId=${packet.packetIdentifier}, resending PUBREC"
                }
                sendPacket(PubRec(packetIdentifier = packet.packetIdentifier))
                return
            }
        }

        val resolvedTopic = resolveInboundTopicAlias(packet)
        log.debug(TAG) {
            "Incoming PUBLISH topic='$resolvedTopic' qos=${packet.qos} retain=${packet.retain}" +
                (packet.packetIdentifier?.let { " packetId=$it" } ?: "")
        }

        val message =
            MqttMessage(
                topic = resolvedTopic,
                payload = ByteString(packet.payload),
                qos = packet.qos,
                retain = packet.retain,
                properties = extractPublishProperties(packet.properties),
            )

        when (packet.qos) {
            QoS.AT_MOST_ONCE -> {
                _incomingMessages.emit(message)
            }

            QoS.AT_LEAST_ONCE -> {
                // Send PUBACK before emitting to prevent slow consumers from stalling ACKs
                packet.packetIdentifier?.let { sendPacket(PubAck(packetIdentifier = it)) }
                _incomingMessages.emit(message)
            }

            QoS.EXACTLY_ONCE -> {
                // §4.3.3: Send PUBREC but defer delivery until PUBREL arrives.
                // Store the resolved message so topic alias state can't change under us.
                packet.packetIdentifier?.let { packetId ->
                    acksMutex.withLock { pendingQos2Messages[packetId] = message }
                    sendPacket(PubRec(packetIdentifier = packetId))
                }
            }
        }
    }

    // --- Private: Keepalive ---

    /**
     * Start the keepalive timer per §3.1.2.10.
     *
     * Sends PINGREQ every `effectiveKeepAliveSeconds * 0.75` seconds if no other packet was sent
     * within that interval. Honors Server Keep Alive from CONNACK (§3.2.2.3.14).
     * Detects dead connections if no PINGRESP is received within the keepalive period.
     */
    private fun startKeepAlive() {
        if (effectiveKeepAliveSeconds <= 0) return

        val intervalMs = (effectiveKeepAliveSeconds * KEEPALIVE_FACTOR).toLong()
        val deadlineMs = effectiveKeepAliveSeconds * MILLIS_PER_SECOND.toLong()
        log.debug(TAG) { "Keepalive started: interval=${intervalMs}ms, deadline=${deadlineMs}ms" }
        keepAliveJob =
            scope.launch {
                try {
                    while (isActive && transport.isConnected) {
                        delay(intervalMs)

                        // Check for PINGRESP timeout — dead connection.
                        // Timeout fires if PINGRESP not received within keepAliveSeconds after PINGREQ.
                        if (awaitingPingResp) {
                            val pingElapsedMs = pingSentMark.elapsedNow().inWholeMilliseconds
                            if (pingElapsedMs >= deadlineMs) {
                                log.warn(TAG) {
                                    "PINGRESP timeout (${pingElapsedMs}ms since PINGREQ) — connection assumed dead"
                                }
                                handleFatalError()
                                return@launch
                            }
                        }

                        // Only send PINGREQ if we're not already awaiting a PINGRESP.
                        // Otherwise pingSentMark would keep resetting and the timeout
                        // could never fire (interval < deadline, always).
                        if (!awaitingPingResp) {
                            val elapsedMs =
                                sendMutex.withLock { lastSendMark.elapsedNow().inWholeMilliseconds }
                            if (elapsedMs >= intervalMs) {
                                log.trace(TAG) { "Sending PINGREQ (${elapsedMs}ms since last send)" }
                                awaitingPingResp = true
                                pingSentMark = timeSource.markNow()
                                sendPacket(PingReq)
                            }
                        }
                    }
                } catch (
                    @Suppress("SwallowedException") _: kotlin.coroutines.cancellation.CancellationException,
                ) {
                    // Job was cancelled — expected during disconnect
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    if (isActive) {
                        log.error(TAG, throwable = e) { "Keepalive error: ${e.message}" }
                        handleFatalError()
                    }
                }
            }
    }

    // --- Private: Helpers ---

    private fun stopBackgroundJobs() {
        keepAliveJob?.cancel()
        readLoopJob?.cancel()
        keepAliveJob = null
        readLoopJob = null
    }

    /** Build a CONNECT packet from [config] per §3.1. */
    @Suppress("CyclomaticComplexMethod")
    private fun buildConnectPacket(): Connect {
        val connectProperties =
            if (version.supportsProperties) {
                MqttProperties(
                    sessionExpiryInterval = config.sessionExpiryInterval,
                    receiveMaximum = if (config.receiveMaximum != DEFAULT_RECEIVE_MAXIMUM) config.receiveMaximum else null,
                    maximumPacketSize = config.maximumPacketSize,
                    topicAliasMaximum = if (config.topicAliasMaximum != 0) config.topicAliasMaximum else null,
                    requestResponseInformation = if (config.requestResponseInformation) true else null,
                    requestProblemInformation = if (!config.requestProblemInformation) false else null,
                    userProperties = config.userProperties,
                    authenticationMethod = config.authenticationMethod,
                    authenticationData = config.authenticationData?.toByteArray(),
                )
            } else {
                MqttProperties.EMPTY
            }

        val willConfig = config.will
        return Connect(
            protocolLevel = version.protocolLevel,
            cleanStart = config.cleanStart,
            keepAliveSeconds = config.keepAliveSeconds,
            clientId = config.clientId,
            willTopic = willConfig?.topic,
            willPayload = willConfig?.payload?.toByteArray(),
            willQos = willConfig?.qos ?: QoS.AT_MOST_ONCE,
            willRetain = willConfig?.retain ?: false,
            willProperties =
                if (version.supportsProperties) {
                    willConfig?.let { buildWillProperties(it) } ?: MqttProperties.EMPTY
                } else {
                    MqttProperties.EMPTY
                },
            username = config.username,
            password = config.password?.toByteArray(),
            properties = connectProperties,
        )
    }

    /** Build MQTT 5.0 will properties from [WillConfig] per §3.1.3.2. */
    private fun buildWillProperties(will: WillConfig): MqttProperties =
        MqttProperties(
            willDelayInterval = will.willDelayInterval,
            messageExpiryInterval = will.messageExpiryInterval,
            contentType = will.contentType,
            responseTopic = will.responseTopic,
            correlationData = will.correlationData?.toByteArray(),
            payloadFormatIndicator = will.payloadFormatIndicator,
            userProperties = will.userProperties,
        )

    /** Build wire-level [MqttProperties] from user-facing [PublishProperties]. */
    private fun buildPublishProperties(props: PublishProperties): MqttProperties =
        MqttProperties(
            messageExpiryInterval = props.messageExpiryInterval,
            topicAlias = props.topicAlias,
            contentType = props.contentType,
            responseTopic = props.responseTopic,
            correlationData = props.correlationData?.toByteArray(),
            payloadFormatIndicator = props.payloadFormatIndicator,
            userProperties = props.userProperties,
            // subscriptionIdentifier intentionally omitted — it is set by the broker
            // on inbound PUBLISH only, not sent by clients on outbound PUBLISH (§3.3.2.3.8)
        )

    /** Extract user-facing [PublishProperties] from wire-level [MqttProperties]. */
    private fun extractPublishProperties(props: MqttProperties): PublishProperties =
        PublishProperties(
            messageExpiryInterval = props.messageExpiryInterval,
            topicAlias = props.topicAlias,
            contentType = props.contentType,
            responseTopic = props.responseTopic,
            correlationData = props.correlationData?.let { ByteString(it) },
            payloadFormatIndicator = props.payloadFormatIndicator,
            userProperties = props.userProperties,
            subscriptionIdentifiers = props.subscriptionIdentifier,
        )

    // --- Private: Topic Alias (§3.3.2.3.4) ---

    /**
     * Apply outbound topic alias mapping for the given [topic].
     *
     * - First publish to a topic: assigns a new alias, sends both topic name and alias.
     * - Subsequent publishes: sends empty topic name with alias only.
     * - Respects [serverTopicAliasMaximum] from CONNACK.
     *
     * @return Pair of (resolved topic name, updated properties).
     */
    private suspend fun applyOutboundTopicAlias(
        topic: String,
        properties: MqttProperties,
    ): Pair<String, MqttProperties> =
        aliasMutex.withLock {
            if (serverTopicAliasMaximum <= 0) return@withLock topic to properties

            val existingAlias = outboundTopicAliases[topic]
            if (existingAlias != null) {
                // Topic already has an alias — send empty topic + alias
                return@withLock "" to properties.copy(topicAlias = existingAlias)
            }

            // Assign a new alias if we haven't exceeded the server's limit
            if (nextOutboundAlias <= serverTopicAliasMaximum) {
                val alias = nextOutboundAlias++
                outboundTopicAliases[topic] = alias
                return@withLock topic to properties.copy(topicAlias = alias)
            }

            // No more aliases available — send topic without alias
            topic to properties
        }

    /**
     * Resolve inbound topic alias from a received PUBLISH packet (§3.3.2.3.4).
     *
     * - If the packet has a topic alias with a non-empty topic: store the mapping, return the topic.
     * - If the packet has a topic alias with an empty topic: resolve from stored mapping.
     * - If the packet has no topic alias: return the topic as-is.
     */
    private suspend fun resolveInboundTopicAlias(packet: Publish): String {
        val alias = packet.properties.topicAlias
        return if (alias != null) {
            // §3.3.2.3.4: Topic Alias greater than Topic Alias Maximum is a Protocol Error
            if (alias > config.topicAliasMaximum) {
                throw MqttConnectionException(
                    ReasonCode.TOPIC_ALIAS_INVALID,
                    "Inbound topic alias $alias exceeds advertised maximum ${config.topicAliasMaximum} (§3.3.2.3.4)",
                )
            }
            aliasMutex.withLock {
                if (packet.topicName.isNotEmpty()) {
                    inboundTopicAliases[alias] = packet.topicName
                    packet.topicName
                } else {
                    inboundTopicAliases[alias]
                        ?: throw MqttConnectionException(
                            ReasonCode.TOPIC_ALIAS_INVALID,
                            "Unknown inbound topic alias: $alias (§3.3.2.3.4)",
                        )
                }
            }
        } else {
            packet.topicName
        }
    }

    // --- Private: Enhanced Auth (§4.12) ---

    /** Emit an authentication challenge to the [authChallenges] flow. */
    private suspend fun handleAuthPacket(packet: Auth) {
        _authChallenges.emit(
            AuthChallenge(
                reasonCode = packet.reasonCode,
                authenticationMethod = packet.properties.authenticationMethod,
                authenticationData = packet.properties.authenticationData?.let { ByteString(it) },
            ),
        )
    }

    internal companion object {
        private const val TAG = "MqttConnection"

        /** Extra buffer capacity for incoming message flow. */
        const val INCOMING_BUFFER_CAPACITY = 64

        /** Extra buffer capacity for auth challenge flow. */
        const val AUTH_BUFFER_CAPACITY = 8

        /** Default server Receive Maximum per §3.2.2.3.2. */
        const val DEFAULT_RECEIVE_MAXIMUM = 65_535

        /** Timeout for waiting on acknowledgement packets (30 seconds). */
        const val ACK_TIMEOUT_MS = 30_000L

        /** Keepalive factor: send PINGREQ at 75% of keep alive interval. */
        const val KEEPALIVE_FACTOR = 750 // keepAliveSeconds * 750 = milliseconds at 75%

        const val MILLIS_PER_SECOND = 1000
    }
}

/**
 * Internal connection exception — wraps [MqttException.ConnectionRejected] for internal use.
 *
 * This is the primary exception type thrown within the connection layer.
 * [MqttClient] catches it and re-throws the appropriate public [MqttException] subtype.
 *
 * @property reasonCode The MQTT 5.0 reason code from the broker.
 * @property serverReference Server reference from CONNACK/DISCONNECT for redirect handling (§4.13).
 */
internal class MqttConnectionException(
    val reasonCode: ReasonCode,
    message: String,
    cause: Throwable? = null,
    val serverReference: String? = null,
) : Exception(message, cause)

/**
 * Represents a server redirect instruction received via CONNACK or DISCONNECT (§4.13).
 *
 * @property reasonCode Either [ReasonCode.USE_ANOTHER_SERVER] (temporary) or [ReasonCode.SERVER_MOVED] (permanent).
 * @property serverReference The server reference string (host:port or URI).
 * @property permanent `true` for SERVER_MOVED (0x9D), `false` for USE_ANOTHER_SERVER (0x9C).
 */
internal data class ServerRedirect(
    val reasonCode: ReasonCode,
    val serverReference: String,
    val permanent: Boolean,
)
