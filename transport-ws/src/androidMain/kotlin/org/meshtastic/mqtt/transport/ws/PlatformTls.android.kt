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

import android.net.http.X509TrustManagerExtensions
import io.ktor.network.tls.TLSConfigBuilder
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * On Android, installs a hostname-aware [X509TrustManager] that delegates to the
 * platform default via [X509TrustManagerExtensions].
 *
 * Android's `NetworkSecurityTrustManager` (injected when `network_security_config.xml`
 * has domain-specific rules) throws from the standard 2-arg `checkServerTrusted` and
 * requires the 3-arg hostname-aware overload. Ktor's TLS handshake only calls the
 * 2-arg version, so we intercept and route through the extensions API.
 *
 * This runs for IP-literal brokers as well as DNS hostnames: the platform's 2-arg
 * overload throws whenever the config has *any* domain-specific entry, independent of
 * the target host, so an IP-only broker (a common private-broker setup) hits the same
 * failure. The [host] — an IP literal or DNS name — is a valid argument for the 3-arg
 * overload even though an IP must never be sent as the TLS SNI server name.
 *
 * **Constraint on caller-supplied trust managers.** Whatever [X509TrustManager] is on the
 * builder must be one Android can wrap for hostname-aware checking. In practice that means it
 * must come from a [TrustManagerFactory] (which yields the platform's `TrustManagerImpl`), or
 * it must itself declare a `checkServerTrusted(X509Certificate[], String, String)` method.
 * A hand-written `X509TrustManager` that implements only the two-arg overloads cannot be
 * wrapped, and this function throws [IllegalArgumentException] rather than silently dropping
 * Android's policy checks. To trust a private CA, load it into a [KeyStore] and initialise a
 * [TrustManagerFactory] with that store.
 *
 * Note that the hostname passed to the 3-arg overload drives network-security-config lookup,
 * certificate pinning, and Certificate Transparency policy — it does **not** perform RFC 6125
 * subject-name matching. That comes from ktor and only when the SNI server name is set.
 *
 * **Sibling copy** of `:transport-tcp`'s `PlatformTls.android.kt`. Any fix here must be applied
 * there too, and vice versa.
 */
internal actual fun TLSConfigBuilder.configurePlatformTrust(host: String) {
    if (host.isBlank()) return

    val baseTm =
        (trustManager as? X509TrustManager) ?: run {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

    trustManager =
        try {
            HostnameAwareTrustManager(baseTm, host)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Android cannot wrap the configured X509TrustManager (${baseTm::class.java.name}) " +
                    "for hostname-aware certificate checking. The trust manager must either be " +
                    "obtained from TrustManagerFactory (which yields the platform TrustManagerImpl) " +
                    "or declare checkServerTrusted(X509Certificate[], String, String). To trust a " +
                    "private CA, load it into a KeyStore and initialise a TrustManagerFactory with " +
                    "that KeyStore instead of hand-implementing X509TrustManager.",
                e,
            )
        }
}

/**
 * Wraps a platform [X509TrustManager] so that the 2-arg `checkServerTrusted` call
 * (which is all Ktor invokes) is routed through Android's hostname-aware
 * [X509TrustManagerExtensions.checkServerTrusted] overload.
 */
private class HostnameAwareTrustManager(
    private val delegate: X509TrustManager,
    private val hostname: String,
) : X509TrustManager {
    private val extensions = X509TrustManagerExtensions(delegate)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        // Route through the hostname-aware overload to satisfy NetworkSecurityTrustManager.
        extensions.checkServerTrusted(chain, authType, hostname)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
