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

/**
 * MQTT 5.0 reason codes per §2.4.
 *
 * Reason codes indicate the result of an operation. They appear in CONNACK, PUBACK,
 * PUBREC, PUBREL, PUBCOMP, SUBACK, UNSUBACK, DISCONNECT, and AUTH packets.
 *
 * Success codes are in the range 0x00..0x1F. Error codes are 0x80 and above.
 * The [SUCCESS] code (0x00) is overloaded — it also represents "Normal Disconnection"
 * in DISCONNECT and "Granted QoS 0" in SUBACK.
 *
 * @property value The single-byte reason code value as defined in the MQTT 5.0 specification.
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901031">§2.4 Reason Code</a>
 */
public enum class ReasonCode(
    public val value: Int,
) {
    /** Operation completed successfully. Also used for normal disconnection and Granted QoS 0. */
    SUCCESS(0x00),

    /** The subscription is accepted with QoS 1 as the maximum QoS level. */
    GRANTED_QOS_1(0x01),

    /** The subscription is accepted with QoS 2 as the maximum QoS level. */
    GRANTED_QOS_2(0x02),

    /** The client wishes to disconnect but requires the server to publish its Will Message. */
    DISCONNECT_WITH_WILL(0x04),

    /** The message is accepted but there are no matching subscribers. */
    NO_MATCHING_SUBSCRIBERS(0x10),

    /** No matching subscription existed for the topic filter in the UNSUBSCRIBE. */
    NO_SUBSCRIPTION_EXISTED(0x11),

    /** Continue the authentication with another step. */
    CONTINUE_AUTHENTICATION(0x18),

    /** Initiate a re-authentication. */
    RE_AUTHENTICATE(0x19),

    /** The server or client does not wish to reveal the reason for the failure. */
    UNSPECIFIED_ERROR(0x80),

    /** Data within the packet could not be correctly parsed. */
    MALFORMED_PACKET(0x81),

    /** Data in the packet does not conform to this specification. */
    PROTOCOL_ERROR(0x82),

    /** The packet is valid but not accepted by the implementation. */
    IMPLEMENTATION_SPECIFIC_ERROR(0x83),

    /** The server does not support the requested MQTT protocol version. */
    UNSUPPORTED_PROTOCOL_VERSION(0x84),

    /** The Client Identifier is not valid. */
    CLIENT_IDENTIFIER_NOT_VALID(0x85),

    /** The server does not accept the user name or password. */
    BAD_USER_NAME_OR_PASSWORD(0x86),

    /** The client is not authorized to perform this operation. */
    NOT_AUTHORIZED(0x87),

    /** The MQTT server is not available. */
    SERVER_UNAVAILABLE(0x88),

    /** The server is busy; try again later. */
    SERVER_BUSY(0x89),

    /** The client has been banned by administrative action. */
    BANNED(0x8A),

    /** The server is shutting down. */
    SERVER_SHUTTING_DOWN(0x8B),

    /** The authentication method is not supported or does not match the current method. */
    BAD_AUTHENTICATION_METHOD(0x8C),

    /** The connection is closed because no packet has been received within 1.5 times the keep alive. */
    KEEP_ALIVE_TIMEOUT(0x8D),

    /** Another connection using the same Client Identifier has connected. */
    SESSION_TAKEN_OVER(0x8E),

    /** The topic filter is correctly formed but not accepted by the server. */
    TOPIC_FILTER_INVALID(0x8F),

    /** The topic name is correctly formed but not accepted by the server or client. */
    TOPIC_NAME_INVALID(0x90),

    /** The specified packet identifier is already in use. */
    PACKET_IDENTIFIER_IN_USE(0x91),

    /** The packet identifier is not known (e.g., not part of an active QoS 2 flow). */
    PACKET_IDENTIFIER_NOT_FOUND(0x92),

    /** The client or server has received more than Receive Maximum concurrent QoS 1/2 messages. */
    RECEIVE_MAXIMUM_EXCEEDED(0x93),

    /** The topic alias is not valid. */
    TOPIC_ALIAS_INVALID(0x94),

    /** The packet exceeded the maximum permissible size. */
    PACKET_TOO_LARGE(0x95),

    /** The received data rate is too high. */
    MESSAGE_RATE_TOO_HIGH(0x96),

    /** An implementation or administrative imposed limit has been exceeded. */
    QUOTA_EXCEEDED(0x97),

    /** The connection is closed due to an administrative action. */
    ADMINISTRATIVE_ACTION(0x98),

    /** The payload format does not match the specified Payload Format Indicator. */
    PAYLOAD_FORMAT_INVALID(0x99),

    /** The server does not support retained messages. */
    RETAIN_NOT_SUPPORTED(0x9A),

    /** The server does not support the requested QoS level. */
    QOS_NOT_SUPPORTED(0x9B),

    /** The client should temporarily use another server. */
    USE_ANOTHER_SERVER(0x9C),

    /** The client should permanently use another server. */
    SERVER_MOVED(0x9D),

    /** The server does not support shared subscriptions. */
    SHARED_SUBSCRIPTIONS_NOT_SUPPORTED(0x9E),

    /** The connection rate limit has been exceeded. */
    CONNECTION_RATE_EXCEEDED(0x9F),

    /** The maximum connection time authorized for this connection has been exceeded. */
    MAXIMUM_CONNECT_TIME(0xA0),

    /** The server does not support subscription identifiers. */
    SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED(0xA1),

    /** The server does not support wildcard subscriptions. */
    WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED(0xA2),
    ;

    public companion object {
        /**
         * Returns the [ReasonCode] corresponding to the given [value].
         *
         * @throws IllegalArgumentException if [value] does not match any known reason code.
         */
        public fun fromValue(value: Int): ReasonCode =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown reason code: 0x${value.toString(16).uppercase().padStart(2, '0')}")
    }
}
