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
    val authenticationData: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthChallenge) return false
        return reasonCode == other.reasonCode &&
            authenticationMethod == other.authenticationMethod &&
            authenticationData.contentEqualsNullable(other.authenticationData)
    }

    override fun hashCode(): Int {
        var result = reasonCode.hashCode()
        result = 31 * result + (authenticationMethod?.hashCode() ?: 0)
        result = 31 * result + (authenticationData?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this === other -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }

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
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** Observable connection state. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = INCOMING_BUFFER_CAPACITY)

    /** Flow of incoming PUBLISH messages received from the broker. */
    val incomingMessages: SharedFlow<MqttMessage> = _incomingMessages.asSharedFlow()

    private val _authChallenges = MutableSharedFlow<AuthChallenge>(extraBufferCapacity = AUTH_BUFFER_CAPACITY)

    /** Flow of authentication challenges from the broker during enhanced auth (§4.12). */
    val authChallenges: SharedFlow<AuthChallenge> = _authChallenges.asSharedFlow()

    private val packetIdAllocator = PacketIdAllocator()
    private val sendMutex = Mutex()
    private val acksMutex = Mutex()
    private val pendingAcks = mutableMapOf<Int, CompletableDeferred<MqttPacket>>()
    private val inboundQos2PacketIds = mutableSetOf<Int>()

    private var readLoopJob: Job? = null
    private var keepAliveJob: Job? = null
    private var lastSendMark: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    @Volatile
    private var awaitingPingResp = false

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

    private val _serverRedirect = MutableSharedFlow<ServerRedirect>(extraBufferCapacity = 1)

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
        _connectionState.value = ConnectionState.CONNECTING
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
                _connectionState.value = ConnectionState.DISCONNECTED
                val serverRef = connAck.properties.serverReference
                throw MqttConnectionException(
                    connAck.reasonCode,
                    "Connection refused: ${connAck.reasonCode}",
                    serverReference = serverRef,
                )
            }

            log.debug(TAG) { "Received CONNACK: reasonCode=${connAck.reasonCode}" }

            // Store server-assigned values from CONNACK properties
            connAck.properties.receiveMaximum?.let { serverReceiveMaximum = it }
            connAck.properties.assignedClientIdentifier?.let { assignedClientId = it }
            connAck.properties.topicAliasMaximum?.let { serverTopicAliasMaximum = it }
            connAck.properties.sharedSubscriptionAvailable?.let { serverSharedSubscriptionAvailable = it }

            // Honor Server Keep Alive override (§3.2.2.3.14)
            effectiveKeepAliveSeconds =
                connAck.properties.serverKeepAlive ?: config.keepAliveSeconds

            // Initialize flow control semaphore based on server's Receive Maximum (§3.3.4)
            inflightSemaphore = Semaphore(serverReceiveMaximum)

            log.debug(TAG) {
                "Session established: keepAlive=${effectiveKeepAliveSeconds}s, " +
                    "serverReceiveMax=$serverReceiveMaximum, " +
                    "topicAliasMax=$serverTopicAliasMaximum" +
                    (assignedClientId?.let { ", assignedClientId=$it" } ?: "")
            }

            _connectionState.value = ConnectionState.CONNECTED
            startReadLoop()
            startKeepAlive()

            return connAck
        } catch (e: MqttConnectionException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error(TAG, throwable = e) { "Connection failed: ${e.message}" }
            _connectionState.value = ConnectionState.DISCONNECTED
            throw MqttConnectionException(ReasonCode.UNSPECIFIED_ERROR, "Connection failed: ${e.message}", e)
        }
    }

    /**
     * Read packets from the transport until CONNACK is received.
     * Handles interleaved AUTH packets for enhanced authentication (§4.12).
     */
    private suspend fun awaitConnAck(): ConnAck {
        while (true) {
            val responseBytes = transport.receive()
            val response = decodePacket(responseBytes)

            when (response) {
                is ConnAck -> {
                    return response
                }

                is Auth -> {
                    _authChallenges.emit(
                        AuthChallenge(
                            reasonCode = response.reasonCode,
                            authenticationMethod = response.properties.authenticationMethod,
                            authenticationData = response.properties.authenticationData,
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
    }

    /**
     * Gracefully disconnect from the broker per §3.14.
     *
     * Stops keepalive and read loop, sends a DISCONNECT packet, then closes the transport.
     */
    suspend fun disconnect(reasonCode: ReasonCode = ReasonCode.SUCCESS) {
        log.debug(TAG) { "Disconnecting with reasonCode=$reasonCode" }
        stopBackgroundJobs()

        try {
            if (transport.isConnected) {
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
        _connectionState.value = ConnectionState.DISCONNECTED
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
        }
        inflightSemaphore = null
        awaitingPingResp = false
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
        check(_connectionState.value == ConnectionState.CONNECTED) { "Not connected" }

        val publishProperties = buildPublishProperties(message.properties)
        val (resolvedTopic, resolvedProperties) = applyOutboundTopicAlias(message.topic, publishProperties)

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
        check(_connectionState.value == ConnectionState.CONNECTED) { "Not connected" }

        // Enforce shared subscription availability (§4.8.2)
        if (!serverSharedSubscriptionAvailable) {
            subscriptions.forEach { sub ->
                require(!TopicValidator.isSharedSubscription(sub.topicFilter)) {
                    "Server does not support shared subscriptions (§4.8.2): '${sub.topicFilter}'"
                }
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

            val ack = withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as SubAck
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
        check(_connectionState.value == ConnectionState.CONNECTED) { "Not connected" }

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

            val ack = withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as UnsubAck
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
            val bytes = packet.encode()
            log.trace(TAG) { "Sending ${packet.packetType} (${bytes.size} bytes)" }
            transport.send(bytes)
            lastSendMark = TimeSource.Monotonic.markNow()
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
        inflightSemaphore?.acquire()
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

                val ack = withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as PubAck
                log.debug(TAG) { "Received PUBACK packetId=$packetId reasonCode=${ack.reasonCode}" }
                return ack.reasonCode
            } finally {
                removePendingAck(packetId)
                packetIdAllocator.release(packetId)
            }
        } finally {
            inflightSemaphore?.release()
        }
    }

    private suspend fun publishQos2(
        message: MqttMessage,
        resolvedTopic: String,
        publishProperties: MqttProperties,
    ): ReasonCode {
        inflightSemaphore?.acquire()
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
                withTimeout(ACK_TIMEOUT_MS) { pubRecDeferred.await() } as PubRec
                log.debug(TAG) { "QoS 2 received PUBREC packetId=$packetId" }
                removePendingAck(packetId)

                // Step 2: Send PUBREL and wait for PUBCOMP
                val pubCompDeferred = CompletableDeferred<MqttPacket>()
                registerPendingAck(packetId, pubCompDeferred)
                log.debug(TAG) { "QoS 2 sending PUBREL packetId=$packetId" }
                sendPacket(PubRel(packetIdentifier = packetId))

                val pubComp = withTimeout(ACK_TIMEOUT_MS) { pubCompDeferred.await() } as PubComp
                log.debug(TAG) { "QoS 2 received PUBCOMP packetId=$packetId reasonCode=${pubComp.reasonCode}" }
                return pubComp.reasonCode
            } finally {
                removePendingAck(packetId)
                packetIdAllocator.release(packetId)
            }
        } finally {
            inflightSemaphore?.release()
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
                        val packet = decodePacket(bytes)
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
                        handleFatalError()
                    }
                }
                log.debug(TAG) { "Read loop stopped" }
            }
    }

    /** Centralized fatal error handler: tear down resources and fail all pending waiters. */
    private suspend fun handleFatalError() {
        log.error(TAG) { "Fatal error — tearing down connection" }
        stopBackgroundJobs()
        try {
            transport.close()
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception,
        ) {
            // Best-effort transport close
        }
        failAllPendingAcks()
        _connectionState.value = ConnectionState.DISCONNECTED
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
                log.debug(TAG) { "Received PUBREL packetId=${packet.packetIdentifier}, sending PUBCOMP" }
                acksMutex.withLock { inboundQos2PacketIds.remove(packet.packetIdentifier) }
                sendPacket(PubComp(packetIdentifier = packet.packetIdentifier))
            }

            is Publish -> {
                handleIncomingPublish(packet)
            }

            is Disconnect -> {
                log.warn(TAG) { "Received DISCONNECT from broker: reasonCode=${packet.reasonCode}" }
                val isRedirect =
                    packet.reasonCode == ReasonCode.USE_ANOTHER_SERVER ||
                        packet.reasonCode == ReasonCode.SERVER_MOVED
                if (isRedirect && packet.properties.serverReference != null) {
                    log.info(TAG) {
                        "Server redirect (${packet.reasonCode}): ${packet.properties.serverReference}"
                    }
                    _serverRedirect.emit(
                        ServerRedirect(
                            reasonCode = packet.reasonCode,
                            serverReference = packet.properties.serverReference!!,
                            permanent = packet.reasonCode == ReasonCode.SERVER_MOVED,
                        ),
                    )
                }
                _connectionState.value = ConnectionState.DISCONNECTED
                stopBackgroundJobs()
                transport.close()
            }

            is ConnAck, is Connect, is Subscribe, is Unsubscribe -> {
                log.warn(TAG) { "Unexpected ${packet.packetType} from broker — protocol violation" }
            }

            is PingReq, is PingResp -> {
                if (packet is PingResp) {
                    log.trace(TAG) { "Received PINGRESP" }
                    awaitingPingResp = false
                }
            }

            is Auth -> {
                log.debug(TAG) { "Received AUTH: reasonCode=${packet.reasonCode}" }
                handleAuthPacket(packet)
            }
        }
    }

    /** Handle an incoming PUBLISH packet: resolve topic aliases, emit the message, and send QoS acks. */
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
        _incomingMessages.emit(message)

        when (packet.qos) {
            QoS.AT_MOST_ONCE -> { /* No ack needed */ }

            QoS.AT_LEAST_ONCE -> {
                packet.packetIdentifier?.let { sendPacket(PubAck(packetIdentifier = it)) }
            }

            QoS.EXACTLY_ONCE -> {
                packet.packetIdentifier?.let { sendPacket(PubRec(packetIdentifier = it)) }
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
                while (isActive && transport.isConnected) {
                    delay(intervalMs)

                    // Check for PINGRESP timeout — dead connection
                    if (awaitingPingResp) {
                        log.warn(TAG) { "PINGRESP timeout — connection assumed dead" }
                        handleFatalError()
                        return@launch
                    }

                    val elapsedMs = sendMutex.withLock { lastSendMark.elapsedNow().inWholeMilliseconds }
                    if (elapsedMs >= intervalMs) {
                        log.trace(TAG) { "Sending PINGREQ (${elapsedMs}ms since last send)" }
                        awaitingPingResp = true
                        sendPacket(PingReq)
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

        val willConfig = config.will
        return Connect(
            cleanStart = config.cleanStart,
            keepAliveSeconds = config.keepAliveSeconds,
            clientId = config.clientId,
            willTopic = willConfig?.topic,
            willPayload = willConfig?.payload?.toByteArray(),
            willQos = willConfig?.qos ?: QoS.AT_MOST_ONCE,
            willRetain = willConfig?.retain ?: false,
            willProperties = willConfig?.let { buildWillProperties(it) } ?: MqttProperties.EMPTY,
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
            subscriptionIdentifier = props.subscriptionIdentifiers,
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
                authenticationData = packet.properties.authenticationData,
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
 * Exception thrown when an MQTT connection fails.
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
