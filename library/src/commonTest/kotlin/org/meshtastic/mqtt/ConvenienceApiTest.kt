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

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConvenienceApiTest {
    // --- MqttMessage.payloadAsString() ---

    @Test
    fun payloadAsStringDecodesUtf8() {
        val msg = MqttMessage(topic = "test", payload = ByteString("hello world".encodeToByteArray()))
        assertEquals("hello world", msg.payloadAsString())
    }

    @Test
    fun payloadAsStringEmpty() {
        val msg = MqttMessage(topic = "test", payload = ByteString())
        assertEquals("", msg.payloadAsString())
    }

    @Test
    fun payloadAsStringMultibyte() {
        val text = "Héllo Wörld 🌍"
        val msg = MqttMessage(topic = "test", payload = ByteString(text.encodeToByteArray()))
        assertEquals(text, msg.payloadAsString())
    }

    // --- MqttEndpoint.parse() ---

    @Test
    fun parseTcpPlain() {
        val endpoint = MqttEndpoint.parse("tcp://broker.example.com:1883")
        assertIs<MqttEndpoint.Tcp>(endpoint)
        assertEquals("broker.example.com", endpoint.host)
        assertEquals(1883, endpoint.port)
        assertFalse(endpoint.tls)
    }

    @Test
    fun parseTcpDefaultPort() {
        val endpoint = MqttEndpoint.parse("tcp://broker.example.com")
        assertIs<MqttEndpoint.Tcp>(endpoint)
        assertEquals("broker.example.com", endpoint.host)
        assertEquals(1883, endpoint.port)
    }

    @Test
    fun parseMqttScheme() {
        val endpoint = MqttEndpoint.parse("mqtt://broker.example.com:1883")
        assertIs<MqttEndpoint.Tcp>(endpoint)
        assertEquals("broker.example.com", endpoint.host)
        assertFalse(endpoint.tls)
    }

    @Test
    fun parseSslScheme() {
        val endpoint = MqttEndpoint.parse("ssl://broker.example.com:8883")
        assertIs<MqttEndpoint.Tcp>(endpoint)
        assertEquals("broker.example.com", endpoint.host)
        assertEquals(8883, endpoint.port)
        assertTrue(endpoint.tls)
    }

    @Test
    fun parseMqttsScheme() {
        val endpoint = MqttEndpoint.parse("mqtts://broker.example.com")
        assertIs<MqttEndpoint.Tcp>(endpoint)
        assertEquals(8883, endpoint.port)
        assertTrue(endpoint.tls)
    }

    @Test
    fun parseTlsScheme() {
        val endpoint = MqttEndpoint.parse("tls://broker.example.com:9883")
        assertIs<MqttEndpoint.Tcp>(endpoint)
        assertEquals(9883, endpoint.port)
        assertTrue(endpoint.tls)
    }

    @Test
    fun parseWsScheme() {
        val endpoint = MqttEndpoint.parse("ws://broker.example.com/mqtt")
        assertIs<MqttEndpoint.WebSocket>(endpoint)
        assertEquals("ws://broker.example.com/mqtt", endpoint.url)
    }

    @Test
    fun parseWssScheme() {
        val endpoint = MqttEndpoint.parse("wss://broker.example.com/mqtt")
        assertIs<MqttEndpoint.WebSocket>(endpoint)
        assertEquals("wss://broker.example.com/mqtt", endpoint.url)
    }

    @Test
    fun parseInvalidScheme() {
        assertFailsWith<IllegalArgumentException> {
            MqttEndpoint.parse("http://broker.example.com")
        }
    }

    @Test
    fun parseMissingScheme() {
        assertFailsWith<IllegalArgumentException> {
            MqttEndpoint.parse("broker.example.com:1883")
        }
    }

    @Test
    fun parseCaseInsensitiveScheme() {
        val endpoint = MqttEndpoint.parse("TCP://broker.example.com")
        assertIs<MqttEndpoint.Tcp>(endpoint)
    }

    // --- topicMatchesFilter() ---

    @Test
    fun exactTopicMatch() {
        assertTrue(topicMatchesFilter("sport/tennis", "sport/tennis"))
    }

    @Test
    fun exactTopicNoMatch() {
        assertFalse(topicMatchesFilter("sport/tennis", "sport/football"))
    }

    @Test
    fun singleLevelWildcard() {
        assertTrue(topicMatchesFilter("sport/tennis/player1", "sport/+/player1"))
        assertTrue(topicMatchesFilter("sport/football/player1", "sport/+/player1"))
        assertFalse(topicMatchesFilter("sport/tennis/a/player1", "sport/+/player1"))
    }

    @Test
    fun multiLevelWildcard() {
        assertTrue(topicMatchesFilter("sport/tennis", "sport/#"))
        assertTrue(topicMatchesFilter("sport/tennis/player1", "sport/#"))
        assertTrue(topicMatchesFilter("sport", "sport/#"))
    }

    @Test
    fun hashMatchesAll() {
        assertTrue(topicMatchesFilter("any/topic/here", "#"))
        assertTrue(topicMatchesFilter("a", "#"))
    }

    @Test
    fun plusAtStart() {
        assertTrue(topicMatchesFilter("a/b", "+/b"))
        assertFalse(topicMatchesFilter("a/c", "+/b"))
    }

    @Test
    fun singlePlusMatchesSingleLevel() {
        assertTrue(topicMatchesFilter("a", "+"))
        assertFalse(topicMatchesFilter("a/b", "+"))
    }

    @Test
    fun combinedWildcards() {
        assertTrue(topicMatchesFilter("sport/tennis/player1/score", "+/tennis/#"))
        assertTrue(topicMatchesFilter("x/tennis/a/b/c", "+/tennis/#"))
        assertFalse(topicMatchesFilter("sport/football/player1", "+/tennis/#"))
    }

    @Test
    fun emptyLevelExactMatch() {
        assertTrue(topicMatchesFilter("/a/b", "/a/b"))
        assertFalse(topicMatchesFilter("a/b", "/a/b"))
    }

    // --- MqttClient factory function ---

    @Test
    fun clientFactoryFunction() {
        val client =
            MqttClient("test-id") {
                keepAliveSeconds = 15
                autoReconnect = false
            }
        // Just verify it constructs without error — can't introspect config from outside
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }

    // --- MqttConfig.build {} ---

    @Test
    fun configBuilderWithLogger() {
        val config =
            MqttConfig.build {
                clientId = "logged-client"
                logger = MqttLogger.println()
                logLevel = MqttLogLevel.INFO
            }
        assertEquals("logged-client", config.clientId)
        assertEquals(MqttLogLevel.INFO, config.logLevel)
    }
}
