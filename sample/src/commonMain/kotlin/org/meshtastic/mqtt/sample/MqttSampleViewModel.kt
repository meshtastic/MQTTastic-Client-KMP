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
package org.meshtastic.mqtt.sample

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.meshtastic.mqtt.ConnectionState
import org.meshtastic.mqtt.MqttClient
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.MqttLogLevel
import org.meshtastic.mqtt.MqttLogger
import org.meshtastic.mqtt.MqttMessage
import org.meshtastic.mqtt.QoS
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ServiceEnvelope

/** A received message formatted for display. */
@Immutable
data class DisplayMessage(
    val id: Long,
    val topic: String,
    val payload: String,
    val rawUtf8: String,
    val receivedAt: kotlin.time.TimeMark,
    val qos: QoS,
    val retained: Boolean,
    /** Decoded Meshtastic metadata, if this was a ServiceEnvelope. */
    val meshtastic: MeshtasticInfo? = null,
)

/** Decoded metadata from a Meshtastic ServiceEnvelope. */
@Immutable
data class MeshtasticInfo(
    val gatewayId: String,
    val channelId: String,
    val from: String,
    val to: String,
    val packetId: String,
    val portnum: String?,
    val payloadText: String?,
    val hopLimit: Int,
    val rxSnr: Float,
    val rxRssi: Int,
    val isEncrypted: Boolean,
)

/** UI state for the MQTTastic sample app. */
@Immutable
data class MqttSampleState(
    val brokerUri: String = "tls://mqtt.meshtastic.org:8883",
    val clientId: String = "mqtttastic-sample",
    val username: String = "meshdev",
    val password: String = "large4cats",
    val connectionState: ConnectionState = ConnectionState.Disconnected.Idle,
    // subscribe
    val subscribeTopic: String = "msh/US/2/e/LongFast/#",
    val subscribeQos: QoS = QoS.AT_MOST_ONCE,
    val activeSubscriptions: Set<String> = emptySet(),
    // publish
    val publishTopic: String = "msh/US/2/e/LongFast/!mqtttastic",
    val publishMessage: String = "Hello from MQTTastic!",
    val publishQos: QoS = QoS.AT_MOST_ONCE,
    val publishRetain: Boolean = false,
    // messages
    val receivedMessages: List<DisplayMessage> = emptyList(),
    val totalMessageCount: Int = 0,
    val showRawPayload: Boolean = false,
    // error
    val error: String? = null,
)

/**
 * State-holder driving the sample Compose UI.
 *
 * Extends the multiplatform [ViewModel] so its [viewModelScope] is automatically
 * cancelled when the host lifecycle disposes it — the canonical Modern Android
 * Development pattern, now available on all KMP targets via
 * `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`.
 */
