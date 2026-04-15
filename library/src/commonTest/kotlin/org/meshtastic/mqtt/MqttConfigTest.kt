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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MqttConfigTest {
    // --- MqttConfig defaults ---

    @Test
    fun mqttConfigDefaults() {
        val config = MqttConfig()
        assertEquals("", config.clientId)
        assertEquals(60, config.keepAliveSeconds)
        assertTrue(config.cleanStart)
        assertNull(config.username)
        assertNull(config.password)
        assertNull(config.will)
        assertNull(config.sessionExpiryInterval)
        assertEquals(65535, config.receiveMaximum)
        assertNull(config.maximumPacketSize)
        assertEquals(0, config.topicAliasMaximum)
        assertFalse(config.requestResponseInformation)
        assertTrue(config.requestProblemInformation)
        assertEquals(emptyList(), config.userProperties)
        assertNull(config.authenticationMethod)
        assertNull(config.authenticationData)
        assertTrue(config.autoReconnect)
        assertEquals(1000L, config.reconnectBaseDelayMs)
        assertEquals(30000L, config.reconnectMaxDelayMs)
    }

    // --- MqttConfig keepAliveSeconds validation ---

    @Test
    fun keepAliveSecondsRejectsNegative() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(keepAliveSeconds = -1)
        }
    }

    @Test
    fun keepAliveSecondsRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(keepAliveSeconds = 65_536)
        }
    }

    @Test
    fun keepAliveSecondsAcceptsBounds() {
        MqttConfig(keepAliveSeconds = 0)
        MqttConfig(keepAliveSeconds = 65_535)
    }

    // --- MqttConfig receiveMaximum validation ---

    @Test
    fun receiveMaximumRejectsZero() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(receiveMaximum = 0)
        }
    }

    @Test
    fun receiveMaximumRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(receiveMaximum = 65_536)
        }
    }

    @Test
    fun receiveMaximumAcceptsBounds() {
        MqttConfig(receiveMaximum = 1)
        MqttConfig(receiveMaximum = 65_535)
    }

    // --- MqttConfig sessionExpiryInterval validation ---

    @Test
    fun sessionExpiryIntervalRejectsNegative() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(sessionExpiryInterval = -1L)
        }
    }

    @Test
    fun sessionExpiryIntervalAcceptsBounds() {
        MqttConfig(sessionExpiryInterval = 0L)
        MqttConfig(sessionExpiryInterval = 4_294_967_295L)
    }

    // --- MqttConfig maximumPacketSize validation ---

    @Test
    fun maximumPacketSizeRejectsZero() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(maximumPacketSize = 0L)
        }
    }

    @Test
    fun maximumPacketSizeAcceptsBounds() {
        MqttConfig(maximumPacketSize = 1L)
        MqttConfig(maximumPacketSize = 4_294_967_295L)
    }

    // --- MqttConfig topicAliasMaximum validation ---

    @Test
    fun topicAliasMaximumRejectsNegative() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(topicAliasMaximum = -1)
        }
    }

    @Test
    fun topicAliasMaximumRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(topicAliasMaximum = 65_536)
        }
    }

    @Test
    fun topicAliasMaximumAcceptsBounds() {
        MqttConfig(topicAliasMaximum = 0)
        MqttConfig(topicAliasMaximum = 65_535)
    }

    // --- MqttConfig reconnect validation ---

    @Test
    fun reconnectBaseDelayMsRejectsZero() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(reconnectBaseDelayMs = 0)
        }
    }

    @Test
    fun reconnectMaxDelayMsRejectsLessThanBase() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig(reconnectBaseDelayMs = 2000, reconnectMaxDelayMs = 1000)
        }
    }

    @Test
    fun reconnectDelaysAcceptEqual() {
        MqttConfig(reconnectBaseDelayMs = 5000, reconnectMaxDelayMs = 5000)
    }

    // --- WillConfig defaults ---

    @Test
    fun willConfigDefaults() {
        val will = WillConfig(topic = "last-will")
        assertEquals("last-will", will.topic)
        assertEquals(ByteString(), will.payload)
        assertEquals(QoS.AT_MOST_ONCE, will.qos)
        assertFalse(will.retain)
        assertNull(will.willDelayInterval)
        assertNull(will.messageExpiryInterval)
        assertNull(will.contentType)
        assertNull(will.responseTopic)
        assertNull(will.correlationData)
        assertFalse(will.payloadFormatIndicator)
        assertEquals(emptyList(), will.userProperties)
    }

    // --- WillConfig validation ---

    @Test
    fun willConfigRejectsBlankTopic() {
        assertFailsWith<IllegalArgumentException> {
            WillConfig(topic = "  ")
        }
    }

    @Test
    fun willConfigRejectsEmptyTopic() {
        assertFailsWith<IllegalArgumentException> {
            WillConfig(topic = "")
        }
    }

    @Test
    fun willDelayIntervalRejectsNegative() {
        assertFailsWith<IllegalArgumentException> {
            WillConfig(topic = "t", willDelayInterval = -1L)
        }
    }

    @Test
    fun willDelayIntervalAcceptsBounds() {
        WillConfig(topic = "t", willDelayInterval = 0L)
        WillConfig(topic = "t", willDelayInterval = 4_294_967_295L)
    }

    @Test
    fun willMessageExpiryIntervalRejectsNegative() {
        assertFailsWith<IllegalArgumentException> {
            WillConfig(topic = "t", messageExpiryInterval = -1L)
        }
    }

    @Test
    fun willMessageExpiryIntervalAcceptsBounds() {
        WillConfig(topic = "t", messageExpiryInterval = 0L)
        WillConfig(topic = "t", messageExpiryInterval = 4_294_967_295L)
    }

    // --- WillConfig ByteArray constructor ---

    @Test
    fun willConfigByteArrayConstructorDefensiveCopy() {
        val original = byteArrayOf(1, 2, 3)
        val will = WillConfig(topic = "t", payload = original)

        // Mutate the original — will should not be affected
        original[0] = 99
        assertEquals(1, will.payload[0])
    }

    // --- Data class equality and copy ---

    @Test
    fun mqttConfigEquality() {
        val a = MqttConfig(clientId = "client-1", keepAliveSeconds = 30)
        val b = MqttConfig(clientId = "client-1", keepAliveSeconds = 30)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun mqttConfigInequality() {
        val a = MqttConfig(clientId = "client-1")
        val b = MqttConfig(clientId = "client-2")
        assertNotEquals(a, b)
    }

    @Test
    fun mqttConfigCopyPreservesFields() {
        val config =
            MqttConfig(
                clientId = "c1",
                keepAliveSeconds = 30,
                cleanStart = false,
                receiveMaximum = 100,
                autoReconnect = false,
            )
        val copied = config.copy(clientId = "c2")
        assertEquals("c2", copied.clientId)
        assertEquals(30, copied.keepAliveSeconds)
        assertFalse(copied.cleanStart)
        assertEquals(100, copied.receiveMaximum)
        assertFalse(copied.autoReconnect)
    }

    @Test
    fun willConfigEquality() {
        val a = WillConfig(topic = "t", payload = ByteString(1, 2, 3))
        val b = WillConfig(topic = "t", payload = ByteString(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun willConfigInequality() {
        val a = WillConfig(topic = "t1")
        val b = WillConfig(topic = "t2")
        assertNotEquals(a, b)
    }

    @Test
    fun willConfigCopyPreservesFields() {
        val will =
            WillConfig(
                topic = "t",
                payload = ByteString(1, 2),
                qos = QoS.EXACTLY_ONCE,
                retain = true,
                willDelayInterval = 60L,
                contentType = "text/plain",
            )
        val copied = will.copy(topic = "t2")
        assertEquals("t2", copied.topic)
        assertEquals(ByteString(1, 2), copied.payload)
        assertEquals(QoS.EXACTLY_ONCE, copied.qos)
        assertTrue(copied.retain)
        assertEquals(60L, copied.willDelayInterval)
        assertEquals("text/plain", copied.contentType)
    }
}
