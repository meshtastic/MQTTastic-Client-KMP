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
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.tls.TLSConfigBuilder

/**
 * CIO engine — JVM, Android, Apple, and Linux.
 *
 * The caller's hook is applied to CIO's `https` [TLSConfigBuilder], which is the same builder
 * type `:transport-tcp` configures. `serverName` is deliberately not set here: the CIO *client*
 * derives SNI from the request URL when it opens the connection, whereas this block runs once at
 * client construction. A `serverName` a caller does assign inside the hook is *not* overwritten by
 * CIO's per-request SNI — it wins, and ktor verifies the certificate's subject names against it, so
 * the value must be present among the broker certificate's subject names (verified by
 * `WebSocketPrivateCaTest.serverNameSetInsideTheHookWinsOverCioRequestSni`).
 */
internal actual fun buildWsHttpClient(
    host: String,
    maxFrameSize: Long,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
): HttpClient =
    HttpClient(CIO) {
        install(WebSockets) {
            this.maxFrameSize = maxFrameSize
        }
        engine {
            https {
                applyWsTls(host, configureTls)
            }
        }
    }
