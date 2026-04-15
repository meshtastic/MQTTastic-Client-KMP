package org.meshtastic.mqtt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MqttMessageTest {
    @Test
    fun payloadIsDefensivelyCopied() {
        val original = byteArrayOf(1, 2, 3)
        val message = MqttMessage(topic = "test", payload = original)

        // Mutate the original — message should not be affected
        original[0] = 99
        assertEquals(1, message.payload[0])
    }

    @Test
    fun payloadCopyReturnsIndependentArray() {
        val message = MqttMessage(topic = "test", payload = byteArrayOf(1, 2, 3))
        val copy = message.payloadCopy()

        copy[0] = 99
        assertEquals(1, message.payload[0])
    }

    @Test
    fun equalityIgnoresIdentity() {
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
                payload = byteArrayOf(),
                properties = PublishProperties(contentType = "text/plain"),
            )
        val b =
            MqttMessage(
                topic = "t",
                payload = byteArrayOf(),
                properties = PublishProperties(contentType = "application/json"),
            )
        assertNotEquals(a, b)
    }

    @Test
    fun publishPropertiesEquality() {
        val a =
            PublishProperties(
                correlationData = byteArrayOf(1, 2, 3),
                userProperties = listOf("key" to "value"),
            )
        val b =
            PublishProperties(
                correlationData = byteArrayOf(1, 2, 3),
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
}
