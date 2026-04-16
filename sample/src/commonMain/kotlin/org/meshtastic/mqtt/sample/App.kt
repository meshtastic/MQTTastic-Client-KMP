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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.meshtastic.mqtt.ConnectionState
import org.meshtastic.mqtt.QoS

// -- Meshtastic-inspired dark theme --

@Suppress("MagicNumber")
private val MeshGreen = Color(0xFF67EA94)

@Suppress("MagicNumber")
private val MeshGreenDark = Color(0xFF2E7D4A)

@Suppress("MagicNumber")
private val MeshSurface = Color(0xFF1A1C1E)

@Suppress("MagicNumber")
private val MeshSurfaceContainer = Color(0xFF212325)

@Suppress("MagicNumber")
private val MeshSurfaceContainerHigh = Color(0xFF2C2E30)

@Suppress("MagicNumber")
private val MeshOnSurface = Color(0xFFE2E2E5)

@Suppress("MagicNumber")
private val MeshOnSurfaceVariant = Color(0xFF9CA3AF)

@Suppress("MagicNumber")
private val MeshError = Color(0xFFFF6B6B)

@Suppress("MagicNumber")
private val MeshWarning = Color(0xFFFFB74D)

@Suppress("MagicNumber")
private val MeshDarkColorScheme = darkColorScheme(
    primary = MeshGreen,
    onPrimary = Color(0xFF003919),
    primaryContainer = MeshGreenDark,
    onPrimaryContainer = MeshGreen,
    secondary = Color(0xFF86D5A0),
    onSecondary = Color(0xFF003919),
    secondaryContainer = Color(0xFF2E4F3A),
    onSecondaryContainer = Color(0xFFA8F0C0),
    tertiary = Color(0xFF7DD0E0),
    onTertiary = Color(0xFF003640),
    tertiaryContainer = Color(0xFF1E4D58),
    onTertiaryContainer = Color(0xFFB8E8F4),
    error = MeshError,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4D1515),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111315),
    onBackground = MeshOnSurface,
    surface = MeshSurface,
    onSurface = MeshOnSurface,
    surfaceVariant = MeshSurfaceContainer,
    onSurfaceVariant = MeshOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0E1011),
    surfaceContainerLow = Color(0xFF171919),
    surfaceContainer = MeshSurfaceContainer,
    surfaceContainerHigh = MeshSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF363839),
    outline = Color(0xFF454749),
    outlineVariant = Color(0xFF353739),
)

private val TwoPaneBreakpoint = 960.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val viewModel = remember { MqttSampleViewModel() }
    DisposableEffect(Unit) { onDispose { viewModel.dispose() } }
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    MaterialTheme(colorScheme = MeshDarkColorScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⛰", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "MQTTtastic",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    },
                    actions = { ConnectionStatusChip(state.connectionState) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                if (maxWidth >= TwoPaneBreakpoint) {
                    TwoPaneLayout(state, viewModel)
                } else {
                    SinglePaneLayout(state, viewModel)
                }
            }
        }
    }
}

// -- Adaptive Layouts --

@Composable
private fun TwoPaneLayout(state: MqttSampleState, viewModel: MqttSampleViewModel) {
    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ControlsContent(state, viewModel)
        }
        MessagesFeed(
            messages = state.receivedMessages,
            totalCount = state.totalMessageCount,
            showRawPayload = state.showRawPayload,
            onToggleRaw = viewModel::toggleRawPayload,
            onClear = viewModel::clearMessages,
            modifier = Modifier.weight(0.6f).fillMaxHeight(),
        )
    }
}

