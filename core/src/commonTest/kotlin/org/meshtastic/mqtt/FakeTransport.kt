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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.meshtastic.mqtt.packet.MqttPacket
import org.meshtastic.mqtt.packet.decodePacket
import org.meshtastic.mqtt.packet.encode

/**
 * In-memory [MqttTransport] for unit testing the MQTT client state machine.
 *
 * Provides helpers to enqueue broker responses, inspect client-sent packets,
 * and simulate connection errors — all without touching the network.
 *
 * @param version The protocol version used for encoding/decoding packets in
 *   test helpers. Defaults to [MqttProtocolVersion.V5_0].
 */
internal class FakeTransport(
    private val version: MqttProtocolVersion = MqttProtocolVersion.V5_0,
) : MqttTransport {
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    private var receiveChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private val _sentPackets = mutableListOf<ByteArray>()

    /** Snapshot of all raw byte arrays sent by the client, in order. */
    val sentPackets: List<ByteArray> get() = _sentPackets.toList()

    // -- Error simulation --

    /** When non-null, [connect] throws this instead of connecting. */
    var connectError: Throwable? = null

    /** When non-null, [send] throws this instead of recording the packet. */
    var sendError: Throwable? = null

    /** When non-null, [receive] throws this instead of dequeuing. */
    var receiveError: Throwable? = null

    /** Optional callback invoked after each successful [connect] — use to enqueue response packets. */
    var onConnect: (() -> Unit)? = null

    // -- MqttTransport implementation --

    override suspend fun connect(endpoint: MqttEndpoint) {
        connectError?.let { throw it }
        // Recreate receive channel if it was closed (supports reconnect testing)
        @OptIn(ExperimentalCoroutinesApi::class)
        if (receiveChannel.isClosedForSend) {
            receiveChannel = Channel(Channel.UNLIMITED)
        }
        _isConnected = true
        onConnect?.invoke()
    }

    override suspend fun send(bytes: ByteArray) {
        check(_isConnected) { "Not connected" }
        sendError?.let { throw it }
        _sentPackets.add(bytes.copyOf())
    }

    override suspend fun receive(): ByteArray {
        check(_isConnected) { "Not connected" }
        receiveError?.let { error ->
            receiveError = null // Single-shot: clear after first throw
            throw error
        }
        return receiveChannel.receive()
    }

    override suspend fun close() {
        _isConnected = false
        receiveChannel.close()
    }

    // -- Test helpers --

    /** Enqueue raw bytes for the client to receive from the fake broker. */
    fun enqueueReceive(bytes: ByteArray) {
        receiveChannel.trySend(bytes.copyOf())
    }

    /** Encode and enqueue an [MqttPacket] for the client to receive. */
    fun enqueuePacket(packet: MqttPacket) {
        enqueueReceive(packet.encode(version))
    }

    /** Decode all sent byte arrays back into [MqttPacket] objects. */
    fun decodeSentPackets(): List<MqttPacket> = _sentPackets.map { decodePacket(it, version) }

    /** Decode the most recently sent packet. */
    fun lastSentPacket(): MqttPacket = decodePacket(_sentPackets.last(), version)

    /** Clear the sent-packet history. */
    fun clearSent() {
        _sentPackets.clear()
    }

    /** Reset all state so the transport can be reused across test cases. */
    fun reset() {
        _isConnected = false
        _sentPackets.clear()
        connectError = null
        sendError = null
        receiveError = null
        onConnect = null
        receiveChannel.close()
        receiveChannel = Channel(Channel.UNLIMITED)
    }
}
