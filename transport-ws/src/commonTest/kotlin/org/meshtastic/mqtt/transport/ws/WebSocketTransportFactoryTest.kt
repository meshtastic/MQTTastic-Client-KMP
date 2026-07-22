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

import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.MqttTransport
import org.meshtastic.mqtt.MqttTransportFactory
import org.meshtastic.mqtt.plus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebSocketTransportFactoryTest {
    @Test
    fun `supports WebSocket endpoints but not TCP`() {
        val factory = WebSocketTransportFactory()
        assertTrue(factory.supports(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt")))
        assertFalse(factory.supports(MqttEndpoint.Tcp("broker.example.com")))
    }

    @Test
    fun `create returns a WebSocketTransport`() {
        val transport = WebSocketTransportFactory().create(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt"))
        assertIs<WebSocketTransport>(transport)
    }

    @Test
    fun `create returns a distinct instance on each call`() {
        // The MqttTransportFactory SPI requires a fresh transport per call.
        val factory = WebSocketTransportFactory()
        val endpoint = MqttEndpoint.WebSocket("wss://broker.example.com/mqtt")
        assertNotSame(factory.create(endpoint), factory.create(endpoint))
    }

    @Test
    fun `combined factory routes WebSocket to WS and TCP to the other factory`() {
        val tcpFactory = FakeTcpFactory()
        val combined = WebSocketTransportFactory() + tcpFactory

        assertIs<WebSocketTransport>(combined.create(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt")))
        assertSame(tcpFactory.transport, combined.create(MqttEndpoint.Tcp("broker.example.com")))
    }
}

/** Minimal factory that supports only TCP, used to exercise the `plus` combinator. */
private class FakeTcpFactory : MqttTransportFactory {
    val transport: MqttTransport = NoopTransport()

    override fun supports(endpoint: MqttEndpoint): Boolean = endpoint is MqttEndpoint.Tcp

    override fun create(endpoint: MqttEndpoint): MqttTransport = transport
}

private class NoopTransport : MqttTransport {
    override val isConnected: Boolean = false

    override suspend fun connect(endpoint: MqttEndpoint) = Unit

    override suspend fun send(bytes: ByteArray) = Unit

    override suspend fun receive(): ByteArray = ByteArray(0)

    override suspend fun close() = Unit
}
