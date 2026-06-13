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
package org.meshtastic.mqtt.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/**
 * Architectural invariants for the `:core` module, enforced with
 * [Konsist](https://docs.konsist.lemonappdev.com). These catch design drift on the introducing PR
 * — with a readable failure — rather than waiting for an ABI dump diff (`apiCheck`) to notice a leak.
 *
 * Konsist scans source files for the whole build, so every rule is scoped to `/core/src/…` to keep
 * `:core`'s contract independent of the transport modules. Mirrors AGENTS.md §Rules and the ADRs:
 *  - `:core` `commonMain` has zero platform-API bleed (no `java.`/`javax.`/`android.`/`platform.`).
 *  - The codec internals stay `internal`: `MqttProperties`, the sealed `MqttPacket`, and every
 *    packet implementation (which must also be an immutable `data class`).
 *  - Every public top-level type in `:core` is on an explicit allowlist (ADR-0008).
 *
 * Note: there is intentionally no "no `println`" rule — [org.meshtastic.mqtt.MqttLogger.println]
 * is a legitimate println-backed logger factory. Logging discipline is the `MqttLogger` abstraction,
 * not a blanket ban.
 */
class ArchitectureTest {
    private val coreCommonMainFiles by lazy {
        Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("/core/src/commonMain/") }
    }

    @Test
    fun `core commonMain does not import java_ APIs`() {
        coreCommonMainFiles.assertFalse { file ->
            file.imports.any { import ->
                import.name.startsWith("java.") ||
                    import.name.startsWith("javax.")
            }
        }
    }

    @Test
    fun `core commonMain does not import android_ APIs`() {
        coreCommonMainFiles.assertFalse { file ->
            file.imports.any { import -> import.name.startsWith("android.") }
        }
    }

    @Test
    fun `core commonMain does not import kotlin native platform_ APIs`() {
        coreCommonMainFiles.assertFalse { file ->
            file.imports.any { import -> import.name.startsWith("platform.") }
        }
    }

    @Test
    fun `MqttProperties stays internal`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .filter { it.name == "MqttProperties" }
            .assertTrue { it.hasInternalModifier }
    }

    @Test
    fun `MqttPacket sealed interface stays internal`() {
        Konsist
            .scopeFromProduction()
            .interfaces()
            .filter { it.name == "MqttPacket" }
            .assertTrue { it.hasInternalModifier }
    }

    @Test
    fun `MqttPacket implementations stay internal`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .filter { it.hasParentWithName("MqttPacket") }
            .assertTrue { it.hasInternalModifier }
    }

    @Test
    fun `MqttPacket implementations are data classes`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .filter { it.hasParentWithName("MqttPacket") }
            .assertTrue { it.hasDataModifier }
    }

    @Test
    fun `MqttPacket implementation fields are immutable`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .filter { it.hasParentWithName("MqttPacket") }
            .flatMap { it.properties() }
            .assertTrue { it.hasValModifier }
    }

    @Test
    fun `core public top-level classes are on the allowlist`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .filter { it.path.contains("/core/src/") && it.isTopLevel && !it.hasInternalModifier && !it.hasPrivateModifier }
            .assertTrue { it.name in PUBLIC_API_ALLOWLIST }
    }

    @Test
    fun `core public top-level interfaces are on the allowlist`() {
        Konsist
            .scopeFromProduction()
            .interfaces()
            .filter { it.path.contains("/core/src/") && it.isTopLevel && !it.hasInternalModifier && !it.hasPrivateModifier }
            .assertTrue { it.name in PUBLIC_API_ALLOWLIST }
    }

    @Test
    fun `core public top-level objects are on the allowlist`() {
        Konsist
            .scopeFromProduction()
            .objects()
            .filter { it.path.contains("/core/src/") && it.isTopLevel && !it.hasInternalModifier && !it.hasPrivateModifier }
            .assertTrue { it.name in PUBLIC_API_ALLOWLIST }
    }

    private companion object {
        /**
         * The complete set of public top-level types `:core` exposes. Promoting a new type to
         * `public` is a deliberate SemVer commitment — add it here in the same PR, or mark it
         * `internal`. The transport SPI ([org.meshtastic.mqtt.MqttTransport] /
         * [org.meshtastic.mqtt.MqttTransportFactory]) and the VBI framing helper
         * ([org.meshtastic.mqtt.packet.VariableByteInt]) are public on purpose — see ADR-0006/0008.
         */
        val PUBLIC_API_ALLOWLIST =
            setOf(
                "AuthChallenge",
                "ConnectionState",
                "MqttClient",
                "MqttConfig",
                "MqttDsl",
                "MqttEndpoint",
                "MqttException",
                "MqttLogLevel",
                "MqttLogger",
                "MqttMessage",
                "MqttProtocolVersion",
                "MqttTransport",
                "MqttTransportFactory",
                "ProbeResult",
                "ProbeServerInfo",
                "PublishProperties",
                "QoS",
                "ReasonCode",
                "RetainHandling",
                "Subscription",
                "VariableByteInt",
                "VbiResult",
                "WillConfig",
            )
    }
}
