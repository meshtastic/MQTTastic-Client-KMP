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

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WebSocket-based [MqttTransport] for browser/wasmJs targets.
 *
 * Uses Ktor's Js engine (auto-detected from classpath). One binary WebSocket
 * frame = one complete MQTT packet, so no stream parsing is needed — the
 * WebSocket layer handles framing.
 */
internal class WebSocketTransport : MqttTransport {
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

        val httpClient =
            HttpClient {
                install(WebSockets) {
                    maxFrameSize = MAX_FRAME_SIZE
                }
            }
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
