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
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared Kotlin Multiplatform configuration for the library modules
 * (`:core`, `:transport-tcp`, `:transport-ws`).
 *
 * Declares the common (non-browser) target set once so module build files don't repeat it.
 * Modules that also target the browser add `wasmJs { browser() }` themselves; the TCP
 * transport, which cannot run on wasmJs, simply omits it.
 */
class MqttKmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply("org.jetbrains.dokka")
            }

            extensions.configure<KotlinMultiplatformExtension> {
                jvmToolchain(
                    libs
                        .findVersion("javaToolchain")
                        .get()
                        .requiredVersion
                        .toInt(),
                )

                jvm()
                iosArm64()
                iosSimulatorArm64()
                macosArm64()
                linuxX64()
                linuxArm64()
                mingwX64()

                applyDefaultHierarchyTemplate()

                sourceSets.getByName("commonMain").dependencies {
                    implementation(libs.findLibrary("kotlinx-coroutines-core").get())
                }
                sourceSets.getByName("commonTest").dependencies {
                    implementation(libs.findLibrary("kotlin-test").get())
                    implementation(libs.findLibrary("kotlinx-coroutines-test").get())
                }
            }
        }
}
