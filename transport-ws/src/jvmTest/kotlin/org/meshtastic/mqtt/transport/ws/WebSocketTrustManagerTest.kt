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
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * JVM-only checks on the ordering inside [applyWsTls], using `trustManager` — a property that
 * exists only on the JVM and Android actuals of [TLSConfigBuilder], so it cannot be asserted
 * from `commonTest`.
 *
 * On the JVM `configurePlatformTrust` is a no-op, so a hook-installed trust manager survives
 * untouched. On Android it is deliberately *not* preserved by identity — it gets wrapped in a
 * hostname-aware delegate, which needs the `X509TrustManagerExtensions` framework class and is
 * therefore out of unit-test scope. The ordering itself is asserted here via the
 * `platformTrust` test seam.
 */
class WebSocketTrustManagerTest {
    private object FakeTrustManager : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun hookInstalledTrustManagerSurvivesApplyWsTls() {
        val builder = TLSConfigBuilder()
        builder.applyWsTls("broker.example.com", configureTls = { trustManager = FakeTrustManager })
        assertSame(FakeTrustManager, builder.trustManager)
    }

    @Test
    fun callerHookRunsBeforePlatformTrust() {
        // The Android wrapper reads whatever trustManager is on the builder, so the caller's
        // assignment must already be present when platform trust runs. Reversing the two would
        // silently drop Android's hostname-aware checkServerTrusted path.
        val order = mutableListOf<String>()
        val builder = TLSConfigBuilder()
        builder.applyWsTls(
            host = "broker.example.com",
            configureTls = { order += "caller" },
            platformTrust = { order += "platform" },
        )
        assertEquals(listOf("caller", "platform"), order)
    }

    @Test
    fun platformTrustReceivesTheHostUnchanged() {
        // Includes the IP-literal case: Android's 2-arg overload throws whenever the security
        // config holds any domain-specific entry, regardless of target host, so an IP-only
        // broker needs the wrapper too.
        val seen = mutableListOf<String>()
        TLSConfigBuilder().applyWsTls("192.168.1.50", null) { seen += it }
        assertEquals(listOf("192.168.1.50"), seen)
    }

    @Test
    fun nullHookLeavesExistingTrustStateUntouched() {
        // Asserts this module's contract — a null hook mutates no trust state — rather than
        // TLSConfigBuilder's default `trustManager` value, which is ktor's implementation
        // detail and could change without our behaviour changing. Starting from a known
        // non-default state also catches a hook path that clears the manager rather than
        // leaving it alone. On the JVM `configurePlatformTrust` is a no-op, so nothing else
        // in `applyWsTls` should touch it either.
        val builder = TLSConfigBuilder()
        builder.trustManager = FakeTrustManager
        builder.applyWsTls("broker.example.com", null)
        assertSame(FakeTrustManager, builder.trustManager)
    }
}
