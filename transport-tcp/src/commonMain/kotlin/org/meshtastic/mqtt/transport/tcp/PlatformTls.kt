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
package org.meshtastic.mqtt.transport.tcp

import io.ktor.network.tls.TLSConfigBuilder

/**
 * Applies platform-specific TLS trust configuration to the [TLSConfigBuilder].
 *
 * On Android, this installs a hostname-aware trust manager that satisfies
 * `NetworkSecurityTrustManager`'s requirement for the 3-arg
 * `checkServerTrusted(chain, authType, hostname)` overload when
 * `network_security_config.xml` contains domain-specific configurations.
 * The platform throws from the 2-arg overload whenever *any* domain-specific
 * config is present — regardless of the target host — so this must run for
 * IP-literal brokers too, not only DNS hostnames.
 *
 * On JVM and native targets, this is a no-op — the platform default suffices.
 *
 * **Sibling copy.** The same logic is duplicated in
 * `transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.kt` and its
 * actuals, because `:transport-ws` cannot depend on this module. **Any fix here must be applied
 * there too, and vice versa.**
 *
 * @param host the broker host (DNS name or IP literal) used for trust evaluation.
 *   Unlike the SNI server name, this is never `null`: an IP literal is a valid
 *   host for the hostname-aware trust check even though it must not be sent as SNI.
 */
internal expect fun TLSConfigBuilder.configurePlatformTrust(host: String)

/**
 * Applies the MQTT TLS configuration to this [TLSConfigBuilder] in the one correct order.
 *
 * This is the single call site for TLS setup, so the ordering below cannot drift:
 *
 * 1. `serverName` — the SNI value, `null` for IP literals (RFC 6066 §3 forbids them).
 * 2. [configureTls] — the caller's hook, so it can read or override the SNI value and
 *    install its own trust manager (e.g. a private CA).
 * 3. [configurePlatformTrust] — reads whatever trust manager is now on the builder and,
 *    on Android, wraps it in a hostname-aware delegate.
 *
 * Step 2 must precede step 3. What step 3 provides is the call path: whatever trust manager is
 * on the builder is reached through Android's hostname-aware
 * `checkServerTrusted(chain, authType, hostname)` overload, which `NetworkSecurityTrustManager`
 * requires whenever `network_security_config.xml` holds any domain-specific configuration.
 * Reversing the two steps would discard that wrapper, leaving ktor's 2-arg call to hit the bare
 * manager.
 *
 * Be precise about what this ordering does *not* provide. Installing a trust manager via
 * [configureTls] *replaces the platform's trust decision*: that manager's anchors are used
 * instead of the platform's, and `network_security_config.xml` anchors, certificate pinning,
 * and Certificate Transparency policy are then enforced only insofar as that manager enforces
 * them itself. Those platform policies apply as they did before only when the caller leaves
 * `trustManager` unset. Wrapping preserves the hostname-aware *call path*, not the platform's
 * *policy*.
 *
 * RFC 6125 subject-name matching (verifying the certificate actually names the host being
 * contacted) is separate again: it comes from ktor, which performs it only when `serverName` is
 * non-`null` — so it is absent for IP-literal brokers. Android's 3-arg overload uses the
 * hostname for config lookup, pinning, and CT policy, not for subject-name matching. On JVM and
 * native targets [configurePlatformTrust] is a no-op, so no platform wrapping happens at all.
 *
 * The hook may read or replace `serverName`, but setting it to `null` disables ktor's
 * subject-name verification entirely — the only name matching this transport has on JVM and
 * native — so do not do that unless you verify the peer identity yourself.
 *
 * @param host the broker host (DNS name or IP literal), used for both SNI and trust evaluation.
 * @param configureTls optional caller customisation; `null` preserves the default behaviour.
 * @param platformTrust test seam for [configurePlatformTrust]; production callers use the default.
 */
internal fun TLSConfigBuilder.applyMqttTls(
    host: String,
    configureTls: (TLSConfigBuilder.() -> Unit)? = null,
    platformTrust: TLSConfigBuilder.(String) -> Unit = { configurePlatformTrust(it) },
) {
    serverName = sniServerName(host)
    configureTls?.invoke(this)
    platformTrust(host)
}
