package org.meshtastic.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QoSTest {
    @Test
    fun fromValueReturnsCorrectQoS() {
        assertEquals(QoS.AT_MOST_ONCE, QoS.fromValue(0))
        assertEquals(QoS.AT_LEAST_ONCE, QoS.fromValue(1))
        assertEquals(QoS.EXACTLY_ONCE, QoS.fromValue(2))
    }

    @Test
    fun fromValueThrowsOnInvalid() {
        assertFailsWith<IllegalArgumentException> { QoS.fromValue(3) }
        assertFailsWith<IllegalArgumentException> { QoS.fromValue(-1) }
    }
}
