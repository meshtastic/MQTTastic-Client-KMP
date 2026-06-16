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
 * Creates the [MqttTransport] for a given [MqttEndpoint].
 *
 * This is the seam that lets `:core` stay free of any concrete transport dependency: each
 * transport module (`mqtt-client-transport-tcp`, `mqtt-client-transport-ws`) ships a factory,
 * and the consumer supplies one via [MqttConfig.Builder.transportFactory]. Factories that
 * handle different endpoint types can be combined with [plus]:
 *
 * ```kotlin
 * val client = MqttClient("sensor") {
 *     transportFactory = TcpTransportFactory() + WebSocketTransportFactory()
 * }
 * ```
 */
public interface MqttTransportFactory {
    /** Whether this factory can create a transport for [endpoint]. */
    public fun supports(endpoint: MqttEndpoint): Boolean

    /**
     * Create a fresh, unconnected [MqttTransport] for [endpoint].
     *
     * Called once per connection attempt (including reconnects and server redirects), so the
     * returned transport must be a new instance each time.
     */
    public fun create(endpoint: MqttEndpoint): MqttTransport
}

/**
 * Combine two factories into one that delegates to whichever [supports][MqttTransportFactory.supports]
 * the endpoint, trying the left-hand factory first. Use this to accept more than one endpoint type
 * (e.g. both TCP and WebSocket) from a single client.
 */
public operator fun MqttTransportFactory.plus(other: MqttTransportFactory): MqttTransportFactory {
    val left = (this as? CompositeMqttTransportFactory)?.factories ?: listOf(this)
    val right = (other as? CompositeMqttTransportFactory)?.factories ?: listOf(other)
    return CompositeMqttTransportFactory(left + right)
}

/** Tries each delegate in order; the first whose `supports` returns `true` builds the transport. */
internal class CompositeMqttTransportFactory(
    val factories: List<MqttTransportFactory>,
) : MqttTransportFactory {
    override fun supports(endpoint: MqttEndpoint): Boolean = factories.any { it.supports(endpoint) }

    override fun create(endpoint: MqttEndpoint): MqttTransport =
        factories.firstOrNull { it.supports(endpoint) }?.create(endpoint)
            ?: throw IllegalArgumentException(
                "No registered MqttTransportFactory supports endpoint: $endpoint",
            )
}
