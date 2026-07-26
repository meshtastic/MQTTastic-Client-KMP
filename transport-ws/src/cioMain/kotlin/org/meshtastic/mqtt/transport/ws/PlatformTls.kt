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

import io.ktor.network.tls.TLSConfigBuilder

/**
 * Applies platform-specific TLS trust configuration to the [TLSConfigBuilder].
 *
 * On Android, this installs a hostname-aware trust manager that satisfies
 * `NetworkSecurityTrustManager`'s requirement for the 3-arg
 * `checkServerTrusted(chain, authType, hostname)` overload when `network_security_config.xml`
 * contains domain-specific configurations. The platform throws from the 2-arg overload whenever
 * *any* domain-specific config is present — regardless of the target host — so this must run for
 * IP-literal brokers too, not only DNS hostnames.
 *
 * On JVM, Apple, and Linux this is a no-op — the platform default suffices, and non-JVM
 * `TLSConfigBuilder` has no `trustManager` property at all.
 *
 * **Sibling copy.** `:transport-ws` cannot depend on `:transport-tcp` — that module has no wasmJs
 * target, and WS-only consumers should not pull a TCP artifact — so this logic is deliberately
 * duplicated from
 * `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt` and its
 * per-platform actuals. **Any fix here must be applied there too, and vice versa.**
 *
 * @param host the broker host (DNS name or IP literal) used for trust evaluation.
 */
internal expect fun TLSConfigBuilder.configurePlatformTrust(host: String)

/**
 * Applies the MQTT TLS configuration to this [TLSConfigBuilder] in the one correct order:
 *
 * 1. [configureTls] — the caller's hook, so it can install its own trust manager (e.g. a private CA).
 * 2. [configurePlatformTrust] — reads whatever trust manager is now on the builder and, on Android,
 *    wraps it in a hostname-aware delegate.
 *
 * Step 1 must precede step 2. What step 2 provides is the call path: whatever trust manager is on
 * the builder is reached through Android's hostname-aware
 * `checkServerTrusted(chain, authType, hostname)` overload, which `NetworkSecurityTrustManager`
 * requires whenever `network_security_config.xml` holds any domain-specific configuration.
 * Reversing the two steps would discard that wrapper, leaving ktor's 2-arg call to hit the bare
 * manager.
 *
 * Be precise about what this ordering does *not* provide. Installing a trust manager via
 * [configureTls] *replaces the platform's trust decision*: that manager's anchors are used instead
 * of the platform's, and `network_security_config.xml` anchors, certificate pinning, and
 * Certificate Transparency policy are then enforced only insofar as that manager enforces them
 * itself. Wrapping preserves the hostname-aware *call path*, not the platform's *policy*.
 *
 * Unlike `:transport-tcp`'s `applyMqttTls`, this does not set `serverName`: the CIO client derives
 * SNI — and with it ktor's RFC 6125 subject-name check — from the request URL when it opens the
 * connection, whereas this runs once at client construction.
 *
 * @param host the broker host (DNS name or IP literal), used for trust evaluation.
 * @param configureTls optional caller customisation; `null` preserves the default behaviour.
 * @param platformTrust test seam for [configurePlatformTrust]; production callers use the default.
 */
internal fun TLSConfigBuilder.applyWsTls(
    host: String,
    configureTls: (TLSConfigBuilder.() -> Unit)? = null,
    platformTrust: TLSConfigBuilder.(String) -> Unit = { configurePlatformTrust(it) },
) {
    configureTls?.invoke(this)
    platformTrust(host)
}
