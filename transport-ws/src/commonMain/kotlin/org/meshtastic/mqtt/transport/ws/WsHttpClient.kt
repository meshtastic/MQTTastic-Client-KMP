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
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient

/**
 * Builds the [HttpClient] used for the WebSocket connection, with the WebSockets plugin
 * installed and a platform-appropriate engine selected.
 *
 * Engine auto-detection cannot be used here: configuring engine-level TLS requires naming
 * the engine, and a star-projected `HttpClientConfig<*>` cannot reach `engine { }` at all.
 *
 * @param maxFrameSize the WebSocket frame safety cap, in bytes.
 */
internal expect fun buildWsHttpClient(maxFrameSize: Long): HttpClient
