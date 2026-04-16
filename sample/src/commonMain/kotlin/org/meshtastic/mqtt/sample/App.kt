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
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package org.meshtastic.mqtt.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowWidthSizeClass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.meshtastic.mqtt.ConnectionState
import org.meshtastic.mqtt.QoS

// -- Theme: Material3 Expressive with Meshtastic-inspired palette --

@Suppress("MagicNumber")
private val MeshGreen = Color(0xFF67EA94)

@Suppress("MagicNumber")
private val MeshGreenDark = Color(0xFF2E7D4A)

@Suppress("MagicNumber")
private val MeshWarning = Color(0xFFFFB74D)

@Suppress("MagicNumber")
private val MeshDarkColors = darkColorScheme(
    primary = MeshGreen,
    onPrimary = Color(0xFF003919),
    primaryContainer = MeshGreenDark,
    onPrimaryContainer = MeshGreen,
    secondary = Color(0xFF86D5A0),
    secondaryContainer = Color(0xFF2E4F3A),
    onSecondaryContainer = Color(0xFFA8F0C0),
    tertiary = Color(0xFF7DD0E0),
    tertiaryContainer = Color(0xFF1E4D58),
    onTertiaryContainer = Color(0xFFB8E8F4),
    background = Color(0xFF111315),
    surface = Color(0xFF1A1C1E),
    surfaceContainerLowest = Color(0xFF0E1011),
    surfaceContainerLow = Color(0xFF171919),
    surfaceContainer = Color(0xFF212325),
    surfaceContainerHigh = Color(0xFF2C2E30),
    surfaceContainerHighest = Color(0xFF363839),
    onSurface = Color(0xFFE2E2E5),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF454749),
    outlineVariant = Color(0xFF353739),
)

// -- App entry --

@Composable
fun App() {
    // Canonical MAD ViewModel ownership: the viewModel { } composable scopes
    // MqttSampleViewModel to the nearest ViewModelStoreOwner and auto-clears it
    // (triggering onCleared → viewModelScope cancellation) on disposal.
    val viewModel: MqttSampleViewModel = viewModel { MqttSampleViewModel() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val adaptiveInfo = currentWindowAdaptiveInfo()

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkModeOrFallback()) MeshDarkColors else expressiveLightColorScheme(),
    ) {
        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = { MqttTopBar(state.connectionState, scrollBehavior) },
            snackbarHost = { SnackbarHost(snackbarHostState) { SnackbarCard(it.visuals.message) } },
            contentWindowInsets = WindowInsets.navigationBars,
        ) { inner ->
            AdaptiveLayout(state, viewModel, adaptiveInfo, Modifier.fillMaxSize().padding(inner))
        }
    }
}

@Composable private fun isSystemInDarkModeOrFallback(): Boolean = true

// -- Top bar --

