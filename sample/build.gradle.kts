import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.wire)
}

kotlin {
    jvmToolchain(17)

    @Suppress("DEPRECATION")
    androidLibrary {
        namespace = "org.meshtastic.mqtt.sample.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.layout)
            implementation(libs.compose.material3.adaptive.navigation)
            implementation(libs.compose.material3.navigation.suite)
            implementation(libs.compose.material3.window.size)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.11.0-beta02")
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(project(":library"))
            implementation(libs.wire.runtime)
            implementation(libs.kotlinx.io.bytestring)
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.core)
            // Provides Dispatchers.Main on JVM desktop (Swing/AWT EDT).
            implementation(libs.kotlinx.coroutines.swing)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

wire {
    sourcePath {
        srcDir("../protobufs")
        include("meshtastic/*.proto")
    }
    protoPath {
        srcDir("../protobufs")
        include("nanopb.proto")
    }
    kotlin {
        makeImmutableCopies = false
        boxOneOfsMinSize = 5000
    }
    root("meshtastic.*")
    prune("meshtastic.MeshPacket#delayed")
    prune("meshtastic.MeshPacket.Delayed")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        // Sample app — ProGuard adds no value and trips on wire-runtime's Android-only classes.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "mqtt-sample"
            // jpackage requires the first component to be >= 1 (macOS CFBundleShortVersionString rule).
            // Library follows 0.x SemVer; coerce to 1.x.y for the installer metadata only.
            packageVersion = providers.gradleProperty("VERSION_NAME").get().let { v ->
                val parts = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
                val major = parts.getOrElse(0) { 0 }.coerceAtLeast(1)
                val minor = parts.getOrElse(1) { 0 }
                val patch = parts.getOrElse(2) { 0 }
                "$major.$minor.$patch"
            }
            description = "MQTTastic sample — a reference MQTT 5.0 client built on MQTTastic-Client-KMP."
            vendor = "Meshtastic"
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
        }
    }
}

// Opt-in Compose compiler metrics + stability reports.
// Enable with `-PenableComposeMetrics=true`; outputs land in
// `sample/build/compose-metrics/` and `sample/build/compose-reports/`.
// See https://developer.android.com/develop/ui/compose/performance/stability/diagnose
if (providers.gradleProperty("enableComposeMetrics").orNull.toBoolean()) {
    composeCompiler {
        metricsDestination = layout.buildDirectory.dir("compose-metrics")
        reportsDestination = layout.buildDirectory.dir("compose-reports")
    }
}
