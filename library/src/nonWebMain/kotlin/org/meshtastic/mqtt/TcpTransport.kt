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

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.mqtt.packet.VariableByteInt

/**
 * TCP transport for MQTT over raw sockets with optional TLS.
 *
 * TCP is stream-oriented, so [receive] must reconstruct MQTT packet boundaries by
 * parsing the fixed header and Variable Byte Integer remaining length from the byte
 * stream, then reading exactly that many payload bytes.
 */
internal class TcpTransport : MqttTransport {
    private var socket: Socket? = null
    private var selectorManager: SelectorManager? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    private val sendMutex = Mutex()

    override val isConnected: Boolean
        get() = socket?.isClosed == false

    override suspend fun connect(endpoint: MqttEndpoint) {
        require(endpoint is MqttEndpoint.Tcp) {
            "TcpTransport only supports MqttEndpoint.Tcp. " +
                "WebSocket endpoints (MqttEndpoint.WebSocket) are available on wasmJs only."
        }

        // Close any existing connection to prevent resource leaks on reconnect
        close()

        val selector = SelectorManager(Dispatchers.IO)
        val rawSocket = aSocket(selector).tcp().connect(endpoint.host, endpoint.port)

        try {
            socket =
                if (endpoint.tls) {
                    rawSocket.tls(Dispatchers.IO)
                } else {
                    rawSocket
                }
        } catch (e: Exception) {
            // TLS handshake failed — close the raw socket and selector to prevent leaks
            try {
                rawSocket.close()
            } finally {
                selector.close()
            }
            throw e
        }

        selectorManager = selector
        readChannel = socket!!.openReadChannel()
        writeChannel = socket!!.openWriteChannel(autoFlush = false)
    }

    override suspend fun send(bytes: ByteArray) {
        val channel = writeChannel ?: error("Not connected")
        sendMutex.withLock {
            channel.writeFully(bytes)
            channel.flush()
        }
    }

    /**
     * Reads one complete MQTT packet from the TCP stream.
     *
     * 1. Read the fixed header byte (packet type + flags).
     * 2. Read the Variable Byte Integer remaining length (1–4 bytes).
     * 3. Read exactly `remainingLength` payload bytes.
     * 4. Return the reassembled packet as a single [ByteArray].
     */
    override suspend fun receive(): ByteArray {
        val channel = readChannel ?: error("Not connected")

        // Step 1: Fixed header byte (§2.1.1)
        val firstByte = channel.readByte()

        // Step 2: Variable Byte Integer remaining length (§1.5.5)
        val vbiBytes = ByteArray(4)
        var vbiLen = 0
        do {
            val b = channel.readByte()
            vbiBytes[vbiLen++] = b
        } while (b.toInt() and 0x80 != 0 && vbiLen < 4)

        if (vbiLen == 4 && vbiBytes[3].toInt() and 0x80 != 0) {
            throw IllegalArgumentException("Malformed VBI: exceeds 4 bytes")
        }

        val vbiResult = VariableByteInt.decode(vbiBytes, 0)
        val remainingLength = vbiResult.value

        // Safety cap: reject packets larger than the VBI maximum to prevent OOM
        // from a malicious/broken broker. Application-level enforcement of
        // config.maximumPacketSize happens in MqttConnection's read loop.
        if (remainingLength > MAX_PACKET_REMAINING_LENGTH) {
            throw IllegalArgumentException(
                "Packet remaining length $remainingLength exceeds safety cap $MAX_PACKET_REMAINING_LENGTH",
            )
        }

        // Step 3: Read exactly remainingLength bytes
        val payload = ByteArray(remainingLength)
        if (remainingLength > 0) {
            channel.readFully(payload)
        }

        // Step 4: Assemble complete MQTT packet
        val packet = ByteArray(1 + vbiLen + remainingLength)
        packet[0] = firstByte
        vbiBytes.copyInto(packet, destinationOffset = 1, startIndex = 0, endIndex = vbiLen)
        payload.copyInto(packet, destinationOffset = 1 + vbiLen)

        return packet
    }

    override suspend fun close() {
        try {
            socket?.close()
        } finally {
            socket = null
            readChannel = null
            writeChannel = null
            selectorManager?.close()
            selectorManager = null
        }
    }

    private companion object {
        /**
         * Hard safety cap for remaining length allocation.
         *
         * Defaults to 16 MB to prevent OOM from a malicious/broken broker.
         * Application-level enforcement of config.maximumPacketSize happens
         * in MqttConnection's read loop. This is a transport-level safety net.
         */
        const val MAX_PACKET_REMAINING_LENGTH = 16 * 1024 * 1024 // 16 MB
    }
}
