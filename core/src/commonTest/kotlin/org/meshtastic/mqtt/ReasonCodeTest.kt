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

class ReasonCodeTest {
    @Test
    fun fromValueReturnsCorrectCodeForAllValues() {
        assertEquals(ReasonCode.SUCCESS, ReasonCode.fromValue(0x00))
        assertEquals(ReasonCode.GRANTED_QOS_1, ReasonCode.fromValue(0x01))
        assertEquals(ReasonCode.GRANTED_QOS_2, ReasonCode.fromValue(0x02))
        assertEquals(ReasonCode.DISCONNECT_WITH_WILL, ReasonCode.fromValue(0x04))
        assertEquals(ReasonCode.NO_MATCHING_SUBSCRIBERS, ReasonCode.fromValue(0x10))
        assertEquals(ReasonCode.NO_SUBSCRIPTION_EXISTED, ReasonCode.fromValue(0x11))
        assertEquals(ReasonCode.CONTINUE_AUTHENTICATION, ReasonCode.fromValue(0x18))
        assertEquals(ReasonCode.RE_AUTHENTICATE, ReasonCode.fromValue(0x19))
        assertEquals(ReasonCode.UNSPECIFIED_ERROR, ReasonCode.fromValue(0x80))
        assertEquals(ReasonCode.MALFORMED_PACKET, ReasonCode.fromValue(0x81))
        assertEquals(ReasonCode.PROTOCOL_ERROR, ReasonCode.fromValue(0x82))
        assertEquals(ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR, ReasonCode.fromValue(0x83))
        assertEquals(ReasonCode.UNSUPPORTED_PROTOCOL_VERSION, ReasonCode.fromValue(0x84))
        assertEquals(ReasonCode.CLIENT_IDENTIFIER_NOT_VALID, ReasonCode.fromValue(0x85))
        assertEquals(ReasonCode.BAD_USER_NAME_OR_PASSWORD, ReasonCode.fromValue(0x86))
        assertEquals(ReasonCode.NOT_AUTHORIZED, ReasonCode.fromValue(0x87))
        assertEquals(ReasonCode.SERVER_UNAVAILABLE, ReasonCode.fromValue(0x88))
        assertEquals(ReasonCode.SERVER_BUSY, ReasonCode.fromValue(0x89))
        assertEquals(ReasonCode.BANNED, ReasonCode.fromValue(0x8A))
        assertEquals(ReasonCode.SERVER_SHUTTING_DOWN, ReasonCode.fromValue(0x8B))
        assertEquals(ReasonCode.BAD_AUTHENTICATION_METHOD, ReasonCode.fromValue(0x8C))
        assertEquals(ReasonCode.KEEP_ALIVE_TIMEOUT, ReasonCode.fromValue(0x8D))
        assertEquals(ReasonCode.SESSION_TAKEN_OVER, ReasonCode.fromValue(0x8E))
        assertEquals(ReasonCode.TOPIC_FILTER_INVALID, ReasonCode.fromValue(0x8F))
        assertEquals(ReasonCode.TOPIC_NAME_INVALID, ReasonCode.fromValue(0x90))
        assertEquals(ReasonCode.PACKET_IDENTIFIER_IN_USE, ReasonCode.fromValue(0x91))
        assertEquals(ReasonCode.PACKET_IDENTIFIER_NOT_FOUND, ReasonCode.fromValue(0x92))
        assertEquals(ReasonCode.RECEIVE_MAXIMUM_EXCEEDED, ReasonCode.fromValue(0x93))
        assertEquals(ReasonCode.TOPIC_ALIAS_INVALID, ReasonCode.fromValue(0x94))
        assertEquals(ReasonCode.PACKET_TOO_LARGE, ReasonCode.fromValue(0x95))
        assertEquals(ReasonCode.MESSAGE_RATE_TOO_HIGH, ReasonCode.fromValue(0x96))
        assertEquals(ReasonCode.QUOTA_EXCEEDED, ReasonCode.fromValue(0x97))
        assertEquals(ReasonCode.ADMINISTRATIVE_ACTION, ReasonCode.fromValue(0x98))
        assertEquals(ReasonCode.PAYLOAD_FORMAT_INVALID, ReasonCode.fromValue(0x99))
        assertEquals(ReasonCode.RETAIN_NOT_SUPPORTED, ReasonCode.fromValue(0x9A))
        assertEquals(ReasonCode.QOS_NOT_SUPPORTED, ReasonCode.fromValue(0x9B))
        assertEquals(ReasonCode.USE_ANOTHER_SERVER, ReasonCode.fromValue(0x9C))
        assertEquals(ReasonCode.SERVER_MOVED, ReasonCode.fromValue(0x9D))
        assertEquals(ReasonCode.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED, ReasonCode.fromValue(0x9E))
        assertEquals(ReasonCode.CONNECTION_RATE_EXCEEDED, ReasonCode.fromValue(0x9F))
        assertEquals(ReasonCode.MAXIMUM_CONNECT_TIME, ReasonCode.fromValue(0xA0))
        assertEquals(ReasonCode.SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED, ReasonCode.fromValue(0xA1))
        assertEquals(ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED, ReasonCode.fromValue(0xA2))
    }

    @Test
    fun fromValueThrowsOnUnknownValues() {
        assertFailsWith<IllegalArgumentException> { ReasonCode.fromValue(0x03) }
        assertFailsWith<IllegalArgumentException> { ReasonCode.fromValue(0xFF) }
        assertFailsWith<IllegalArgumentException> { ReasonCode.fromValue(-1) }
    }

    @Test
    fun valuePropertyReturnsCorrectByte() {
        assertEquals(0x00, ReasonCode.SUCCESS.value)
        assertEquals(0x80, ReasonCode.UNSPECIFIED_ERROR.value)
        assertEquals(0xA2, ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED.value)
    }

    @Test
    fun allEntriesHaveUniqueValues() {
        val values = ReasonCode.entries.map { it.value }
        assertEquals(values.size, values.toSet().size, "Duplicate reason code values detected")
    }

    @Test
    fun entryCountMatchesSpec() {
        assertEquals(43, ReasonCode.entries.size)
    }
}
