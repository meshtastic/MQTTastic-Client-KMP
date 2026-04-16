plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinx.atomicfu) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.detekt) apply false
}
