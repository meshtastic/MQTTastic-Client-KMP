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
import org.meshtastic.mqtt.transport.ws.WebSocketTransportFactory

/**
 * WebSockets only. A browser cannot open a raw TCP socket, so `:transport-tcp` publishes no
 * wasmJs variant — the sample needs a `ws://` or `wss://` broker URI when run in the browser.
 */
internal actual fun platformTransportFactory(): MqttTransportFactory = WebSocketTransportFactory()
