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

/**
 * Abstract byte-level transport for MQTT connections.
 *
 * This is the sole platform abstraction boundary in the library.
 * Implementations:
 * - `TcpTransport` in `nonWebMain` — raw TCP sockets via ktor-network (JVM, Android, iOS, macOS, Linux, Windows)
 * - `WebSocketTransport` in `nonWebMain` — binary WebSocket frames via ktor-client-websockets (JVM, Android, iOS, macOS, Linux, Windows)
 * - `WebSocketTransport` in `wasmJsMain` — binary WebSocket frames via ktor-client-websockets (browser)
 *
 * Not part of the public API — consumers use [MqttClient] instead.
 */
internal interface MqttTransport {
    /** Establish a connection to the broker. */
    suspend fun connect(endpoint: MqttEndpoint)

    /** Send raw bytes over the transport. */
    suspend fun send(bytes: ByteArray)

    /** Receive one complete MQTT packet as raw bytes. */
    suspend fun receive(): ByteArray

    /** Close the transport connection. */
    suspend fun close()

    /** Whether the transport is currently connected. */
    val isConnected: Boolean
}

/**
 * Describes how to reach an MQTT broker.
 *
 * Used as the argument to [MqttClient.connect] to specify the broker's address and transport.
 * Two transport types are supported, each as a sealed subclass:
 * - [Tcp] — raw TCP socket (optionally with TLS), available on JVM, Android, iOS, macOS, Linux, Windows.
 * - [WebSocket] — binary WebSocket frames, available on **all** platforms including browser/wasmJs.
 *
 * ## Example
 * ```kotlin
 * // Plain TCP
 * val tcp = MqttEndpoint.Tcp(host = "broker.example.com", port = 1883)
 *
 * // TCP with TLS
 * val tls = MqttEndpoint.Tcp(host = "broker.example.com", port = 8883, tls = true)
 *
 * // WebSocket (browser)
 * val ws = MqttEndpoint.WebSocket(url = "wss://broker.example.com/mqtt")
 * ```
 */
public sealed interface MqttEndpoint {
    /**
     * TCP socket connection, used by all non-browser targets.
     *
     * Connects via raw TCP (or TLS when [tls] is `true`) using ktor-network.
     * The standard MQTT ports are 1883 (plain) and 8883 (TLS).
     *
     * @property host Broker hostname or IP address. Must not be blank.
     * @property port TCP port number. Range: 1..65,535. Default: 1883.
     * @property tls If `true`, the connection is wrapped in TLS for encryption.
     *   Certificate validation uses the platform's default trust store.
     */
    public data class Tcp(
        val host: String,
        val port: Int = 1883,
        val tls: Boolean = false,
    ) : MqttEndpoint {
        init {
            require(host.isNotBlank()) { "host must not be blank" }
            require(port in 1..65_535) { "port must be 1..65535, got: $port" }
        }
    }

    /**
     * WebSocket connection, available on all platforms.
     *
     * Connects via binary WebSocket frames using ktor-client-websockets.
     * Each WebSocket frame carries exactly one complete MQTT packet.
     * Useful for connecting through load balancers, CDNs, and firewalls
     * that don't allow raw TCP but do allow WebSocket upgrades.
     *
     * @property url The WebSocket URL (e.g. `"wss://broker.example.com/mqtt"`).
     *   Must not be blank. Use `ws://` for unencrypted or `wss://` for TLS.
     * @property protocols WebSocket sub-protocols to negotiate. Default: `["mqtt"]`
     *   per the MQTT specification (§6.0).
     */
    public data class WebSocket(
        val url: String,
        val protocols: List<String> = listOf("mqtt"),
    ) : MqttEndpoint {
        init {
            require(url.isNotBlank()) { "url must not be blank" }
        }
    }

    public companion object {
        /**
         * Parse an MQTT endpoint from a URI string.
         *
         * Supported schemes:
         * - `tcp://host:port` or `mqtt://host:port` — plain TCP (default port: 1883)
         * - `ssl://host:port` or `mqtts://host:port` or `tls://host:port` — TCP with TLS (default port: 8883)
         * - `ws://host:port/path` — plain WebSocket
         * - `wss://host:port/path` — WebSocket with TLS
         *
         * ## Example
         * ```kotlin
         * val tcp = MqttEndpoint.parse("tcp://broker.example.com:1883")
         * val tls = MqttEndpoint.parse("ssl://broker.example.com:8883")
         * val ws  = MqttEndpoint.parse("wss://broker.example.com/mqtt")
         * ```
         *
         * @param uri The URI to parse.
         * @throws IllegalArgumentException if the URI scheme is unsupported or the format is invalid.
         */
        public fun parse(uri: String): MqttEndpoint {
            val colonSlashSlash = uri.indexOf("://")
            require(colonSlashSlash > 0) { "Invalid MQTT URI — missing scheme: $uri" }

            val scheme = uri.substring(0, colonSlashSlash).lowercase()
            val rest = uri.substring(colonSlashSlash + 3)

            return when (scheme) {
                "tcp", "mqtt" -> parseTcp(rest, tls = false, defaultPort = 1883)

                "ssl", "mqtts", "tls" -> parseTcp(rest, tls = true, defaultPort = 8883)

                "ws" -> WebSocket(url = uri)

                "wss" -> WebSocket(url = uri)

                else -> throw IllegalArgumentException(
                    "Unsupported MQTT URI scheme '$scheme'. Use tcp://, ssl://, ws://, or wss://",
                )
            }
        }

        private fun parseTcp(
            hostPort: String,
            tls: Boolean,
            defaultPort: Int,
        ): Tcp {
            // Strip any trailing path (e.g., "host:1883/mqtt" → "host:1883")
            val withoutPath = hostPort.substringBefore('/')

            // Handle bracket-enclosed IPv6 addresses (e.g., "[::1]:1883")
            if (withoutPath.startsWith('[')) {
                val closeBracket = withoutPath.indexOf(']')
                require(closeBracket > 0) { "Unclosed IPv6 bracket in URI: $withoutPath" }
                val host = withoutPath.substring(1, closeBracket)
                val afterBracket = withoutPath.substring(closeBracket + 1)
                val port =
                    if (afterBracket.startsWith(':')) {
                        afterBracket.substring(1).toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid port in URI: $withoutPath")
                    } else {
                        defaultPort
                    }
                return Tcp(host = host, port = port, tls = tls)
            }

            val colonIdx = withoutPath.lastIndexOf(':')
            return if (colonIdx > 0) {
                val host = withoutPath.substring(0, colonIdx)
                val port =
                    withoutPath.substring(colonIdx + 1).toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid port in URI: $withoutPath")
                Tcp(host = host, port = port, tls = tls)
            } else {
                Tcp(host = withoutPath, port = defaultPort, tls = tls)
            }
        }
    }
}
