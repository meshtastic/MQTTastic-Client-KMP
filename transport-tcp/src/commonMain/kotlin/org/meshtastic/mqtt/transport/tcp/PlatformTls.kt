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
 * @param host the broker host (DNS name or IP literal) used for trust evaluation.
 *   Unlike the SNI server name, this is never `null`: an IP literal is a valid
 *   host for the hostname-aware trust check even though it must not be sent as SNI.
 */
internal expect fun TLSConfigBuilder.configurePlatformTrust(host: String)
