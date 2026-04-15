package org.meshtastic.mqtt

import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MqttMessageTest {
    @Test
    fun byteArrayConstructorCreatesImmutablePayload() {
        val original = byteArrayOf(1, 2, 3)
        val message = MqttMessage(topic = "test", payload = original)

        // Mutate the original — message should not be affected
        original[0] = 99
        assertEquals(1, message.payload[0])
    }

    @Test
    fun dataClassEqualityWorks() {
        val a = MqttMessage(topic = "t", payload = byteArrayOf(1, 2))
        val b = MqttMessage(topic = "t", payload = byteArrayOf(1, 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequalityOnDifferentPayload() {
        val a = MqttMessage(topic = "t", payload = byteArrayOf(1, 2))
        val b = MqttMessage(topic = "t", payload = byteArrayOf(1, 3))
        assertNotEquals(a, b)
    }

    @Test
    fun defaultsAreCorrect() {
        val msg = MqttMessage(topic = "t", payload = byteArrayOf())
        assertEquals(QoS.AT_MOST_ONCE, msg.qos)
        assertFalse(msg.retain)
        assertEquals(PublishProperties(), msg.properties)
    }

    @Test
    fun propertiesAffectEquality() {
        val a =
            MqttMessage(
                topic = "t",
                payload = ByteString(),
                properties = PublishProperties(contentType = "text/plain"),
            )
        val b =
            MqttMessage(
                topic = "t",
                payload = ByteString(),
                properties = PublishProperties(contentType = "application/json"),
            )
        assertNotEquals(a, b)
    }

    @Test
    fun publishPropertiesEquality() {
        val a =
            PublishProperties(
                correlationData = ByteString(1, 2, 3),
                userProperties = listOf("key" to "value"),
            )
        val b =
            PublishProperties(
                correlationData = ByteString(1, 2, 3),
                userProperties = listOf("key" to "value"),
            )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun toStringDoesNotDumpPayload() {
        val msg = MqttMessage(topic = "test/topic", payload = ByteArray(1024))
        val str = msg.toString()
        assertTrue(str.contains("1024B"))
        assertFalse(str.contains("[0, 0, 0"))
    }

    @Test
    fun copyPreservesAllFields() {
        val msg =
            MqttMessage(
                topic = "t",
                payload = ByteString(1, 2, 3),
                qos = QoS.EXACTLY_ONCE,
                retain = true,
                properties = PublishProperties(contentType = "text/plain"),
            )
        val copied = msg.copy(topic = "t2")
        assertEquals("t2", copied.topic)
        assertEquals(msg.payload, copied.payload)
        assertEquals(msg.qos, copied.qos)
        assertEquals(msg.retain, copied.retain)
        assertEquals(msg.properties, copied.properties)
    }

    // --- PublishProperties validation ---

    @Test
    fun topicAliasRejectsZero() {
        assertFailsWith<IllegalArgumentException> {
            PublishProperties(topicAlias = 0)
        }
    }

    @Test
    fun topicAliasRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> {
            PublishProperties(topicAlias = 65_536)
        }
    }

    @Test
    fun topicAliasAcceptsBounds() {
        PublishProperties(topicAlias = 1)
        PublishProperties(topicAlias = 65_535)
    }

    @Test
    fun messageExpiryRejectsNegative() {
        assertFailsWith<IllegalArgumentException> {
            PublishProperties(messageExpiryInterval = -1L)
        }
    }

    @Test
    fun subscriptionIdRejectsZero() {
        assertFailsWith<IllegalArgumentException> {
            PublishProperties(subscriptionIdentifiers = listOf(0))
        }
    }

    @Test
    fun subscriptionIdRejectsOverflow() {
        assertFailsWith<IllegalArgumentException> {
            PublishProperties(subscriptionIdentifiers = listOf(268_435_456))
        }
    }
}
