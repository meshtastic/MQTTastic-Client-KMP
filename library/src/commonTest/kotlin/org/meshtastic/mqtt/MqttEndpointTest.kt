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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MqttEndpointTest {
    @Test
    fun tcpDefaultPort() {
        val ep = MqttEndpoint.Tcp(host = "broker.example.com")
        assertEquals(1883, ep.port)
        assertEquals(false, ep.tls)
    }

    @Test
    fun tcpRejectsBlankHost() {
        assertFailsWith<IllegalArgumentException> {
            MqttEndpoint.Tcp(host = "  ")
        }
    }

    @Test
    fun tcpRejectsPortZero() {
        assertFailsWith<IllegalArgumentException> {
            MqttEndpoint.Tcp(host = "localhost", port = 0)
        }
    }

    @Test
    fun tcpRejectsPortOverflow() {
        assertFailsWith<IllegalArgumentException> {
            MqttEndpoint.Tcp(host = "localhost", port = 65_536)
        }
    }

    @Test
    fun tcpAcceptsBoundaryPorts() {
        MqttEndpoint.Tcp(host = "localhost", port = 1)
        MqttEndpoint.Tcp(host = "localhost", port = 65_535)
    }

    @Test
    fun webSocketRejectsBlankUrl() {
        assertFailsWith<IllegalArgumentException> {
            MqttEndpoint.WebSocket(url = "")
        }
    }

    @Test
    fun webSocketDefaultProtocols() {
        val ep = MqttEndpoint.WebSocket(url = "wss://broker.example.com/mqtt")
        assertEquals(listOf("mqtt"), ep.protocols)
    }
}