@Composable
private fun MqttTopBar(
    connectionState: ConnectionState,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MeshGreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("M", color = Color(0xFF003919), fontWeight = FontWeight.Bold)
                }
                Text(
                    "MQTTtastic",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        actions = {
            ConnectionChip(connectionState)
            Spacer(Modifier.width(8.dp))
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun SnackbarCard(message: String) {
    Snackbar(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) { Text(message) }
}

// -- Adaptive layout: two-pane on Expanded, stacked otherwise --

@Composable
private fun AdaptiveLayout(
    state: MqttSampleState,
    viewModel: MqttSampleViewModel,
    adaptiveInfo: WindowAdaptiveInfo,
    modifier: Modifier = Modifier,
) {
    val isExpanded = adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED
    if (isExpanded) {
        Row(
            modifier = modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ControlsColumn(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .widthIn(min = 360.dp, max = 440.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            )
            MessagesPane(state, viewModel, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ControlsColumn(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
            )
            MessagesPane(
                state,
                viewModel,
                Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 560.dp),
            )
        }
    }
}

@Composable
private fun ControlsColumn(
    state: MqttSampleState,
    viewModel: MqttSampleViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConnectionCard(state, viewModel)
        SubscribeCard(state, viewModel)
        PublishCard(state, viewModel)
    }
}

// -- Cards --

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                trailing?.invoke()
            }
            content()
        }
    }
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun ConnectionCard(state: MqttSampleState, viewModel: MqttSampleViewModel) {
    val connected = state.connectionState == ConnectionState.CONNECTED
    val connecting = state.connectionState == ConnectionState.CONNECTING ||
        state.connectionState == ConnectionState.RECONNECTING
    SectionCard(title = "Connection") {
        OutlinedTextField(
            value = state.brokerUri,
            onValueChange = viewModel::updateBrokerUri,
            label = { Text("Broker URI") },
            singleLine = true,
            enabled = !connected && !connecting,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        OutlinedTextField(
            value = state.clientId,
            onValueChange = viewModel::updateClientId,
            label = { Text("Client ID") },
            singleLine = true,
            enabled = !connected && !connecting,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::updateUsername,
                label = { Text("User") },
                singleLine = true,
                enabled = !connected && !connecting,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("Password") },
                singleLine = true,
                enabled = !connected && !connecting,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            )
        }
        Button(
            onClick = { if (connected) viewModel.disconnect() else viewModel.connect() },
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialShapes.Pill.toShape(),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            if (connecting) {
                LoadingIndicator(modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                when (state.connectionState) {
                    ConnectionState.CONNECTED -> "Disconnect"
                    ConnectionState.CONNECTING -> "Connecting…"
                    ConnectionState.RECONNECTING -> "Reconnecting…"
                    ConnectionState.DISCONNECTED -> "Connect"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SubscribeCard(state: MqttSampleState, viewModel: MqttSampleViewModel) {
    SectionCard(
        title = "Subscribe",
        trailing = {
            if (state.activeSubscriptions.isNotEmpty()) {
                Badge("${state.activeSubscriptions.size} active", MeshGreen)
            }
        },
    ) {
        OutlinedTextField(
            value = state.subscribeTopic,
            onValueChange = viewModel::updateSubscribeTopic,
            label = { Text("Topic Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        QosChipRow(state.subscribeQos, viewModel::updateSubscribeQos)
        FilledTonalButton(
            onClick = viewModel::subscribe,
            enabled = state.connectionState == ConnectionState.CONNECTED,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) { Text("Subscribe", fontWeight = FontWeight.SemiBold) }
        if (state.activeSubscriptions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.activeSubscriptions.forEach { topic ->
                    ListItem(
                        headlineContent = {
                            Text(topic, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            TextButton(onClick = { viewModel.unsubscribe(topic) }) { Text("Remove") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun PublishCard(state: MqttSampleState, viewModel: MqttSampleViewModel) {
    SectionCard(title = "Publish") {
        OutlinedTextField(
            value = state.publishTopic,
            onValueChange = viewModel::updatePublishTopic,
            label = { Text("Topic") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        OutlinedTextField(
            value = state.publishMessage,
            onValueChange = viewModel::updatePublishMessage,
            label = { Text("Payload") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            QosChipRow(state.publishQos, viewModel::updatePublishQos, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.publishRetain, onCheckedChange = viewModel::updatePublishRetain)
                Text("Retain", style = MaterialTheme.typography.labelMedium)
            }
        }
        FilledTonalButton(
            onClick = viewModel::publish,
            enabled = state.connectionState == ConnectionState.CONNECTED,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) { Text("Send", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun QosChipRow(
    selected: QoS,
    onSelect: (QoS) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Expressive: true ButtonGroup — tight connected row with shape
    // morphing on press. Delegates interaction to toggleableItem.
    ButtonGroup(modifier = modifier) {
        QoS.entries.forEach { q ->
            toggleableItem(
                checked = selected == q,
                label = "QoS ${q.ordinal}",
                onCheckedChange = { if (it) onSelect(q) },
                weight = 1f,
            )
        }
    }
}

// -- Messages pane: Jetchat-style reverseLayout feed --

@Composable
private fun MessagesPane(
    state: MqttSampleState,
    viewModel: MqttSampleViewModel,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxSize()) {
            MessagesHeader(
                total = state.totalMessageCount,
                showRaw = state.showRawPayload,
                onToggleRaw = viewModel::toggleRawPayload,
                onClear = viewModel::clearMessages,
                canClear = state.receivedMessages.isNotEmpty(),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (state.receivedMessages.isEmpty()) {
                EmptyMessages(
                    connecting = state.connectionState == ConnectionState.CONNECTING ||
                        state.connectionState == ConnectionState.RECONNECTING,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MessagesList(
                    messages = state.receivedMessages,
                    showRaw = state.showRawPayload,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MessagesHeader(
    total: Int,
    showRaw: Boolean,
    onToggleRaw: () -> Unit,
    onClear: () -> Unit,
    canClear: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Messages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (total > 0) Badge(total.toString(), MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonalToggleButton(
                checked = showRaw,
                onCheckedChange = { onToggleRaw() },
                shapes = ToggleButtonDefaults.shapes(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Raw (UTF-8)", style = MaterialTheme.typography.labelLarge)
            }
            AnimatedVisibility(canClear) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun EmptyMessages(connecting: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connecting) {
                ContainedLoadingIndicator(modifier = Modifier.size(56.dp))
                Text("Connecting to broker…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Messages will stream in as they arrive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(MaterialShapes.Flower.toShape())
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                )
                Text("No messages yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Connect and subscribe to start receiving",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Chat-style feed following the Material3/Jetchat pattern:
 * - Messages are stored chronologically (oldest → newest) in the ViewModel.
 * - `reverseLayout = true` means index 0 is rendered at the visual bottom,
 *   so the newest message is always at the bottom and `firstVisibleItemIndex == 0`
 *   corresponds to "the latest message is visible".
 * - When the user is anchored to latest, we animate-scroll on new arrivals.
 * - When they have scrolled up into history, we show an FAB to jump back.
 */
@Composable
private fun MessagesList(
    messages: List<DisplayMessage>,
    showRaw: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reversed = remember(messages) { messages.asReversed() }

    val atLatest by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }

    // Auto-scroll: when a new message arrives AND the user is already pinned to
    // the latest, keep them there. If they've scrolled into history, don't
    // yank the view away — the FAB lets them come back on their own.
    LaunchedEffect(Unit) {
        snapshotFlow { messages.lastOrNull()?.id }
            .drop(1)
            .distinctUntilChanged()
            .collect {
                if (atLatest) listState.animateScrollToItem(0)
            }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(items = reversed, key = { it.id }) { msg ->
                MessageRow(msg, showRaw)
            }
        }

        AnimatedVisibility(
            visible = !atLatest,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Text("↓ Jump to latest") }
        }
    }
}

// -- Message rows --

@Suppress("LongMethod")
@Composable
private fun MessageRow(msg: DisplayMessage, showRaw: Boolean) {
    val meshInfo = msg.meshtastic
    val accent = meshInfo?.let { portnumColor(it) } ?: MaterialTheme.colorScheme.outline

    when {
        showRaw -> RawMessageRow(msg)
        meshInfo != null -> MeshtasticRow(msg, meshInfo, accent)
        else -> GenericRow(msg, accent)
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

@Composable
private fun RawMessageRow(msg: DisplayMessage) {
    ListItem(
        overlineContent = {
            Text(msg.topic, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        headlineContent = {
            Text(
                msg.rawUtf8.ifEmpty { "(empty)" },
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(formatElapsed(msg.receivedAt)) },
        trailingContent = { QosRetainBadges(msg) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun MeshtasticRow(msg: DisplayMessage, info: MeshtasticInfo, accent: Color) {
    ListItem(
        leadingContent = { AccentBar(accent) },
        overlineContent = {
            Text(
                "${info.from} → ${info.to}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        },
        headlineContent = {
            val text = info.payloadText ?: buildString {
                append("via ${info.gatewayId}")
                if (info.hopLimit > 0) append(" · ${info.hopLimit} hops")
            }
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                buildString {
                    append(formatElapsed(msg.receivedAt))
                    append(" · ${info.gatewayId}")
                    if (info.rxRssi != 0) append(" · ${info.rxRssi} dBm")
                    if (info.rxSnr != 0f) append(" · ${info.rxSnr} SNR")
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (info.isEncrypted) Badge("ENC", MaterialTheme.colorScheme.outline)
                info.portnum?.let { Badge(it.replace("_APP", ""), accent) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun GenericRow(msg: DisplayMessage, accent: Color) {
    ListItem(
        leadingContent = { AccentBar(accent) },
        headlineContent = {
            Text(msg.topic, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(msg.payload, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    formatElapsed(msg.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { QosRetainBadges(msg) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AccentBar(color: Color) {
    Box(Modifier.width(4.dp).height(44.dp).clip(MaterialShapes.Pill.toShape()).background(color))
}

@Composable
private fun QosRetainBadges(msg: DisplayMessage) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Badge("QoS ${msg.qos.ordinal}")
        if (msg.retained) Badge("RET", MeshWarning)
    }
}

// -- Small UI primitives --

@Composable
private fun ConnectionChip(connectionState: ConnectionState) {
    val (label, color) = when (connectionState) {
        ConnectionState.CONNECTED -> "Connected" to MeshGreen
        ConnectionState.CONNECTING -> "Connecting" to MeshWarning
        ConnectionState.RECONNECTING -> "Reconnecting" to MeshWarning
        ConnectionState.DISCONNECTED -> "Offline" to MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = MaterialShapes.Pill.toShape(),
        color = color.copy(alpha = 0.16f),
        modifier = Modifier.semantics { contentDescription = "Connection: $label" },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color = LocalContentColor.current) {
    Surface(
        shape = MaterialShapes.Pill.toShape(),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// -- Helpers --

private fun formatElapsed(mark: kotlin.time.TimeMark): String {
    val elapsed = mark.elapsedNow()
    return when {
        elapsed.inWholeSeconds < 60 -> "${elapsed.inWholeSeconds}s ago"
        elapsed.inWholeMinutes < 60 -> "${elapsed.inWholeMinutes}m ago"
        elapsed.inWholeHours < 24 -> "${elapsed.inWholeHours}h ago"
        else -> "${elapsed.inWholeDays}d ago"
    }
}

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
