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
package org.meshtastic.mqtt.packet

import org.meshtastic.mqtt.QoS

/**
 * Sealed base class for all 15 MQTT 5.0 packet types.
 *
 * Each subclass is a pure data container — encode/decode logic lives in separate functions.
 */
internal sealed class MqttPacket {
    /** The MQTT 5.0 control packet type. */
    abstract val packetType: PacketType

    /** MQTT 5.0 properties associated with this packet. */
    abstract val properties: MqttProperties
}

// --- §3.1 CONNECT ---

/** CONNECT packet — client requests a connection to the server (§3.1). */
@Suppress("LongParameterList")
internal data class Connect(
    val protocolName: String = "MQTT",
    val protocolLevel: Int = 5,
    val cleanStart: Boolean = true,
    val keepAliveSeconds: Int = 0,
    val clientId: String = "",
    val willTopic: String? = null,
    val willPayload: ByteArray? = null,
    val willQos: QoS = QoS.AT_MOST_ONCE,
    val willRetain: Boolean = false,
    val willProperties: MqttProperties = MqttProperties.EMPTY,
    val username: String? = null,
    val password: ByteArray? = null,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.CONNECT

    init {
        require(protocolLevel == 5) { "Only MQTT 5.0 is supported (protocolLevel must be 5)" }
        require(keepAliveSeconds in 0..65535) { "keepAliveSeconds must be 0..65535" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Connect) return false
        return protocolName == other.protocolName &&
            protocolLevel == other.protocolLevel &&
            cleanStart == other.cleanStart &&
            keepAliveSeconds == other.keepAliveSeconds &&
            clientId == other.clientId &&
            willTopic == other.willTopic &&
            willPayload.contentEqualsNullable(other.willPayload) &&
            willQos == other.willQos &&
            willRetain == other.willRetain &&
            willProperties == other.willProperties &&
            username == other.username &&
            password.contentEqualsNullable(other.password) &&
            properties == other.properties
    }

    override fun hashCode(): Int {
        var result = protocolName.hashCode()
        result = 31 * result + protocolLevel
        result = 31 * result + cleanStart.hashCode()
        result = 31 * result + keepAliveSeconds
        result = 31 * result + clientId.hashCode()
        result = 31 * result + (willTopic?.hashCode() ?: 0)
        result = 31 * result + (willPayload?.contentHashCode() ?: 0)
        result = 31 * result + willQos.hashCode()
        result = 31 * result + willRetain.hashCode()
        result = 31 * result + willProperties.hashCode()
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (password?.contentHashCode() ?: 0)
        result = 31 * result + properties.hashCode()
        return result
    }
}

// --- §3.2 CONNACK ---

/** CONNACK packet — server acknowledges a connection request (§3.2). */
internal data class ConnAck(
    val sessionPresent: Boolean = false,
    val reasonCode: ReasonCode = ReasonCode.SUCCESS,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.CONNACK
}

// --- §3.3 PUBLISH ---

/** PUBLISH packet — publish a message to a topic (§3.3). */
internal data class Publish(
    val dup: Boolean = false,
    val qos: QoS = QoS.AT_MOST_ONCE,
    val retain: Boolean = false,
    val topicName: String,
    val packetIdentifier: Int? = null,
    val payload: ByteArray = byteArrayOf(),
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.PUBLISH

    init {
        if (qos != QoS.AT_MOST_ONCE) {
            requireNotNull(packetIdentifier) {
                "packetIdentifier is required for QoS ${qos.value}"
            }
            require(packetIdentifier in 1..65535) {
                "packetIdentifier must be 1..65535"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Publish) return false
        return dup == other.dup &&
            qos == other.qos &&
            retain == other.retain &&
            topicName == other.topicName &&
            packetIdentifier == other.packetIdentifier &&
            payload.contentEquals(other.payload) &&
            properties == other.properties
    }

    override fun hashCode(): Int {
        var result = dup.hashCode()
        result = 31 * result + qos.hashCode()
        result = 31 * result + retain.hashCode()
        result = 31 * result + topicName.hashCode()
        result = 31 * result + (packetIdentifier ?: 0)
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + properties.hashCode()
        return result
    }
}

// --- §3.4 PUBACK ---

/** PUBACK packet — QoS 1 publish acknowledgement (§3.4). */
internal data class PubAck(
    val packetIdentifier: Int,
    val reasonCode: ReasonCode = ReasonCode.SUCCESS,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.PUBACK

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
    }
}

// --- §3.5 PUBREC ---

/** PUBREC packet — QoS 2 publish received, step 1 (§3.5). */
internal data class PubRec(
    val packetIdentifier: Int,
    val reasonCode: ReasonCode = ReasonCode.SUCCESS,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.PUBREC

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
    }
}

// --- §3.6 PUBREL ---

/** PUBREL packet — QoS 2 publish release, step 2 (§3.6). */
internal data class PubRel(
    val packetIdentifier: Int,
    val reasonCode: ReasonCode = ReasonCode.SUCCESS,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.PUBREL

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
    }
}