@Stable
class MqttSampleViewModel : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        _state.update { it.copy(error = e.message ?: e::class.simpleName ?: "Unknown error") }
    }
    private val _state = MutableStateFlow(MqttSampleState())
    val state: StateFlow<MqttSampleState> = _state.asStateFlow()

    private var client: MqttClient? = null
    private var connectionStateJob: Job? = null
    private var messagesJob: Job? = null
    private var nextMessageId = 0L

    companion object {
        private const val MAX_MESSAGES = 100
    }

    // -- Text-field / toggle updaters --

    fun updateBrokerUri(value: String) {
        _state.update { it.copy(brokerUri = value) }
    }

    fun updateClientId(value: String) {
        _state.update { it.copy(clientId = value) }
    }

    fun updateUsername(value: String) {
        _state.update { it.copy(username = value) }
    }

    fun updatePassword(value: String) {
        _state.update { it.copy(password = value) }
    }

    fun updateSubscribeTopic(value: String) {
        _state.update { it.copy(subscribeTopic = value) }
    }

    fun updateSubscribeQos(value: QoS) {
        _state.update { it.copy(subscribeQos = value) }
    }

    fun updatePublishTopic(value: String) {
        _state.update { it.copy(publishTopic = value) }
    }

    fun updatePublishMessage(value: String) {
        _state.update { it.copy(publishMessage = value) }
    }

    fun updatePublishQos(value: QoS) {
        _state.update { it.copy(publishQos = value) }
    }

    fun updatePublishRetain(value: Boolean) {
        _state.update { it.copy(publishRetain = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(receivedMessages = emptyList(), totalMessageCount = 0) }
    }

    fun toggleRawPayload() {
        _state.update { it.copy(showRawPayload = !it.showRawPayload) }
    }

    // -- MQTT operations --

    fun connect() {
        val s = _state.value
        if (s.connectionState !is ConnectionState.Disconnected) return
        viewModelScope.launch(exceptionHandler) {
            try {
                val endpoint = MqttEndpoint.parse(s.brokerUri)
                val newClient = MqttClient(s.clientId) {
                    username = s.username.ifBlank { null }
                    s.password.ifBlank { null }?.let { password(it) }
                    logger = MqttLogger.println()
                    logLevel = MqttLogLevel.DEBUG
                    autoReconnect = true
                }
                client = newClient

                // Observe connection state
                connectionStateJob?.cancel()
                connectionStateJob = viewModelScope.launch(exceptionHandler) {
                    newClient.connectionState.collect { connState ->
                        _state.update { it.copy(connectionState = connState) }
                    }
                }

                // Observe incoming messages
                messagesJob?.cancel()
                messagesJob = viewModelScope.launch(exceptionHandler) {
                    newClient.messages.collect { msg ->
                        val display = decodeMessage(msg)
                        _state.update { current ->
                            val updated = current.receivedMessages + display
                            current.copy(
                                receivedMessages = updated.takeLast(MAX_MESSAGES),
                                totalMessageCount = current.totalMessageCount + 1,
                            )
                        }
                    }
                }

                newClient.connect(endpoint)

                // Auto-subscribe to the default topic
                if (s.subscribeTopic.isNotBlank()) {
                    newClient.subscribe(s.subscribeTopic, s.subscribeQos)
                    _state.update {
                        it.copy(activeSubscriptions = it.activeSubscriptions + s.subscribeTopic)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Connection failed") }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch(exceptionHandler) {
            try {
                connectionStateJob?.cancel()
                messagesJob?.cancel()
                client?.disconnect()
                client?.close()
                client = null
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected.Idle,
                        activeSubscriptions = emptySet(),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Disconnect failed") }
            }
        }
    }

    fun subscribe() {
        val s = _state.value
        if (s.subscribeTopic.isBlank()) {
            _state.update { it.copy(error = "Topic filter cannot be empty") }
            return
        }
        viewModelScope.launch(exceptionHandler) {
            try {
                client?.subscribe(s.subscribeTopic, s.subscribeQos)
                _state.update {
                    it.copy(activeSubscriptions = it.activeSubscriptions + s.subscribeTopic)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Subscribe failed") }
            }
        }
    }

    fun unsubscribe(topic: String) {
        viewModelScope.launch(exceptionHandler) {
            try {
                client?.unsubscribe(topic)
                _state.update {
                    it.copy(activeSubscriptions = it.activeSubscriptions - topic)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Unsubscribe failed") }
            }
        }
    }

    fun publish() {
        val s = _state.value
        if (s.publishTopic.isBlank()) {
            _state.update { it.copy(error = "Topic cannot be empty") }
            return
        }
        viewModelScope.launch(exceptionHandler) {
            try {
                client?.publish(
                    topic = s.publishTopic,
                    payload = s.publishMessage,
                    qos = s.publishQos,
                    retain = s.publishRetain,
                )
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Publish failed") }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decodeMessage(msg: MqttMessage): DisplayMessage {
        val rawUtf8 = msg.payloadAsString()
        val meshtastic = try {
            decodeMeshtastic(msg)
        } catch (_: Exception) {
            null
        }
        return DisplayMessage(
            id = nextMessageId++,
            topic = msg.topic,
            payload = meshtastic?.let { formatMeshtastic(it) } ?: rawUtf8,
            rawUtf8 = rawUtf8,
            receivedAt = kotlin.time.TimeSource.Monotonic.markNow(),
            qos = msg.qos,
            retained = msg.retain,
            meshtastic = meshtastic,
        )
    }

    private fun decodeMeshtastic(msg: MqttMessage): MeshtasticInfo? {
        val bytes = msg.payload.toByteArray()
        if (bytes.isEmpty()) return null

        val envelope = ServiceEnvelope.ADAPTER.decode(bytes)
        val packet = envelope.packet ?: return null

        val isEncrypted = packet.encrypted != null && packet.encrypted.size > 0
        val decoded = packet.decoded

        val portnum = decoded?.portnum?.takeIf { it != PortNum.UNKNOWN_APP }
        val payloadText = if (portnum == PortNum.TEXT_MESSAGE_APP && decoded != null) {
            decoded.payload.utf8()
        } else {
            null
        }

        return MeshtasticInfo(
            gatewayId = envelope.gateway_id,
            channelId = envelope.channel_id,
            from = "!${formatNodeId(packet.from)}",
            to = "!${formatNodeId(packet.to)}",
            packetId = "0x${packet.id.toUInt().toString(radix = 16)}",
            portnum = portnum?.name,
            payloadText = payloadText,
            hopLimit = packet.hop_limit,
            rxSnr = packet.rx_snr,
            rxRssi = packet.rx_rssi,
            isEncrypted = isEncrypted,
        )
    }

    private fun formatNodeId(nodeNum: Int): String =
        nodeNum.toUInt().toString(radix = 16).padStart(length = 8, padChar = '0')

    private fun formatMeshtastic(info: MeshtasticInfo): String = buildString {
        append("${info.from} → ${info.to}")
        if (info.isEncrypted) {
            append(" [encrypted]")
        } else if (info.portnum != null) {
            append(" [${info.portnum}]")
            if (info.payloadText != null) {
                append(": ${info.payloadText}")
            }
        }
        append(" via ${info.gatewayId}")
    }

    override fun onCleared() {
        connectionStateJob?.cancel()
        messagesJob?.cancel()
        // viewModelScope is cancelled automatically; this cascades into the
        // client's internal coroutines and closes the underlying transport.
        client = null
        super.onCleared()
    }
}
