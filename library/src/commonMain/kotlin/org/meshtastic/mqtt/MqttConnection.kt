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
import org.meshtastic.mqtt.packet.ReasonCode
import org.meshtastic.mqtt.packet.SubAck
import org.meshtastic.mqtt.packet.Subscribe
import org.meshtastic.mqtt.packet.Subscription
import org.meshtastic.mqtt.packet.UnsubAck
import org.meshtastic.mqtt.packet.Unsubscribe
import org.meshtastic.mqtt.packet.decodePacket
import org.meshtastic.mqtt.packet.encode
import kotlin.time.TimeSource

/**
 * Internal connection manager implementing the MQTT 5.0 client state machine.
 *
 * Manages the full connection lifecycle: CONNECT/CONNACK handshake, keepalive via PINGREQ/PINGRESP,
 * background read loop, QoS 0/1/2 publish flows, subscribe/unsubscribe, and graceful disconnect.
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
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** Observable connection state. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = INCOMING_BUFFER_CAPACITY)

    /** Flow of incoming PUBLISH messages received from the broker. */
    val incomingMessages: SharedFlow<MqttMessage> = _incomingMessages.asSharedFlow()

    private val packetIdAllocator = PacketIdAllocator()
    private val sendMutex = Mutex()
    private val pendingAcks = mutableMapOf<Int, CompletableDeferred<MqttPacket>>()

    private var readLoopJob: Job? = null
    private var keepAliveJob: Job? = null
    private var lastSendMark: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    // Server-assigned values from CONNACK
    private var serverReceiveMaximum: Int = DEFAULT_RECEIVE_MAXIMUM
    private var assignedClientId: String? = null

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

        try {
            transport.connect(endpoint)
            val connectPacket = buildConnectPacket()
            sendPacket(connectPacket)

            // Read and decode CONNACK
            val responseBytes = transport.receive()
            val response = decodePacket(responseBytes)
            val connAck =
                response as? ConnAck
                    ?: throw MqttConnectionException(
                        ReasonCode.PROTOCOL_ERROR,
                        "Expected CONNACK, got ${response.packetType}",
                    )

            if (connAck.reasonCode != ReasonCode.SUCCESS) {
                transport.close()
                _connectionState.value = ConnectionState.DISCONNECTED
                throw MqttConnectionException(connAck.reasonCode, "Connection refused: ${connAck.reasonCode}")
            }

            // Store server-assigned values from CONNACK properties
            connAck.properties.receiveMaximum?.let { serverReceiveMaximum = it }
            connAck.properties.assignedClientIdentifier?.let { assignedClientId = it }

            _connectionState.value = ConnectionState.CONNECTED
            startReadLoop()
            startKeepAlive()

            return connAck
        } catch (e: MqttConnectionException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            _connectionState.value = ConnectionState.DISCONNECTED
            throw MqttConnectionException(ReasonCode.UNSPECIFIED_ERROR, "Connection failed: ${e.message}", e)
        }
    }

    /**
     * Gracefully disconnect from the broker per §3.14.
     *
     * Stops keepalive and read loop, sends a DISCONNECT packet, then closes the transport.
     */
    suspend fun disconnect(reasonCode: ReasonCode = ReasonCode.SUCCESS) {
        stopBackgroundJobs()

        try {
            if (transport.isConnected) {
                sendPacket(Disconnect(reasonCode = reasonCode))
                transport.close()
            }
        } finally {
            _connectionState.value = ConnectionState.DISCONNECTED
            if (config.cleanStart) {
                packetIdAllocator.reset()
            }
        }
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

        return when (message.qos) {
            QoS.AT_MOST_ONCE -> {
                sendPacket(
                    Publish(
                        topicName = message.topic,
                        payload = message.payload.toByteArray(),
                        qos = QoS.AT_MOST_ONCE,
                        retain = message.retain,
                        properties = publishProperties,
                    ),
                )
                null
            }

            QoS.AT_LEAST_ONCE -> {
                publishQos1(message, publishProperties)
            }

            QoS.EXACTLY_ONCE -> {
                publishQos2(message, publishProperties)
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

        val packetId = packetIdAllocator.allocate()
        val deferred = CompletableDeferred<MqttPacket>()
        pendingAcks[packetId] = deferred

        try {
            sendPacket(
                Subscribe(
                    packetIdentifier = packetId,
                    subscriptions = subscriptions,
                    properties = properties,
                ),
            )

            val ack = withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as SubAck
            return ack
        } finally {
            pendingAcks.remove(packetId)
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
        pendingAcks[packetId] = deferred

        try {
            sendPacket(
                Unsubscribe(
                    packetIdentifier = packetId,
                    topicFilters = topicFilters,
                    properties = properties,
                ),
            )

            val ack = withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as UnsubAck
            return ack
        } finally {
            pendingAcks.remove(packetId)
            packetIdAllocator.release(packetId)
        }
    }

    // --- Private: Packet sending ---

    /** Send a packet over the transport, guarded by [sendMutex] for wire-level serialization. */
    private suspend fun sendPacket(packet: MqttPacket) {
        sendMutex.withLock {
            transport.send(packet.encode())
            lastSendMark = TimeSource.Monotonic.markNow()
        }
    }

    // --- Private: QoS flows ---

    private suspend fun publishQos1(
        message: MqttMessage,
        publishProperties: MqttProperties,
    ): ReasonCode {
        val packetId = packetIdAllocator.allocate()
        val deferred = CompletableDeferred<MqttPacket>()
        pendingAcks[packetId] = deferred

        try {
            sendPacket(
                Publish(
                    topicName = message.topic,
                    payload = message.payload.toByteArray(),
                    qos = QoS.AT_LEAST_ONCE,
                    retain = message.retain,
                    packetIdentifier = packetId,
                    properties = publishProperties,
                ),
            )

            val ack = withTimeout(ACK_TIMEOUT_MS) { deferred.await() } as PubAck
            return ack.reasonCode
        } finally {
            pendingAcks.remove(packetId)
            packetIdAllocator.release(packetId)
        }
    }

    private suspend fun publishQos2(
        message: MqttMessage,
        publishProperties: MqttProperties,
    ): ReasonCode {
        val packetId = packetIdAllocator.allocate()
        val pubRecDeferred = CompletableDeferred<MqttPacket>()
        pendingAcks[packetId] = pubRecDeferred

        try {
            sendPacket(
                Publish(
                    topicName = message.topic,
                    payload = message.payload.toByteArray(),
                    qos = QoS.EXACTLY_ONCE,
                    retain = message.retain,
                    packetIdentifier = packetId,
                    properties = publishProperties,
                ),
            )

            // Step 1: Wait for PUBREC
            withTimeout(ACK_TIMEOUT_MS) { pubRecDeferred.await() } as PubRec
            pendingAcks.remove(packetId)

            // Step 2: Send PUBREL and wait for PUBCOMP
            val pubCompDeferred = CompletableDeferred<MqttPacket>()
            pendingAcks[packetId] = pubCompDeferred
            sendPacket(PubRel(packetIdentifier = packetId))

            val pubComp = withTimeout(ACK_TIMEOUT_MS) { pubCompDeferred.await() } as PubComp
            return pubComp.reasonCode
        } finally {
            pendingAcks.remove(packetId)
            packetIdAllocator.release(packetId)
        }
    }

    // --- Private: Read loop ---

    /** Start the background coroutine that continuously reads and dispatches incoming packets. */
    private fun startReadLoop() {
        readLoopJob =
            scope.launch {
                try {
                    while (isActive && transport.isConnected) {
                        val bytes = transport.receive()
                        val packet = decodePacket(bytes)
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
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
    }

    /** Dispatch an incoming packet to the appropriate handler. */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun handlePacket(packet: MqttPacket) {
        when (packet) {
            is PubAck -> {
                pendingAcks[packet.packetIdentifier]?.complete(packet)
            }

            is PubRec -> {
                pendingAcks[packet.packetIdentifier]?.complete(packet)
            }

            is PubComp -> {
                pendingAcks[packet.packetIdentifier]?.complete(packet)
            }

            is SubAck -> {
                pendingAcks[packet.packetIdentifier]?.complete(packet)
            }

            is UnsubAck -> {
                pendingAcks[packet.packetIdentifier]?.complete(packet)
            }

            is PubRel -> {
                // Server-side QoS 2 completion: respond with PUBCOMP
                sendPacket(PubComp(packetIdentifier = packet.packetIdentifier))
            }

            is Publish -> {
                handleIncomingPublish(packet)
            }

            is Disconnect -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                stopBackgroundJobs()
                transport.close()
            }

            is ConnAck, is Connect, is Subscribe, is Unsubscribe -> {
                // Unexpected packets from server — protocol violation
            }

            is PingReq, is PingResp, is Auth -> {
                // PingResp absorbed, PingReq/Auth ignored for now
            }
        }
    }

    /** Handle an incoming PUBLISH packet: emit the message and send QoS acknowledgements. */
    private suspend fun handleIncomingPublish(packet: Publish) {
        val message =
            MqttMessage(
                topic = packet.topicName,
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
     * Sends PINGREQ every `keepAliveSeconds * 0.75` seconds if no other packet was sent
     * within that interval.
     */
    private fun startKeepAlive() {
        if (config.keepAliveSeconds <= 0) return

        val intervalMs = (config.keepAliveSeconds * KEEPALIVE_FACTOR).toLong()
        keepAliveJob =
            scope.launch {
                while (isActive && transport.isConnected) {
                    delay(intervalMs)
                    val elapsedMs = lastSendMark.elapsedNow().inWholeMilliseconds
                    if (elapsedMs >= intervalMs) {
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

    internal companion object {
        /** Extra buffer capacity for incoming message flow. */
        const val INCOMING_BUFFER_CAPACITY = 64

        /** Default server Receive Maximum per §3.2.2.3.2. */
        const val DEFAULT_RECEIVE_MAXIMUM = 65_535

        /** Timeout for waiting on acknowledgement packets (30 seconds). */
        const val ACK_TIMEOUT_MS = 30_000L

        /** Keepalive factor: send PINGREQ at 75% of keep alive interval. */
        const val KEEPALIVE_FACTOR = 750 // keepAliveSeconds * 750 = milliseconds at 75%
    }
}

/**
 * Exception thrown when an MQTT connection fails.
 *
 * @property reasonCode The MQTT 5.0 reason code from the broker.
 */
internal class MqttConnectionException(
    val reasonCode: ReasonCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
