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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.meshtastic.mqtt.ConnectionState
import org.meshtastic.mqtt.QoS

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App() {
    val viewModel = remember { MqttSampleViewModel() }
    DisposableEffect(Unit) { onDispose { viewModel.dispose() } }
    val state by viewModel.state.collectAsState()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // -- Error Banner --
                state.error?.let { errorMessage ->
                    ErrorBanner(
                        message = errorMessage,
                        onDismiss = { viewModel.dismissError() },
                    )
                }

                // -- Connection Card --
                ConnectionCard(
                    brokerHost = state.brokerHost,
                    brokerPort = state.brokerPort,
                    useTls = state.useTls,
                    clientId = state.clientId,
                    username = state.username,
                    password = state.password,
                    connectionState = state.connectionState,
                    onBrokerHostChange = viewModel::updateBrokerHost,
                    onBrokerPortChange = viewModel::updateBrokerPort,
                    onUseTlsChange = viewModel::updateUseTls,
                    onClientIdChange = viewModel::updateClientId,
                    onUsernameChange = viewModel::updateUsername,
                    onPasswordChange = viewModel::updatePassword,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                )

                // -- Publish Card --
                PublishCard(
                    topic = state.publishTopic,
                    message = state.publishMessage,
                    qos = state.publishQos,
                    retain = state.publishRetain,
                    enabled = state.connectionState == ConnectionState.CONNECTED,
                    onTopicChange = viewModel::updatePublishTopic,
                    onMessageChange = viewModel::updatePublishMessage,
                    onQosChange = viewModel::updatePublishQos,
                    onRetainChange = viewModel::updatePublishRetain,
                    onPublish = viewModel::publish,
                )

                // -- Subscribe Card --
                SubscribeCard(
                    topicFilter = state.subscribeTopic,
                    qos = state.subscribeQos,
                    activeSubscriptions = state.activeSubscriptions,
                    enabled = state.connectionState == ConnectionState.CONNECTED,
                    onTopicFilterChange = viewModel::updateSubscribeTopic,
                    onQosChange = viewModel::updateSubscribeQos,
                    onSubscribe = viewModel::subscribe,
                    onUnsubscribe = viewModel::unsubscribe,
                )

                // -- Messages Card --
                MessagesCard(
                    messages = state.receivedMessages,
                    onClear = viewModel::clearMessages,
                )
            }
        }
    }
}

// -- Composable sections --

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    brokerHost: String,
    brokerPort: String,
    useTls: Boolean,
    clientId: String,
    username: String,
    password: String,
    connectionState: ConnectionState,
    onBrokerHostChange: (String) -> Unit,
    onBrokerPortChange: (String) -> Unit,
    onUseTlsChange: (Boolean) -> Unit,
    onClientIdChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = brokerHost,
                    onValueChange = onBrokerHostChange,
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = brokerPort,
                    onValueChange = onBrokerPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(80.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = clientId,
                onValueChange = onClientIdChange,
                label = { Text("Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = useTls, onCheckedChange = onUseTlsChange)
                Text("TLS", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(connectionState.indicatorColor()),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = connectionState.displayText(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val isConnected = connectionState == ConnectionState.CONNECTED
                val isConnecting = connectionState == ConnectionState.CONNECTING ||
                    connectionState == ConnectionState.RECONNECTING
                Button(
                    onClick = if (isConnected) onDisconnect else onConnect,
                    enabled = !isConnecting,
                ) {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublishCard(
    topic: String,
    message: String,
    qos: QoS,
    retain: Boolean,
    enabled: Boolean,
    onTopicChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onQosChange: (QoS) -> Unit,
    onRetainChange: (Boolean) -> Unit,
    onPublish: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Publish",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = topic,
                onValueChange = onTopicChange,
                label = { Text("Topic") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                label = { Text("Message") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QoS.entries.forEach { q ->
                        FilterChip(
                            selected = qos == q,
                            onClick = { onQosChange(q) },
                            label = { Text("QoS ${q.ordinal}") },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = retain, onCheckedChange = onRetainChange)
                    Text("Retain", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onPublish,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Publish")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscribeCard(
    topicFilter: String,
    qos: QoS,
    activeSubscriptions: Set<String>,
    enabled: Boolean,
    onTopicFilterChange: (String) -> Unit,
    onQosChange: (QoS) -> Unit,
    onSubscribe: () -> Unit,
    onUnsubscribe: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Subscribe",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = topicFilter,
                onValueChange = onTopicFilterChange,
                label = { Text("Topic Filter") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QoS.entries.forEach { q ->
                        FilterChip(
                            selected = qos == q,
                            onClick = { onQosChange(q) },
                            label = { Text("QoS ${q.ordinal}") },
                        )
                    }
                }
                Button(onClick = onSubscribe, enabled = enabled) {
                    Text("Subscribe")
                }
            }

            if (activeSubscriptions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Active Subscriptions",
                    style = MaterialTheme.typography.labelMedium,
                )
                activeSubscriptions.forEach { topic ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = topic,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onUnsubscribe(topic) }) {
                            Text("✕", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesCard(
    messages: List<DisplayMessage>,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Messages (${messages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }

            if (messages.isEmpty()) {
                Text(
                    text = "No messages received yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(messages) { msg ->
                        MessageRow(msg)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: DisplayMessage) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = msg.topic,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(text = "QoS ${msg.qos.ordinal}")
                if (msg.retained) {
                    Badge(text = "RET", color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        Text(
            text = msg.payload,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Badge(
    text: String,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

// -- Helpers --

private fun ConnectionState.indicatorColor(): Color =
    when (this) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING -> Color(0xFFFFC107)
        ConnectionState.RECONNECTING -> Color(0xFFFF9800)
        ConnectionState.DISCONNECTED -> Color(0xFFF44336)
    }

private fun ConnectionState.displayText(): String =
    when (this) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.RECONNECTING -> "Reconnecting…"
        ConnectionState.DISCONNECTED -> "Disconnected"
    }
