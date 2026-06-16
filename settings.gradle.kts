pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.4.3"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.6.0"
}

// Build Cache configuration (HTTP remote cache + local)
apply(from = "gradle/build-cache.settings.gradle")

// Build Scans — publish in CI only for debugging and performance profiling.
apply(from = "gradle/develocity.settings.gradle")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MQTTastic-Client-KMP"
include(":library")
include(":sample")
include(":sample:androidApp")
