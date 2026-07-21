pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Build Cache configuration (HTTP remote cache + local)
apply(from = "gradle/build-cache.settings.gradle")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MQTTastic-Client-KMP"
include(":core")
include(":transport-tcp")
include(":transport-ws")
include(":bom")
include(":sample")
include(":sample:androidApp")
