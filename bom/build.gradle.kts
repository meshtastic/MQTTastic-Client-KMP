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
    `java-platform`
    id("mqtt.publishing")
}

// Bill-of-Materials: pins every mqtt-client-* artifact to one version so consumers can write
//
//   implementation(platform("org.meshtastic:mqtt-client-bom:<version>"))
//   implementation("org.meshtastic:mqtt-client-core")
//   implementation("org.meshtastic:mqtt-client-transport-tcp")
//
// without repeating the version on each dependency.
dependencies {
    constraints {
        api("org.meshtastic:mqtt-client-core:${project.version}")
        api("org.meshtastic:mqtt-client-transport-tcp:${project.version}")
        api("org.meshtastic:mqtt-client-transport-ws:${project.version}")
    }
}
