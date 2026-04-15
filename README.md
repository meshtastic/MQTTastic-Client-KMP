# MQTTtastic Client KMP

[![Maven Central](https://img.shields.io/maven-central/v/org.meshtastic/mqtt-client?label=Maven%20Central)](https://central.sonatype.com/artifact/org.meshtastic/mqtt-client)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7f52ff.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)

A fully-featured **MQTT 5.0** client library for **Kotlin Multiplatform** — connecting JVM, Android, iOS, macOS, Linux, Windows, and browsers through a single, idiomatic Kotlin API.

## Features

- 📦 **Full MQTT 5.0** — all 15 packet types, properties, reason codes, and enhanced auth
- 🔄 **All QoS levels** — QoS 0, 1, and 2 with complete state machine handling
- 🌍 **True multiplatform** — one codebase, 9 targets (see [Platform Support](#platform-support))
- 🔒 **TLS/SSL** — secure connections on all native/JVM targets
- 🌐 **WebSocket** — browser-compatible transport for wasmJs
- ⚡ **Coroutines-first** — `suspend` functions and `Flow`-based message delivery
- 🪶 **Minimal dependencies** — only Ktor (transport) + kotlinx-coroutines + kotlinx-io
- 🛡️ **Immutable by design** — `ByteString` payloads, validated inputs, data class models

## Platform Support

| Platform | Target | Transport | Status |
|----------|--------|-----------|--------|
| JVM | `jvm` | TCP/TLS | ✅ |
| Android | `android` | TCP/TLS | ✅ |
| iOS | `iosArm64`, `iosSimulatorArm64` | TCP/TLS | ✅ |
| macOS | `macosArm64` | TCP/TLS | ✅ |
| Linux | `linuxX64`, `linuxArm64` | TCP/TLS | ✅ |
| Windows | `mingwX64` | TCP/TLS | ✅ |
| Browser | `wasmJs` | WebSocket | ✅ |

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
// settings.gradle.kts
repositories {
    mavenCentral()
}

// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("org.meshtastic:mqtt-client:<version>")
        }
    }
}
```

<details>
<summary>Groovy DSL</summary>

```groovy
// settings.gradle
repositories {
    mavenCentral()
}

// build.gradle
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation 'org.meshtastic:mqtt-client:<version>'
            }
        }
    }
}
```
</details>

<details>
<summary>Single-platform (JVM / Android only)</summary>

```kotlin
dependencies {
    implementation("org.meshtastic:mqtt-client:<version>")
}
```
</details>

## Quick Start

```kotlin
val client = MqttClient(
    config = MqttConfig(
        endpoint = MqttEndpoint.Tcp(host = "broker.example.com", port = 1883),
        clientId = "my-client",
    )
)

// Connect
client.connect()

// Subscribe
client.subscribe("sensors/temperature") { message ->
    println("Received: ${message.payload.decodeToString()} on ${message.topic}")
}

// Publish
client.publish(
    topic = "sensors/temperature",
    payload = "22.5".encodeToByteArray(),
    qos = QoS.AT_LEAST_ONCE,
)

// Disconnect
client.disconnect()
```

> **Note:** The public client API (`MqttClient`, `MqttConfig`) is under active development.
> The packet codec layer and protocol primitives are implemented and tested.

## MQTT 5.0 Coverage

| Feature | Status |
|---------|--------|
| All 15 packet types | 🚧 In progress |
| QoS 0 / 1 / 2 state machines | 🚧 In progress |
| Session management | 🚧 In progress |
| Will messages & Will Delay | 🚧 Planned |
| Topic aliases | 🚧 Planned |
| Shared subscriptions | 🚧 Planned |
| Enhanced authentication (AUTH) | 🚧 Planned |
| Flow control (Receive Maximum) | 🚧 Planned |
| Automatic reconnection | 🚧 Planned |
| Server redirect | 🚧 Planned |
| Request/Response pattern | 🚧 Planned |

## Building

```bash
./gradlew build              # Compile all targets
./gradlew allTests           # Run tests on all targets
./gradlew jvmTest            # JVM tests only
./gradlew spotlessCheck      # Check formatting
./gradlew detekt             # Static analysis
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed build instructions.

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on:
- Setting up your development environment
- Code style and conventions
- Submitting pull requests

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE),
consistent with all repositories in the [Meshtastic](https://github.com/meshtastic) organization.
