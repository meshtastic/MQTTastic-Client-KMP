# `org.meshtastic:mqtt-client`

> A fully-featured **MQTT 5.0** client library for **Kotlin Multiplatform** — connecting JVM, Android, iOS, macOS, Linux, Windows, and browsers through a single, idiomatic Kotlin API.

[![Maven Central](https://img.shields.io/maven-central/v/org.meshtastic/mqtt-client?label=Maven%20Central)](https://central.sonatype.com/artifact/org.meshtastic/mqtt-client)
[![API Docs](https://img.shields.io/badge/API%20Docs-latest-blue.svg)](https://meshtastic.github.io/MQTTtastic-Client-KMP)

This is the consumer-facing reference for the library artifact. For repository-level
context (architecture, contributing, CI), see the [top-level README](../README.md)
and [`docs/adr/`](../docs/adr/).

## Add to your build

```kotlin
// libs.versions.toml
[versions]
mqtttastic = "<latest>"

[libraries]
mqtt-client = { module = "org.meshtastic:mqtt-client", version.ref = "mqtttastic" }
```

```kotlin
// KMP project
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.mqtt.client)
        }
    }
}

// Android-only or JVM-only project
dependencies {
    implementation(libs.mqtt.client)
}
```

Gradle picks the right per-platform artifact automatically via Kotlin Multiplatform variant resolution.

## Quickstart

### Connect and subscribe (any transport)

```kotlin
import kotlinx.coroutines.flow.collect
import org.meshtastic.mqtt.MqttClient
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.QoS

val client = MqttClient("my-client-id") {
    username = "meshdev"
    password("large4cats")
    autoReconnect = true
}

// Works on every platform the URI scheme supports.
val endpoint = MqttEndpoint.parse("tls://mqtt.example.org:8883")
// Or explicitly:
// val endpoint = MqttEndpoint.WebSocket("wss://mqtt.example.org:443/mqtt")

client.connect(endpoint)
client.subscribe("sensors/+/temperature", QoS.AT_LEAST_ONCE)

client.messages.collect { msg ->
    println("${msg.topic}: ${msg.payload.decodeToString()}")
}
```

### Publish

```kotlin
import kotlinx.io.bytestring.encodeToByteString

client.publish(
    topic = "sensors/living-room/temperature",
    payload = "21.5".encodeToByteString(),
    qos = QoS.EXACTLY_ONCE,
    retain = true,
)
```

### Filter messages per topic

```kotlin
import org.meshtastic.mqtt.messagesMatching

// Cold Flow scoped to one filter — great in a ViewModel.
client.messagesMatching("sensors/+/temperature").collect { msg -> /* ... */ }
```

### Lifecycle

```kotlin
try {
    client.connect(endpoint)
    // ...
} finally {
    client.disconnect()
    client.close()
}
```

Or use the `use { }` extension to scope the client to a suspending block:

```kotlin
client.use {
    it.connect(endpoint)
    it.subscribe("…", QoS.AT_MOST_ONCE)
    // ...
}
```

## Transport selection

`MqttEndpoint.parse(uri)` understands four schemes:

| URI scheme | Transport | Platforms |
|---|---|---|
| `tcp://host:port` | Plain TCP | JVM, Android, iOS, macOS, Linux, Windows |
| `tls://host:port` or `ssl://host:port` | TCP + TLS | JVM, Android, iOS, macOS, Linux, Windows |
| `ws://host:port/path` | WebSocket | All platforms including `wasmJs` |
| `wss://host:port/path` | WebSocket + TLS | All platforms including `wasmJs` |

Attempting `tcp://…` or `tls://…` on `wasmJs` raises `IllegalArgumentException` — the browser can't open raw sockets.

## Error handling

Every failure mode is a subclass of `MqttException`:

```kotlin
try {
    client.connect(endpoint)
} catch (e: MqttException.ConnectionRejected) {
    // broker said no — check e.reasonCode and e.serverReference
} catch (e: MqttException.ConnectionLost) {
    // transport died or broker sent DISCONNECT
} catch (e: MqttException.ProtocolError) {
    // malformed traffic or unexpected packet
} catch (e: MqttException) {
    // catch-all
}
```

Reason codes are exposed as the typed `ReasonCode` enum with spec-accurate names.

## Observability

```kotlin
import org.meshtastic.mqtt.MqttLogLevel
import org.meshtastic.mqtt.MqttLogger

val client = MqttClient("id") {
    logger = MqttLogger.println()   // or implement your own
    logLevel = MqttLogLevel.DEBUG
}
```

The `MqttLogger` interface is a single-method SAM with zero cost when the
level disables a call — plug in SLF4J, Timber, `println`, or anything else.

## What's covered

All 15 MQTT 5.0 packet types, every property, full QoS 0/1/2 state machines,
enhanced authentication (§4.12), topic aliases (§3.3.2.3.4), flow control
(Receive Maximum §3.2.2.3.3), shared subscriptions, retained will messages,
request/response metadata, auto-reconnect with exponential back-off, and
strict spec-compliant validation on every input. See the [main README](../README.md#mqtt-50-coverage)
for the full coverage table.

## Compatibility

- Kotlin **2.3.20** or newer.
- JDK **17+** for JVM/Android consumers (source/target compiled against 17).
- Ktor **3.4.x** on the classpath for consumers who want to share the HTTP client.

## Versioning

Follows [Semantic Versioning](https://semver.org). Public API is enforced by
[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator);
any change to the API dump fails CI.

## License

GPL-3.0 — see the [LICENSE](../LICENSE) file at the repository root.