@Composable
private fun SinglePaneLayout(state: MqttSampleState, viewModel: MqttSampleViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item { ControlsContent(state, viewModel) }
        item {
            MessagesFeed(
                messages = state.receivedMessages,
                totalCount = state.totalMessageCount,
                showRawPayload = state.showRawPayload,
                onToggleRaw = viewModel::toggleRawPayload,
                onClear = viewModel::clearMessages,
                modifier = Modifier.fillMaxWidth().height(500.dp),
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// -- Controls --

@Composable
private fun ControlsContent(state: MqttSampleState, viewModel: MqttSampleViewModel) {
    ConnectionSection(
        brokerUri = state.brokerUri,
        clientId = state.clientId,
        username = state.username,
        password = state.password,
        connectionState = state.connectionState,
        onBrokerUriChange = viewModel::updateBrokerUri,
        onClientIdChange = viewModel::updateClientId,
        onUsernameChange = viewModel::updateUsername,
        onPasswordChange = viewModel::updatePassword,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
    )

    SubscribeSection(
        topicFilter = state.subscribeTopic,
        qos = state.subscribeQos,
        activeSubscriptions = state.activeSubscriptions,
        enabled = state.connectionState == ConnectionState.CONNECTED,
        onTopicFilterChange = viewModel::updateSubscribeTopic,
        onQosChange = viewModel::updateSubscribeQos,
        onSubscribe = viewModel::subscribe,
        onUnsubscribe = viewModel::unsubscribe,
    )

    PublishSection(
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
}

// -- Connection Status Chip --

@Composable
private fun ConnectionStatusChip(connectionState: ConnectionState) {
    val (color, label) = when (connectionState) {
        ConnectionState.CONNECTED -> MeshGreen to "Connected"
        ConnectionState.CONNECTING -> MeshWarning to "Connecting…"
        ConnectionState.RECONNECTING -> MeshWarning to "Reconnecting…"
        ConnectionState.DISCONNECTED -> MeshError to "Disconnected"
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape).background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// -- Section Card wrapper --

@Composable
private fun SectionCard(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// -- Connection Section --

@Composable
private fun ConnectionSection(
    brokerUri: String,
    clientId: String,
    username: String,
    password: String,
    connectionState: ConnectionState,
    onBrokerUriChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val currentConnectionState by rememberUpdatedState(connectionState)
    val isConnected by remember {
        derivedStateOf { currentConnectionState == ConnectionState.CONNECTED }
    }
    val isConnecting by remember {
        derivedStateOf {
            currentConnectionState == ConnectionState.CONNECTING ||
                currentConnectionState == ConnectionState.RECONNECTING
        }
    }

    SectionCard(title = "Connection") {
        OutlinedTextField(
            value = brokerUri,
            onValueChange = onBrokerUriChange,
            label = { Text("Broker URI") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            enabled = !isConnected && !isConnecting,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = clientId,
            onValueChange = onClientIdChange,
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            enabled = !isConnected && !isConnecting,
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
                shape = MaterialTheme.shapes.medium,
                enabled = !isConnected && !isConnecting,
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                enabled = !isConnected && !isConnecting,
            )
        }
        Spacer(Modifier.height(12.dp))
        @Suppress("MagicNumber")
        AnimatedContent(
            targetState = isConnected,
            label = "connect-button",
        ) { connected ->
            if (connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MeshGreen,
                        contentColor = Color(0xFF003919),
                    ),
                ) {
                    Text(
                        if (isConnecting) "Connecting…" else "Connect",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// -- Subscribe Section --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscribeSection(
    topicFilter: String,
    qos: QoS,
    activeSubscriptions: Set<String>,
    enabled: Boolean,
    onTopicFilterChange: (String) -> Unit,
    onQosChange: (QoS) -> Unit,
    onSubscribe: () -> Unit,
    onUnsubscribe: (String) -> Unit,
) {
    SectionCard(
        title = "Subscribe",
        trailing = {
            if (activeSubscriptions.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MeshGreen.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = "${activeSubscriptions.size} active",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MeshGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        OutlinedTextField(
            value = topicFilter,
            onValueChange = onTopicFilterChange,
            label = { Text("Topic Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QoS.entries.forEach { q ->
                FilterChip(
                    selected = qos == q,
                    onClick = { onQosChange(q) },
                    label = { Text("QoS ${q.ordinal}") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onSubscribe,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("Subscribe", fontWeight = FontWeight.SemiBold)
        }

        AnimatedVisibility(visible = activeSubscriptions.isNotEmpty()) {
            Column {
                Spacer(Modifier.height(8.dp))
                activeSubscriptions.forEach { topic ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = topic,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { onUnsubscribe(topic) },
                                modifier = Modifier
                                    .size(28.dp)
                                    .semantics {
                                        contentDescription = "Unsubscribe from $topic"
                                    },
                            ) {
                                Text(
                                    "✕",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(MaterialTheme.shapes.small),
                    )
                }
            }
        }
    }
}

// -- Publish Section --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublishSection(
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
    SectionCard(title = "Publish") {
        OutlinedTextField(
            value = topic,
            onValueChange = onTopicChange,
            label = { Text("Topic") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = message,
            onValueChange = onMessageChange,
            label = { Text("Payload") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Text(
                    "Retain",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onPublish,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("Send", fontWeight = FontWeight.SemiBold)
        }
    }
}

// -- Messages Feed --

@Composable
private fun MessagesFeed(
    messages: List<DisplayMessage>,
    totalCount: Int,
    showRawPayload: Boolean,
    onToggleRaw: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        val scope = rememberCoroutineScope()
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Messages",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (totalCount > 0) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = "$totalCount",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = showRawPayload,
                        onClick = onToggleRaw,
                        label = {
                            Text("Raw (UTF-8)", style = MaterialTheme.typography.labelSmall)
                        },
                    )
                    if (messages.isNotEmpty()) {
                        TextButton(onClick = onClear) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )

            AnimatedContent(
                targetState = messages.isEmpty(),
                label = "messages-content",
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📡", fontSize = 32.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No messages yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Connect and subscribe to start receiving",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.6f),
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        // Jetchat pattern: newest-first list + reverseLayout renders the
                        // newest at the visual bottom. We always animate to item 0 on new
                        // messages; the user can scroll up into history freely because the
                        // "Jump to latest" button returns them.
                        val userScrolledAway by remember {
                            derivedStateOf { listState.firstVisibleItemIndex > 0 }
                        }

                        LaunchedEffect(messages.size) {
                            if (!userScrolledAway) listState.animateScrollToItem(0)
                        }

                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(
                                items = messages,
                                key = { it.id },
                            ) { msg ->
                                MessageRow(msg, showRawPayload)
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = userScrolledAway,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Text(
                                    "↓ Jump to latest",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -- Message Row --

@Composable
private fun MessageRow(msg: DisplayMessage, showRawPayload: Boolean) {
    val meshInfo = msg.meshtastic
    val accentColor = meshInfo?.let { portnumColor(it) }
        ?: MaterialTheme.colorScheme.outline

    if (showRawPayload) {
        ListItem(
            overlineContent = {
                Text(
                    text = msg.topic,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            headlineContent = {
                Text(
                    text = msg.rawUtf8.ifEmpty { "(empty)" },
                    fontFamily = FontFamily.Monospace,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(text = formatElapsed(msg.receivedAt))
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MeshBadge("QoS ${msg.qos.ordinal}")
                    if (msg.retained) MeshBadge("RET", MeshWarning)
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
            ),
        )
    } else if (meshInfo != null) {
        ListItem(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accentColor),
                )
            },
            overlineContent = {
                Text(
                    text = "${meshInfo.from} → ${meshInfo.to}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            },
            headlineContent = {
                if (meshInfo.payloadText != null) {
                    Text(
                        text = meshInfo.payloadText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = buildString {
                            append("via ${meshInfo.gatewayId}")
                            if (meshInfo.hopLimit > 0) append(" · ${meshInfo.hopLimit} hops")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            supportingContent = {
                Text(
                    text = buildString {
                        append(formatElapsed(msg.receivedAt))
                        append(" · via ${meshInfo.gatewayId}")
                        if (meshInfo.hopLimit > 0) append(" · ${meshInfo.hopLimit} hops")
                        if (meshInfo.rxRssi != 0) append(" · ${meshInfo.rxRssi} dBm")
                        if (meshInfo.rxSnr != 0f) append(" · ${meshInfo.rxSnr} SNR")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (meshInfo.isEncrypted) {
                        MeshBadge("ENC", MaterialTheme.colorScheme.outline)
                    }
                    meshInfo.portnum?.let { port ->
                        MeshBadge(port.replace("_APP", ""), accentColor)
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
            ),
        )
    } else {
        ListItem(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accentColor),
                )
            },
            headlineContent = {
                Text(
                    text = msg.topic,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = msg.payload,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = formatElapsed(msg.receivedAt))
                }
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MeshBadge("QoS ${msg.qos.ordinal}")
                    if (msg.retained) MeshBadge("RET", MeshWarning)
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
            ),
        )
    }

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}

// -- Badge --

private fun formatElapsed(mark: kotlin.time.TimeMark): String {
    val elapsed = mark.elapsedNow()
    return when {
        elapsed.inWholeSeconds < 60 -> "${elapsed.inWholeSeconds}s ago"
        elapsed.inWholeMinutes < 60 -> "${elapsed.inWholeMinutes}m ago"
        elapsed.inWholeHours < 24 -> "${elapsed.inWholeHours}h ago"
        else -> "${elapsed.inWholeDays}d ago"
    }
}

@Composable
private fun MeshBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
        )
    }
}

// -- Helpers --

// Portnum-specific accent colors for Meshtastic message types
private object PortnumColors {
    @Suppress("MagicNumber") val ENCRYPTED = Color(0xFF6B7280)
    @Suppress("MagicNumber") val TEXT_MESSAGE = Color(0xFF60A5FA)
    @Suppress("MagicNumber") val POSITION = Color(0xFF34D399)
    @Suppress("MagicNumber") val NODEINFO = Color(0xFFA78BFA)
    @Suppress("MagicNumber") val TELEMETRY = Color(0xFFFBBF24)
    @Suppress("MagicNumber") val ROUTING = Color(0xFF9CA3AF)
    @Suppress("MagicNumber") val ADMIN = Color(0xFFF87171)
    @Suppress("MagicNumber") val TRACEROUTE = Color(0xFF2DD4BF)
    @Suppress("MagicNumber") val NEIGHBORINFO = Color(0xFFC084FC)
    @Suppress("MagicNumber") val MAP_REPORT = Color(0xFF4ADE80)
    @Suppress("MagicNumber") val STORE_FORWARD = Color(0xFFFB923C)
    @Suppress("MagicNumber") val DEFAULT = Color(0xFF9CA3AF)
}

private fun portnumColor(info: MeshtasticInfo): Color {
    if (info.isEncrypted) return PortnumColors.ENCRYPTED
    return when (info.portnum) {
        "TEXT_MESSAGE" -> PortnumColors.TEXT_MESSAGE
        "POSITION" -> PortnumColors.POSITION
        "NODEINFO" -> PortnumColors.NODEINFO
        "TELEMETRY" -> PortnumColors.TELEMETRY
        "ROUTING" -> PortnumColors.ROUTING
        "ADMIN" -> PortnumColors.ADMIN
        "TRACEROUTE" -> PortnumColors.TRACEROUTE
        "NEIGHBORINFO" -> PortnumColors.NEIGHBORINFO
        "MAP_REPORT" -> PortnumColors.MAP_REPORT
        "STORE_FORWARD" -> PortnumColors.STORE_FORWARD
        else -> PortnumColors.DEFAULT
    }
}
