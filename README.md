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
import org.meshtastic.mqtt.*
import kotlinx.coroutines.launch

val client = MqttClient(
    config = MqttConfig(clientId = "my-client"),
)

// Collect incoming messages
scope.launch {
    client.messages.collect { message ->
        println("${message.topic}: ${message.payload}")
    }
}

// Connect to a broker
client.connect(MqttEndpoint.Tcp(host = "broker.example.com", port = 1883))

// Subscribe to topics
client.subscribe("sensors/temperature", QoS.AT_LEAST_ONCE)

// Publish a message
client.publish(
    topic = "sensors/temperature",
    payload = "22.5",
    qos = QoS.AT_LEAST_ONCE,
)

// Disconnect and release resources
client.close()
```

## MQTT 5.0 Coverage

| Feature | Status |
|---------|--------|
| All 15 packet types | ✅ |
| QoS 0 / 1 / 2 state machines | ✅ |
| Session management | ✅ |
| Will messages & Will Delay | ✅ |
| Topic aliases | ✅ |
| Shared subscriptions | ✅ |
| Enhanced authentication (AUTH) | ✅ |
| Flow control (Receive Maximum) | ✅ |
| Automatic reconnection | ✅ |
| Server redirect | ✅ |
| Request/Response pattern | ✅ |

## Building

```bash
./gradlew build              # Compile all targets
./gradlew allTests           # Run tests on all targets
./gradlew jvmTest            # JVM tests only
./gradlew spotlessCheck      # Check formatting
./gradlew detekt             # Static analysis
./gradlew apiCheck           # Verify binary compatibility
./gradlew koverVerify        # Check code coverage (≥80%)
./gradlew dokkaGeneratePublicationHtml  # Generate API docs
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
