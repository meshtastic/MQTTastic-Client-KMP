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
plugins {
    `kotlin-dsl`
}

dependencies {
    // Convention plugins configure the KMP and vanniktech-publish extensions, so the
    // corresponding Gradle plugins must be on the convention build's classpath.
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.vanniktech.publish.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("mqttKmpLibrary") {
            id = "mqtt.kmp.library"
            implementationClass = "MqttKmpLibraryConventionPlugin"
        }
        register("mqttPublishing") {
            id = "mqtt.publishing"
            implementationClass = "MqttPublishingConventionPlugin"
        }
    }
}
