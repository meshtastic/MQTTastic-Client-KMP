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
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * JVM-only checks that the caller's TLS hook mutates the same [TLSConfigBuilder] ktor
 * consumes, using `trustManager` — a property that exists only on the JVM and Android
 * actuals of [TLSConfigBuilder], and so cannot be asserted from `commonTest`.
 *
 * On the JVM, `configurePlatformTrust` is a no-op, so a hook-installed trust manager should
 * survive `applyMqttTls` untouched. On Android it is deliberately *not* preserved by
 * identity — it gets wrapped in a hostname-aware delegate. That wrapping needs the
 * `X509TrustManagerExtensions` framework class and is therefore out of unit-test scope.
 */
class TcpTransportTrustManagerTest {
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
    fun hookInstalledTrustManagerSurvivesApplyMqttTls() {
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("broker.example.com", configureTls = { trustManager = FakeTrustManager })
        assertSame(FakeTrustManager, builder.trustManager)
    }

    @Test
    fun hookInstalledTrustManagerSurvivesForIpLiteralHost() {
        // SNI is suppressed for IP literals, but the hook must still be applied — the
        // earlier SNI/trust coupling bug (Meshtastic-Android #5894) was exactly this
        // class of mistake.
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("192.168.1.50", configureTls = { trustManager = FakeTrustManager })
        assertSame(FakeTrustManager, builder.trustManager)
    }

    @Test
    fun hookWriteToTrustManagerTakesEffect() {
        // The lambda runs against the live builder and its write survives the rest of
        // applyMqttTls. This does not assert the hook/platform-trust ordering — on the JVM
        // configurePlatformTrust is a no-op, so that ordering is not observable here.
        var ranWithBuilder = false
        val builder = TLSConfigBuilder()
        builder.applyMqttTls(
            "broker.example.com",
            configureTls = {
                ranWithBuilder = true
                trustManager = FakeTrustManager
            },
        )
        assertNotNull(builder.trustManager)
        assertSame(FakeTrustManager, builder.trustManager)
        kotlin.test.assertTrue(ranWithBuilder)
    }

    @Test
    fun factoryDoesNotInvokeHookAtCreateTime() {
        // The hook belongs to the handshake, not to transport construction.
        var invocations = 0
        val factory = TcpTransportFactory { invocations++ }
        // create() must not run the hook — that happens during the handshake.
        factory.create(
            org.meshtastic.mqtt.MqttEndpoint
                .Tcp("broker.example.com", 8883, tls = true),
        )
        kotlin.test.assertEquals(0, invocations)
    }
}
