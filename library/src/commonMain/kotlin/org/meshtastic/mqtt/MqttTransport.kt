package org.meshtastic.mqtt

/**
 * Abstract byte-level transport for MQTT connections.
 *
 * This is the sole platform abstraction boundary in the library.
 * Implementations:
 * - [TcpTransport] in `nonWebMain` — raw TCP sockets via ktor-network
 * - [WebSocketTransport] in `wasmJsMain` — binary WebSocket frames via ktor-client-websockets
 */
public interface MqttTransport {
    /** Establish a connection to the given host and port. */
    public suspend fun connect(
        host: String,
        port: Int,
        tls: Boolean,
    )

    /** Send raw bytes over the transport. */
    public suspend fun send(bytes: ByteArray)

    /** Receive one complete MQTT packet as raw bytes. */
    public suspend fun receive(): ByteArray

    /** Close the transport connection. */
    public suspend fun close()

    /** Whether the transport is currently connected. */
    public val isConnected: Boolean
}
