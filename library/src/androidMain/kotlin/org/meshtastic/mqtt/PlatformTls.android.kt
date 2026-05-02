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
 */
internal actual fun TLSConfigBuilder.configurePlatformTrust(serverName: String?) {
    if (serverName == null) return

    val baseTm =
        (trustManager as? X509TrustManager) ?: run {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

    trustManager = HostnameAwareTrustManager(baseTm, serverName)
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
