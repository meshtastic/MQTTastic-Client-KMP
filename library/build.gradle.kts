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
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    alias(libs.plugins.bcv)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
}

group = "org.meshtastic"
version = "0.1.0"

kotlin {
    applyDefaultHierarchyTemplate()
    jvmToolchain(11)

    jvm()

    androidLibrary {
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

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        // Custom intermediate source set for all non-browser targets (TCP transport).
        // Not part of the default hierarchy template — must be wired manually.
        val nonWebMain by creating {
            dependsOn(commonMain.get())
        }
        val nonWebTest by creating {
            dependsOn(commonTest.get())
        }

        jvmMain.get().dependsOn(nonWebMain)
        androidMain.get().dependsOn(nonWebMain)
        nativeMain.get().dependsOn(nonWebMain)

        jvmTest.get().dependsOn(nonWebTest)
        nativeTest.get().dependsOn(nonWebTest)

        // Wire Android host test to nonWebTest for shared transport tests
        findByName("androidHostTest")?.dependsOn(nonWebTest)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.bytestring)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        nonWebMain.dependencies {
            implementation(libs.ktor.network)
            implementation(libs.ktor.network.tls)
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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "mqtt-client", version.toString())

    pom {
        name = "MQTTtastic Client"
        description = "A fully-featured MQTT 5.0 client library for Kotlin Multiplatform."
        inceptionYear = "2025"
        url = "https://github.com/meshtastic/MQTTtastic-Client-KMP"
        licenses {
            license {
                name = "GPL-3.0"
                url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "meshtastic"
                name = "Meshtastic"
                url = "https://meshtastic.org"
            }
        }
        scm {
            url = "https://github.com/meshtastic/MQTTtastic-Client-KMP"
            connection = "scm:git:git://github.com/meshtastic/MQTTtastic-Client-KMP.git"
            developerConnection = "scm:git:ssh://github.com/meshtastic/MQTTtastic-Client-KMP.git"
        }
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

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

dokka {
    dokkaSourceSets.named("commonMain") {
        // Module/package docs: first-class overview page on the generated site
        // and a narrated landing for each Kotlin package.
        includes.from(layout.projectDirectory.file("Module.md"))
    }
}
