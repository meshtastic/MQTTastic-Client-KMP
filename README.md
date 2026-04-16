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
- 📝 **Configurable logging** — zero-overhead `MqttLogger` interface with level filtering
- ✅ **Spec-validated** — topic filter wildcards, reserved bits, and packet structure per MQTT 5.0

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
            implementation("org.meshtastic:mqtt-client:0.1.0")
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
                implementation 'org.meshtastic:mqtt-client:0.1.0'
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
    implementation("org.meshtastic:mqtt-client:0.1.0")
}
```
</details>

## Quick Start

```kotlin
import org.meshtastic.mqtt.*

// Create a client with the factory DSL
val client = MqttClient("my-client") {
    keepAliveSeconds = 30
    autoReconnect = true
    defaultQos = QoS.AT_LEAST_ONCE  // all publishes default to QoS 1
}

// Connect, work, and auto-close
client.use(MqttEndpoint.parse("tcp://broker.example.com:1883")) { c ->
    // Subscribe
    c.subscribe("sensors/temperature")

    // Publish (uses defaultQos from config)
    c.publish("sensors/temperature", "22.5")

    // Collect messages
    c.messagesForTopic("sensors/temperature").collect { msg ->
        println("Received: ${msg.payloadAsString()}")
    }
}
```

<details>
<summary>Verbose equivalent (without convenience APIs)</summary>

```kotlin
val config = MqttConfig(clientId = "my-client", keepAliveSeconds = 30, autoReconnect = true)
val client = MqttClient(config)

client.connect(MqttEndpoint.Tcp(host = "broker.example.com", port = 1883))
client.subscribe("sensors/temperature", QoS.AT_LEAST_ONCE)
client.publish(
    MqttMessage(
        topic = "sensors/temperature",
        payload = ByteString("22.5".encodeToByteArray()),
        qos = QoS.AT_LEAST_ONCE,
    ),
)
client.messages.collect { msg ->
    if (msg.topic == "sensors/temperature") {
        println("Received: ${msg.payload.toByteArray().decodeToString()}")
    }
}
client.close()
```
</details>

### Convenience APIs

The library ships several ergonomic extensions to reduce boilerplate:

| API | What it replaces |
|-----|-----------------|
| `MqttClient("id") { ... }` | `MqttClient(MqttConfig(clientId = "id", ...))` |
| `MqttEndpoint.parse("tcp://host:1883")` | `MqttEndpoint.Tcp(host, port, tls)` |
| `client.use(endpoint) { ... }` | Manual `connect` + `try/finally { close() }` |
| `msg.payloadAsString()` | `msg.payload.toByteArray().decodeToString()` |
| `client.messagesForTopic("x")` | `client.messages.filter { it.topic == "x" }` |
| `client.messagesMatching("x/+/y")` | Manual wildcard matching on `messages` flow |
| `client.publish(topic, payload)` | Constructing `MqttMessage` manually |
| `defaultQos` / `defaultRetain` | Repeating `qos = QoS.AT_LEAST_ONCE` on every publish |
| `will { topic = ...; payload("...") }` | `will = WillConfig(topic = ..., payload = ByteString(...))` |

#### Endpoint Parsing

Parse broker URIs instead of constructing endpoints manually:

```kotlin
MqttEndpoint.parse("tcp://broker:1883")       // Plain TCP
MqttEndpoint.parse("ssl://broker:8883")       // TCP + TLS
MqttEndpoint.parse("mqtts://broker")          // TLS, default port 8883
MqttEndpoint.parse("wss://broker/mqtt")       // Secure WebSocket
```

#### Topic-Filtered Message Flows

```kotlin
// Exact topic match
client.messagesForTopic("sensors/temperature").collect { ... }

// Wildcard filter (supports + and #)
client.messagesMatching("sensors/+/temperature").collect { ... }
```

### Builder DSL

Use the builder DSL for complex configurations (annotated with `@MqttDsl` for scope safety, like Ktor's `@KtorDsl`):

```kotlin
val config = MqttConfig.build {
    clientId = "sensor-hub-01"
    keepAliveSeconds = 30
    cleanStart = false
    autoReconnect = true
    defaultQos = QoS.AT_LEAST_ONCE
    logger = MqttLogger.println()
    logLevel = MqttLogLevel.DEBUG
    will {
        topic = "sensors/status"
        payload("offline")
        qos = QoS.AT_LEAST_ONCE
        retain = true
    }
}
```

### Logging

The library provides a zero-overhead logging interface. When no logger is configured (the default), message lambdas are never evaluated:

```kotlin
// Built-in println logger for quick debugging
val config = MqttConfig(
    clientId = "debug-client",
    logger = MqttLogger.println(),
    logLevel = MqttLogLevel.DEBUG,
)

// Custom logger (e.g., forwarding to your app's logging framework)
val config = MqttConfig(
    clientId = "production-client",
    logger = object : MqttLogger {
        override fun log(level: MqttLogLevel, tag: String, message: String, throwable: Throwable?) {
            myAppLogger.log(level.name, "[$tag] $message", throwable)
        }
    },
    logLevel = MqttLogLevel.INFO,
)
```

Log levels from most to least verbose: `TRACE` → `DEBUG` → `INFO` → `WARN` → `ERROR` → `NONE`.

## Android / KMP Integration

The library is designed as a drop-in MQTT client for KMP projects. Consumer ProGuard/R8 rules are bundled automatically.

### ViewModel-scoped client

```kotlin
class MqttViewModel : ViewModel() {
    private val client = MqttClient("my-device") {
        autoReconnect = true
        keepAliveSeconds = 30
    }

    val connectionState = client.connectionState

    fun connect(broker: String) {
        viewModelScope.launch {
            client.connect(MqttEndpoint.parse(broker))
            client.subscribe("msh/2/e/#", QoS.AT_LEAST_ONCE)
        }
    }

    fun observeMessages() = client.messagesMatching("msh/2/e/+/!/#")

    override fun onCleared() {
        viewModelScope.launch { client.close() }
    }
}
```

### Collecting in Compose

```kotlin
@Composable
fun MqttScreen(viewModel: MqttViewModel) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.observeMessages().collect { msg ->
            // Process message
        }
    }
}
```

### Version alignment

The library uses **Ktor 3.4.2** and **kotlinx-coroutines 1.10.2**. If your project uses the same versions, no conflicts will arise. Pin versions in your `libs.versions.toml` to avoid Gradle resolution surprises.

## MQTT 5.0 Coverage

| Feature | Status |
|---------|--------|
| All 15 packet types | ✅ |
| QoS 0 / 1 / 2 state machines | ✅ |
| Session management | ✅ |
| Will messages & Will Delay | ✅ |
| Topic aliases | ✅ |
| Topic filter validation (§4.7) | ✅ |
| Enhanced authentication (AUTH) | ✅ |
| Flow control (Receive Maximum) | ✅ |
| Automatic reconnection | ✅ |
| Request/Response pattern | ✅ |
| Configurable logging | ✅ |
| Shared subscriptions | ✅ |
| Server redirect | ✅ |

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
