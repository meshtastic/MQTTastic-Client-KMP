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
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.Url
import io.ktor.network.tls.TLSConfigBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.MqttTransport
import org.meshtastic.mqtt.MqttTransportFactory

/**
 * WebSocket-based [MqttTransport] for all platforms, including the browser (wasmJs).
 *
 * Uses Ktor's multiplatform HttpClient with a per-platform engine (CIO on JVM/Android/Apple/Linux,
 * WinHttp on Windows, Js on wasmJs/browser). One binary WebSocket frame = one complete MQTT packet,
 * so no stream parsing is needed — the WebSocket layer handles framing.
 *
 * @param configureTls optional hook applied to the engine's TLS configuration before platform
 *   trust is configured. Use it to trust a private or self-signed CA for this connection only.
 *   Honoured on JVM, Android, Apple, and Linux; ignored on Windows and in the browser.
 */
public class WebSocketTransport(
    internal val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransport {
    public constructor() : this(null)

    private var client: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private val sendMutex = Mutex()

    override val isConnected: Boolean
        get() = session?.isActive == true

    override suspend fun connect(endpoint: MqttEndpoint) {
        require(endpoint is MqttEndpoint.WebSocket) {
            "WebSocketTransport requires MqttEndpoint.WebSocket"
        }

        // Close any existing connection to prevent resource leaks on reconnect
        close()

        val httpClient = buildWsHttpClient(Url(endpoint.url).host, MAX_FRAME_SIZE, configureTls)
        client = httpClient

        val wsSession =
            httpClient.webSocketSession(endpoint.url) {
                headers.append("Sec-WebSocket-Protocol", endpoint.protocols.joinToString(", "))
            }
        session = wsSession
    }

    override suspend fun send(bytes: ByteArray) {
        val ws = session ?: throw IllegalStateException("Not connected")
        sendMutex.withLock {
            ws.send(Frame.Binary(true, bytes))
        }
    }

    override suspend fun receive(): ByteArray {
        val ws = session ?: throw IllegalStateException("Not connected")
        val frame = ws.incoming.receive()
        return when (frame) {
            is Frame.Binary -> {
                if (frame.data.size > MAX_FRAME_SIZE) {
                    throw IllegalArgumentException(
                        "WebSocket frame size ${frame.data.size} exceeds safety cap $MAX_FRAME_SIZE",
                    )
                }
                frame.readBytes()
            }

            is Frame.Close -> {
                throw IllegalStateException("WebSocket closed by server")
            }

            else -> {
                throw IllegalStateException("Unexpected frame type: ${frame.frameType}")
            }
        }
    }

    override suspend fun close() {
        try {
            session?.close()
        } finally {
            try {
                client?.close()
            } finally {
                session = null
                client = null
            }
        }
    }

    private companion object {
        /** Safety cap matching TcpTransport's MAX_PACKET_REMAINING_LENGTH to prevent OOM. */
        const val MAX_FRAME_SIZE = 16L * 1024 * 1024 // 16 MB
    }
}

/**
 * [MqttTransportFactory] that builds a [WebSocketTransport] for [MqttEndpoint.WebSocket] endpoints.
 *
 * Add `org.meshtastic:mqtt-client-transport-ws` and pass an instance to
 * [org.meshtastic.mqtt.MqttConfig.Builder.transportFactory]. This is the only transport available
 * on the browser (wasmJs) target.
 *
 * To trust a broker whose certificate chain is not in the platform CA store — a private or
 * self-signed CA — supply [configureTls]. The scope is this MQTT connection only, unlike Android's
 * app-wide `network_security_config.xml` trust anchors:
 *
 * ```kotlin
 * transportFactory = WebSocketTransportFactory { trustManager = myTrustManager }
 * ```
 *
 * @param configureTls optional hook applied to ktor's [TLSConfigBuilder] for every transport this
 *   factory creates. It runs before platform trust is configured, so on Android a trust manager
 *   installed here is reached through the hostname-aware `checkServerTrusted` overload rather than
 *   bypassing it. Note that installing a trust manager replaces the platform's trust decision —
 *   network-security-config anchors, pinning, and Certificate Transparency policy then hold only
 *   insofar as that manager enforces them. Honoured on JVM, Android, Apple, and Linux; silently
 *   ignored on Windows (WinHttp has no TLS-config surface) and in the browser (which cannot
 *   influence trust). On Apple and Linux `TLSConfigBuilder` has no `trustManager`, so little is
 *   configurable there in practice.
 */
public class WebSocketTransportFactory(
    private val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransportFactory {
    public constructor() : this(null)

    override fun supports(endpoint: MqttEndpoint): Boolean = endpoint is MqttEndpoint.WebSocket

    override fun create(endpoint: MqttEndpoint): MqttTransport = WebSocketTransport(configureTls)
}
