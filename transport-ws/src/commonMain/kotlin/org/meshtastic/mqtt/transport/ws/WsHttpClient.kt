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
import io.ktor.network.tls.TLSConfigBuilder

/**
 * Builds the [HttpClient] used for the WebSocket connection, with the WebSockets plugin
 * installed and a platform-appropriate engine selected.
 *
 * Engine auto-detection cannot be used here: configuring engine-level TLS requires naming
 * the engine, and a star-projected `HttpClientConfig<*>` cannot reach `engine { }` at all.
 *
 * [configureTls] is honoured only where the engine exposes a TLS configuration — CIO, on JVM,
 * Android, Apple, and Linux. On Windows (WinHttp) and in the browser (wasmJs / Js) it is
 * silently ignored: WinHttp exposes no TLS-config surface and the browser cannot influence
 * trust at all.
 *
 * @param host the broker host from the endpoint URL, used for trust evaluation.
 * @param maxFrameSize the WebSocket frame safety cap, in bytes.
 * @param configureTls optional caller customisation of the engine's TLS configuration.
 */
internal expect fun buildWsHttpClient(
    host: String,
    maxFrameSize: Long,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
): HttpClient