// --- §3.7 PUBCOMP ---

/** PUBCOMP packet — QoS 2 publish complete, step 3 (§3.7). */
internal data class PubComp(
    val packetIdentifier: Int,
    val reasonCode: ReasonCode = ReasonCode.SUCCESS,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.PUBCOMP

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
    }
}

// --- §3.8 SUBSCRIBE ---

/**
 * Controls when the broker sends retained messages for a subscription (§3.8.3.1).
 *
 * When subscribing to a topic that has a retained message, this option controls
 * whether and when that retained message is delivered to the subscribing client.
 *
 * @property value The numeric option value (0, 1, or 2) as encoded in the SUBSCRIBE packet.
 */
public enum class RetainHandling(
    public val value: Int,
) {
    /**
     * Send retained messages at the time of the subscribe (default).
     *
     * The broker delivers any retained message for the matching topic immediately
     * when this subscription is established, regardless of whether a prior
     * subscription existed.
     */
    SEND_AT_SUBSCRIBE(0),

    /**
     * Send retained messages only if the subscription does not already exist.
     *
     * The broker delivers retained messages only on the first subscribe for a topic.
     * Re-subscribing to an already-active topic does not re-trigger delivery.
     */
    SEND_IF_NEW_SUBSCRIPTION(1),

    /**
     * Do not send retained messages at the time of the subscribe.
     *
     * Retained messages are never sent when the subscription is established.
     * The client only receives messages published after the subscription is active.
     */
    DO_NOT_SEND(2),
    ;

    public companion object {
        /**
         * Returns the [RetainHandling] corresponding to the given numeric [value].
         *
         * @param value The retain handling option (0, 1, or 2).
         * @throws IllegalArgumentException if [value] is not a valid retain handling option.
         */
        public fun fromValue(value: Int): RetainHandling =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown retain handling: $value")
    }
}

/** A single topic subscription with subscription options per §3.8.3.1. */
internal data class Subscription(
    val topicFilter: String,
    val maxQos: QoS = QoS.AT_MOST_ONCE,
    val noLocal: Boolean = false,
    val retainAsPublished: Boolean = false,
    val retainHandling: RetainHandling = RetainHandling.SEND_AT_SUBSCRIBE,
)

/** SUBSCRIBE packet — client subscribes to topics (§3.8). */
internal data class Subscribe(
    val packetIdentifier: Int,
    val subscriptions: List<Subscription>,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.SUBSCRIBE

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
        require(subscriptions.isNotEmpty()) { "subscriptions must not be empty" }
    }
}

// --- §3.9 SUBACK ---

/** SUBACK packet — server acknowledges a subscribe request (§3.9). */
internal data class SubAck(
    val packetIdentifier: Int,
    val reasonCodes: List<ReasonCode>,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.SUBACK

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
        require(reasonCodes.isNotEmpty()) { "reasonCodes must not be empty" }
    }
}

// --- §3.10 UNSUBSCRIBE ---

/** UNSUBSCRIBE packet — client unsubscribes from topics (§3.10). */
internal data class Unsubscribe(
    val packetIdentifier: Int,
    val topicFilters: List<String>,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.UNSUBSCRIBE

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
        require(topicFilters.isNotEmpty()) { "topicFilters must not be empty" }
    }
}

// --- §3.11 UNSUBACK ---

/** UNSUBACK packet — server acknowledges an unsubscribe request (§3.11). */
internal data class UnsubAck(
    val packetIdentifier: Int,
    val reasonCodes: List<ReasonCode>,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.UNSUBACK

    init {
        require(packetIdentifier in 1..65535) { "packetIdentifier must be 1..65535" }
        require(reasonCodes.isNotEmpty()) { "reasonCodes must not be empty" }
    }
}

// --- §3.12 PINGREQ ---

/** PINGREQ packet — client keepalive ping (§3.12). No variable header or payload. */
internal data object PingReq : MqttPacket() {
    override val packetType: PacketType get() = PacketType.PINGREQ
    override val properties: MqttProperties get() = MqttProperties.EMPTY
}

// --- §3.13 PINGRESP ---

/** PINGRESP packet — server keepalive ping response (§3.13). No variable header or payload. */
internal data object PingResp : MqttPacket() {
    override val packetType: PacketType get() = PacketType.PINGRESP
    override val properties: MqttProperties get() = MqttProperties.EMPTY
}

// --- §3.14 DISCONNECT ---

/** DISCONNECT packet — disconnect notification (§3.14). */
internal data class Disconnect(
    val reasonCode: ReasonCode = ReasonCode.SUCCESS,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.DISCONNECT
}

// --- §3.15 AUTH ---

/** AUTH packet — extended authentication exchange (§3.15). */
internal data class Auth(
    val reasonCode: ReasonCode = ReasonCode.SUCCESS,
    override val properties: MqttProperties = MqttProperties.EMPTY,
) : MqttPacket() {
    override val packetType: PacketType get() = PacketType.AUTH
}

// --- Utility ---

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this === other -> true
        this == null || other == null -> false
        else -> this.contentEquals(other)
    }
