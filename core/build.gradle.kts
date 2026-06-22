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
plugins {
    id("mqtt.kmp.library")
    alias(libs.plugins.android.kotlin.multiplatform.library)
    id("mqtt.publishing")
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    alias(libs.plugins.bcv)
    alias(libs.plugins.kover)
}

// :core holds all protocol logic — packets, codec, client, connection, properties, and the
// MqttTransport / MqttTransportFactory SPI. It has zero dependency on any transport module.
kotlin {
    android {
        namespace = "org.meshtastic.mqtt"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        withHostTestBuilder {}
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.bytestring)
        }
        jvmTest.get().dependencies {
            implementation(libs.konsist)
            // Real-broker integration tests (env-gated by MQTT_INTEGRATION_TESTS) drive the client
            // over the real TCP transport. Test-only — does not affect :core's published deps, and
            // the module-boundary guard checks only main configurations.
            implementation(project(":transport-tcp"))
        }
    }
}

dokka {
    dokkaSourceSets.named("commonMain") {
        includes.from(layout.projectDirectory.file("Module.md"))
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
        licenseHeaderFile(rootProject.file("config/spotless/copyright.kt"))
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
        licenseHeaderFile(
            rootProject.file("config/spotless/copyright.kts"),
            "(^(?![\\/ ]\\*).*$)",
        )
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
}

// ---------------------------------------------------------------------------
// Architecture guard (ADR-0006 / ADR-0008): :core is the dependency-free root —
// it must not depend on any transport module. The transport modules depend on
// :core, never the other way around. Verified at configuration time and wired
// into `check`. Computed eagerly into a plain list so it is configuration-cache safe.
// ---------------------------------------------------------------------------
val coreInTreeDependencies: List<String> =
    configurations
        .filter { it.name.endsWith("MainApi") || it.name.endsWith("MainImplementation") }
        .flatMap { cfg ->
            cfg.dependencies
                .withType(ProjectDependency::class.java)
                .map { "${cfg.name} -> ${it.path}" }
        }

val verifyModuleBoundary by tasks.registering {
    group = "verification"
    description = "Fails if :core declares a dependency on another in-tree module (ADR-0006)."
    // Copy into a task-local val so the doLast action captures a plain List<String> rather than a
    // script-object reference (the latter is not serializable by the configuration cache).
    val violations = coreInTreeDependencies
    doLast {
        if (violations.isNotEmpty()) {
            throw GradleException(
                "ADR-0006 violation: :core must not depend on other in-tree modules:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyModuleBoundary)
}
