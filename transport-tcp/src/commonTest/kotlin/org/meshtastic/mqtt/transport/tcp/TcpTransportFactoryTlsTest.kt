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
package org.meshtastic.mqtt.transport.tcp

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
 * Covers the caller-supplied TLS hook on [TcpTransportFactory] (issue #102).
 *
 * The hook itself is only exercised during a real TLS handshake, so these tests pin the
 * surrounding contract: the factory still selects the right endpoints, still produces a
 * [TcpTransport], and still composes with other factories via `+`.
 */
class TcpTransportFactoryTlsTest {
    @Test
    fun factoryWithTlsHookStillSupportsOnlyTcpEndpoints() {
        val factory = TcpTransportFactory { serverName = "override.example.com" }
        assertTrue(factory.supports(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true)))
        assertFalse(factory.supports(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt")))
    }

    @Test
    fun factoryWithTlsHookCreatesTcpTransport() {
        val factory = TcpTransportFactory { serverName = "override.example.com" }
        val transport = factory.create(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true))
        assertIs<TcpTransport>(transport)
        assertFalse(transport.isConnected)
    }

    @Test
    fun factoryForwardsTlsHookToCreatedTransport() {
        // Guards against create() silently dropping configureTls: the transport must hold the
        // very lambda instance the factory was constructed with.
        val hook: TLSConfigBuilder.() -> Unit = { serverName = "override.example.com" }
        val transport = TcpTransportFactory(hook).create(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true))
        assertIs<TcpTransport>(transport)
        assertSame(hook, transport.configureTls)
    }

    @Test
    fun noArgFactoryLeavesTlsHookNull() {
        val transport = TcpTransportFactory().create(MqttEndpoint.Tcp("broker.example.com", 1883, tls = false))
        assertIs<TcpTransport>(transport)
        assertNull(transport.configureTls)
    }

    @Test
    fun noArgFactoryStillWorks() {
        val factory = TcpTransportFactory()
        assertTrue(factory.supports(MqttEndpoint.Tcp("broker.example.com", 1883, tls = false)))
        assertIs<TcpTransport>(factory.create(MqttEndpoint.Tcp("broker.example.com", 1883, tls = false)))
    }

    @Test
    fun factoryWithTlsHookComposesWithPlus() {
        val combined = TcpTransportFactory { serverName = "override.example.com" } + TcpTransportFactory()
        assertTrue(combined.supports(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true)))
        assertIs<TcpTransport>(combined.create(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true)))
    }
}
