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
