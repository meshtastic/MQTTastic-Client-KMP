plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.wire) apply false
}

// ---------------------------------------------------------------------------
// Version: derived from the latest git tag (e.g. v0.3.6 → 0.3.6).
// Between tags: next patch + "-SNAPSHOT" (e.g. v0.3.6-4-gabcdef → 0.3.7-SNAPSHOT).
// Overridable via -PVERSION_NAME=... (used by CI snapshot publishing).
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
        // Not on an exact tag — compute next-patch SNAPSHOT
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

allprojects {
    version = resolvedVersion
}
