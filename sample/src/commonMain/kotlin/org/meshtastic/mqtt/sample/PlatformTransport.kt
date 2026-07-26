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
package org.meshtastic.mqtt.sample

import org.meshtastic.mqtt.MqttTransportFactory

/**
 * The set of transports usable on the current platform, combined into one factory.
 *
 * Android, desktop (JVM) and iOS get `TcpTransportFactory() + WebSocketTransportFactory()`,
 * so the sample accepts `tcp://`, `tls://`, `ws://` and `wss://` broker URIs. The browser
 * (wasmJs) target gets WebSockets only — `:transport-tcp` has no wasmJs variant because raw
 * TCP sockets are not reachable from a browser sandbox.
 */
internal expect fun platformTransportFactory(): MqttTransportFactory
