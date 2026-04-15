package org.meshtastic.mqtt

/**
 * Abstract byte-level transport for MQTT connections.
 *
 * This is the sole platform abstraction boundary in the library.
 * Implementations:
 * - `TcpTransport` in `nonWebMain` — raw TCP sockets via ktor-network
 * - `WebSocketTransport` in `wasmJsMain` — binary WebSocket frames via ktor-client-websockets
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
 */
public sealed interface MqttEndpoint {
    /** TCP socket connection (used by all non-browser targets). */
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

    /** WebSocket connection (used by browser/wasmJs targets). */
    public data class WebSocket(
        val url: String,
        val protocols: List<String> = listOf("mqtt"),
    ) : MqttEndpoint {
        init {
            require(url.isNotBlank()) { "url must not be blank" }
        }
    }
}
