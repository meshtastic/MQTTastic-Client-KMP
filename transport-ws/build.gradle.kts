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

// Binary-WebSocket transport via ktor-client-websockets. Available on every target including the
// browser (wasmJs) — the only transport that runs there. One Ktor engine is wired per platform.
kotlin {
    android {
        namespace = "org.meshtastic.mqtt.transport.ws"
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
            api(project(":core"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }

        // Ktor HttpClient engines — one per platform family for WebSocket auto-detection.
        jvmMain.get().dependencies {
            implementation(libs.ktor.client.cio)
        }
        findByName("androidMain")?.dependencies {
            implementation(libs.ktor.client.cio)
        }
        findByName("appleMain")?.dependencies {
            implementation(libs.ktor.client.cio)
        }
        findByName("linuxMain")?.dependencies {
            implementation(libs.ktor.client.cio)
        }
        findByName("mingwMain")?.dependencies {
            implementation(libs.ktor.client.winhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
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
