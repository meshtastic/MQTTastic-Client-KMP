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

import io.ktor.network.tls.TLSConfigBuilder
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.plus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the caller-supplied TLS hook on [WebSocketTransportFactory] (issue #107).
 *
 * The hook only runs during a real TLS handshake, so these tests pin the surrounding
 * contract: endpoint selection, transport type, hook forwarding, and `+` composition.
 * The handshake itself is covered by the JVM end-to-end test in Task 5.
 */
class WebSocketTransportFactoryTlsTest {
    private val endpoint = MqttEndpoint.WebSocket("wss://broker.example.com/mqtt")

    @Test
    fun factoryWithTlsHookStillSupportsOnlyWebSocketEndpoints() {
        val factory = WebSocketTransportFactory { serverName = "override.example.com" }
        assertTrue(factory.supports(endpoint))
        assertFalse(factory.supports(MqttEndpoint.Tcp("broker.example.com")))
    }

    @Test
    fun factoryForwardsTlsHookToCreatedTransport() {
        // Guards against create() silently dropping configureTls: the transport must hold the
        // very lambda instance the factory was constructed with.
        val hook: TLSConfigBuilder.() -> Unit = { serverName = "override.example.com" }
        val transport = WebSocketTransportFactory(hook).create(endpoint)
        assertIs<WebSocketTransport>(transport)
        assertSame(hook, transport.configureTls)
    }

    @Test
    fun noArgFactoryProducesTransportWithoutHook() {
        val transport = WebSocketTransportFactory().create(endpoint)
        assertIs<WebSocketTransport>(transport)
        assertNull(transport.configureTls)
    }

    @Test
    fun factoryWithTlsHookStillComposesWithPlus() {
        val combined =
            WebSocketTransportFactory { serverName = "override.example.com" } +
                WebSocketTransportFactory()
        assertIs<WebSocketTransport>(combined.create(endpoint))
    }
}
