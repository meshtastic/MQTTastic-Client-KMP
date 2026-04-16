@file:Suppress("MagicNumber")

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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.meshtastic.mqtt.QoS

/** A received message formatted for display. */
data class DisplayMessage(
    val topic: String,
    val payload: String,
    val qos: QoS,
    val retained: Boolean,
)

/** UI state for the MQTTtastic sample app. */
data class MqttSampleState(
    val brokerUri: String = "tcp://localhost:1883",
    val clientId: String = "mqtttastic-sample",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    // subscribe
    val subscribeTopic: String = "test/#",
    val subscribeQos: QoS = QoS.AT_MOST_ONCE,
    val activeSubscriptions: Set<String> = emptySet(),
    // publish
    val publishTopic: String = "test/hello",
    val publishMessage: String = "Hello from MQTTtastic!",
    val publishQos: QoS = QoS.AT_MOST_ONCE,
    val publishRetain: Boolean = false,
    // messages
    val receivedMessages: List<DisplayMessage> = emptyList(),
    // error
    val error: String? = null,
)

/** Simple state-holder driving the sample Compose UI. */
class MqttSampleViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(MqttSampleState())
    val state: StateFlow<MqttSampleState> = _state.asStateFlow()

    private var client: MqttClient? = null
    private var connectionStateJob: Job? = null
    private var messagesJob: Job? = null

    // -- Text-field / toggle updaters --

    fun updateBrokerUri(value: String) {
        _state.update { it.copy(brokerUri = value) }
    }

    fun updateClientId(value: String) {
        _state.update { it.copy(clientId = value) }
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
        _state.update { it.copy(receivedMessages = emptyList()) }
    }

    // -- MQTT operations --

    fun connect() {
        val s = _state.value
        scope.launch {
            try {
                val endpoint = MqttEndpoint.parse(s.brokerUri)
                val newClient = MqttClient(s.clientId) {
                    logger = MqttLogger.println()
                    logLevel = MqttLogLevel.DEBUG
                    autoReconnect = true
                }
                client = newClient

                // Observe connection state
                connectionStateJob?.cancel()
                connectionStateJob = scope.launch {
                    newClient.connectionState.collect { connState ->
                        _state.update { it.copy(connectionState = connState) }
                    }
                }

                // Observe incoming messages
                messagesJob?.cancel()
                messagesJob = scope.launch {
                    newClient.messages.collect { msg ->
                        val display = DisplayMessage(
                            topic = msg.topic,
                            payload = msg.payloadAsString(),
                            qos = msg.qos,
                            retained = msg.retain,
                        )
                        _state.update { current ->
                            val updated = listOf(display) + current.receivedMessages
                            current.copy(receivedMessages = updated.take(50))
                        }
                    }
                }

                newClient.connect(endpoint)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Connection failed") }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                connectionStateJob?.cancel()
                messagesJob?.cancel()
                client?.disconnect()
                client?.close()
                client = null
                _state.update {
                    it.copy(
                        connectionState = ConnectionState.DISCONNECTED,
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
        scope.launch {
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
        scope.launch {
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
        scope.launch {
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

    fun dispose() {
        scope.launch {
            try {
                client?.close()
            } catch (_: Exception) {
                // Best-effort cleanup
            }
            client = null
        }
        scope.cancel()
    }
}
