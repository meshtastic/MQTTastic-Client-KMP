pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.4.0"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.6.0"
}

// Shared Develocity and Build Cache configuration
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
