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
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Maven Central publishing for every published module.
 *
 * The artifactId is derived from the module name (`:core` → `mqtt-client-core`,
 * `:transport-tcp` → `mqtt-client-transport-tcp`, `:bom` → `mqtt-client-bom`), giving a
 * stable per-module coordinate. The vanniktech plugin auto-creates the per-target KMP
 * publications under that base coordinate.
 */
class MqttPublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.vanniktech.maven.publish")

            extensions.configure<MavenPublishBaseExtension> {
                publishToMavenCentral(automaticRelease = true)
                if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                    signAllPublications()
                }

                // groupId comes from the GROUP gradle property and version from project.version
                // (both already finalized by the vanniktech plugin on apply). Only the artifactId is
                // module-specific, so override just that — passing groupId/version again would throw
                // "property is final and cannot be changed".
                coordinates(artifactId = "mqtt-client-${project.name}")

                pom {
                    name.set("MQTTastic Client (${project.name})")
                    description.set(
                        "A fully-featured MQTT 5.0 and 3.1.1 client library for Kotlin " +
                            "Multiplatform — module: ${project.name}.",
                    )
                    inceptionYear.set("2025")
                    url.set("https://github.com/meshtastic/MQTTastic-Client-KMP")
                    licenses {
                        license {
                            name.set("GNU General Public License, Version 3.0")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("meshtastic")
                            name.set("Meshtastic")
                            url.set("https://meshtastic.org")
                        }
                    }
                    scm {
                        url.set("https://github.com/meshtastic/MQTTastic-Client-KMP")
                        connection.set("scm:git:git://github.com/meshtastic/MQTTastic-Client-KMP.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/meshtastic/MQTTastic-Client-KMP.git",
                        )
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/meshtastic/MQTTastic-Client-KMP/issues")
                    }
                }
            }
        }
}
