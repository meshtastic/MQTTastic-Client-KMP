import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "org.meshtastic.mqtt.sample.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm("desktop")

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // Browser demo. `binaries.executable()` is what creates the
    // `wasmJsBrowserDistribution` task the release workflow zips up.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
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
            implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.12.0")
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(project(":core"))
            // `:transport-ws` is the only transport that builds for the browser, so it is the
            // one every target shares. `:transport-tcp` has no wasmJs variant (raw TCP sockets
            // are unavailable in a browser) and is therefore added per non-web target below.
            // `platformTransportFactory()` combines whatever is available on each platform.
            implementation(project(":transport-ws"))
            implementation(libs.meshtastic.protobufs)
            implementation(libs.kotlinx.io.bytestring)
        }

        androidMain.dependencies {
            implementation(project(":transport-tcp"))
        }

        val desktopMain by getting
        desktopMain.dependencies {
            implementation(project(":transport-tcp"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.core)
            // Provides Dispatchers.Main on JVM desktop (Swing/AWT EDT).
            implementation(libs.kotlinx.coroutines.swing)
        }

        iosMain.dependencies {
            implementation(project(":transport-tcp"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        // Sample app — ProGuard adds no value and trips on wire-runtime classes.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "mqtt-sample"
            // jpackage requires the first component to be >= 1 (macOS CFBundleShortVersionString rule).
            // Library follows 0.x SemVer; coerce to 1.x.y for the installer metadata only.
            packageVersion = project.version.toString().let { v ->
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
