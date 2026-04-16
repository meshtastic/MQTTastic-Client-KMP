plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "org.meshtastic.mqtt.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.meshtastic.mqtt.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Version is the single source of truth defined in root gradle.properties (VERSION_NAME).
        // versionCode is derived from semver: MAJOR*10000 + MINOR*100 + PATCH.
        val semver = providers.gradleProperty("VERSION_NAME").get()
        val (major, minor, patch) = semver.substringBefore('-').split('.').map(String::toInt)
        versionCode = major * 10000 + minor * 100 + patch
        versionName = semver
    }
}

dependencies {
    implementation(project(":sample"))
    implementation(libs.androidx.activity.compose)
}

// Opt-in Compose compiler metrics + stability reports for the Android host.
if (providers.gradleProperty("enableComposeMetrics").orNull.toBoolean()) {
    composeCompiler {
        metricsDestination = layout.buildDirectory.dir("compose-metrics")
        reportsDestination = layout.buildDirectory.dir("compose-reports")
    }
}
