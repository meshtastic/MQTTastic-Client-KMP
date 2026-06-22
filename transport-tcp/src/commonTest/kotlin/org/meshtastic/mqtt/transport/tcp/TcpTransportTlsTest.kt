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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the SNI-vs-trust-host decoupling for TLS connections.
 *
 * An IP-literal broker must suppress SNI ([sniServerName] returns `null`, per
 * RFC 6066 §3) but still pass its raw host to the platform trust manager. The
 * earlier regression skipped the hostname-aware trust manager whenever SNI was
 * suppressed, so IP-literal brokers on Android hit
 * `NetworkSecurityTrustManager`'s 2-arg `checkServerTrusted` and failed with
 * "Domain specific configurations require that hostname aware
 * checkServerTrusted(...) is used" (Meshtastic-Android #5894).
 */
class TcpTransportTlsTest {
    @Test
    fun ipv4LiteralSuppressesSni() {
        assertNull(sniServerName("192.168.1.50"))
        assertNull(sniServerName("10.0.0.1"))
        assertNull(sniServerName("127.0.0.1"))
    }

    @Test
    fun ipv6LiteralSuppressesSni() {
        assertNull(sniServerName("::1"))
        assertNull(sniServerName("2001:db8::1"))
    }

    @Test
    fun dnsHostnameKeepsSni() {
        assertEquals("broker.example.com", sniServerName("broker.example.com"))
        assertEquals("mqtt.local", sniServerName("mqtt.local"))
    }

    @Test
    fun isIpLiteralClassifiesHosts() {
        assertTrue(isIpLiteral("192.168.1.1"))
        assertTrue(isIpLiteral("::1"))
        assertTrue(isIpLiteral("2001:db8::1"))
        assertFalse(isIpLiteral("example.com"))
        assertFalse(isIpLiteral("a1.example.com"))
        assertFalse(isIpLiteral("mqtt.local"))
    }
}
