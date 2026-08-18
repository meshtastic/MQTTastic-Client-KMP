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
    id("com.gradle.develocity") version "4.5.0"
    id("com.gradle.common-custom-user-data-gradle-plugin") version "2.8.0"
}

// Build Scans + remote Build Cache (Develocity OSS Community instance)
apply(from = "gradle/develocity.settings.gradle")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// Version: derived from the latest git tag (e.g. v0.3.6 -> 0.3.6).
// Between tags: next patch + "-SNAPSHOT" (e.g. v0.3.6-4-gabcdef -> 0.3.7-SNAPSHOT).
// Overridable via -PVERSION_NAME=... (used by CI snapshot publishing).
//
// Assigned from settings via gradle.lifecycle.beforeProject rather than an
// `allprojects { }` block in the root build script: under Isolated Projects the
// root project may not reach into its subprojects' state, and setting `version`
// that way fails configuration outright. beforeProject is the supported
// equivalent and needs no cross-project access.
// ---------------------------------------------------------------------------
val gitVersion: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v*")
    isIgnoreExitValue = true
}.standardOutput.asText.map { raw ->
    val desc = raw.trim()
    if (desc.isBlank()) {
        return@map providers.gradleProperty("VERSION_NAME").getOrElse("0.0.0-SNAPSHOT")
    }
    val stripped = desc.removePrefix("v")
    if ('-' in stripped) {
        // Not on an exact tag - compute next-patch SNAPSHOT
        val base = stripped.substringBefore('-')
        val parts = base.split('.').map(String::toInt)
        "${parts[0]}.${parts[1]}.${parts[2] + 1}-SNAPSHOT"
    } else {
        stripped
    }
}

val resolvedVersion: String =
    providers.gradleProperty("VERSION_NAME")
        .orElse(gitVersion)
        .get()

// Copied into a local before the lambda closes over it: a top-level `val` in a settings script is a
// field of the script object, so referencing it directly would capture the script itself — which the
// configuration cache cannot serialize ("cannot serialize Gradle script object references").
run {
    val projectVersion = resolvedVersion
    gradle.lifecycle.beforeProject { version = projectVersion }
}

rootProject.name = "MQTTastic-Client-KMP"
include(":core")
include(":transport-tcp")
include(":transport-ws")
include(":bom")
include(":sample")
include(":sample:androidApp")
