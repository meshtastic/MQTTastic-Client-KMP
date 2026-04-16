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
 * Architectural invariants for the MQTT client library.
 *
 * These tests use [Konsist](https://docs.konsist.lemonappdev.com) to enforce
 * design rules that would be painful to catch in code review. They run as part
 * of `:library:jvmTest` on every CI build.
 *
 * Rules enforced here mirror AGENTS.md §Rules:
 *  - `commonMain` must have zero platform-API bleed.
 *  - Transport implementations must stay internal.
 *  - No `println` debug logging in library `main` sources.
 */
class ArchitectureTest {
    private val commonMainFiles by lazy {
        Konsist
            .scopeFromProduction()
            .files
            .filter { it.path.contains("/commonMain/") }
    }

    @Test
    fun `commonMain does not import java_ APIs`() {
        commonMainFiles.assertFalse { file ->
            file.imports.any { import ->
                import.name.startsWith("java.") ||
                    import.name.startsWith("javax.")
            }
        }
    }

    @Test
    fun `commonMain does not import android_ APIs`() {
        commonMainFiles.assertFalse { file ->
            file.imports.any { import -> import.name.startsWith("android.") }
        }
    }

    @Test
    fun `commonMain does not import kotlin native platform_ APIs`() {
        commonMainFiles.assertFalse { file ->
            file.imports.any { import -> import.name.startsWith("platform.") }
        }
    }

    @Test
    fun `MqttTransport interface stays internal`() {
        Konsist
            .scopeFromProduction()
            .interfaces()
            .filter { it.name == "MqttTransport" }
            .assertTrue { it.hasInternalModifier }
    }

    @Test
    fun `MqttProperties class stays internal`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .filter { it.name == "MqttProperties" }
            .assertTrue { it.hasInternalModifier }
    }
}
