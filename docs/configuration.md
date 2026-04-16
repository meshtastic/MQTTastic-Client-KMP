# Configuration Guide

All configuration for the MQTTastic MQTT 5.0 client is done through `MqttConfig`, passed to `MqttClient` at construction time. Configuration is **immutable after creation** — use Kotlin's `copy()` on the data class if you need variants.

This guide covers every configuration option in detail with practical examples. For a quick start, see the [README](../README.md).

## Table of Contents

- [Creating a Client](#creating-a-client)
- [Connection Settings](#connection-settings)
- [Authentication](#authentication)
- [Will Messages](#will-messages)
- [Quality of Service Defaults](#quality-of-service-defaults)
- [Session Management](#session-management)
- [Reconnection](#reconnection)
- [Flow Control](#flow-control)
- [Logging](#logging)
- [Endpoints](#endpoints)
- [Full Configuration Reference](#full-configuration-reference)

---

## Creating a Client

There are three ways to create an `MqttClient`, each suited to different use cases.

### 1. Factory function (recommended)

The most concise approach — combines a client ID with a builder DSL block:

```kotlin
val client = MqttClient("sensor-hub") {
    keepAliveSeconds = 30
    autoReconnect = true
    defaultQos = QoS.AT_LEAST_ONCE
}
```

The factory function sets `clientId` for you before applying the block, so you don't need to set it inside.

### 2. Direct constructor

Pass a fully constructed `MqttConfig` directly:

```kotlin
val config = MqttConfig(
    clientId = "sensor-hub",
    keepAliveSeconds = 30,
    autoReconnect = true,
    defaultQos = QoS.AT_LEAST_ONCE,
)
val client = MqttClient(config)
```

This is useful when you want to store or share configuration separately from client creation.

### 3. Builder DSL

Use `MqttConfig.build { }` to construct the config independently:

```kotlin
val config = MqttConfig.build {
    clientId = "sensor-hub"
    keepAliveSeconds = 30
    defaultQos = QoS.AT_LEAST_ONCE
    will {
        topic = "devices/sensor-hub/status"
        payload("offline")
        retain = true
    }
}
val client = MqttClient(config)
```

The builder is annotated with `@MqttDsl`, which prevents accidental scope leakage (e.g., setting `clientId` inside a `will { }` block).

---

## Connection Settings

### `keepAliveSeconds`

| | |
|---|---|
| **Type** | `Int` |
| **Default** | `60` |
| **Range** | `0..65535` |

The maximum interval in seconds between control packets sent to the broker (MQTT §3.1.2.10). The client sends a `PINGREQ` if no other packet is sent within **75%** of this interval. If no `PINGRESP` is received within the full interval, the connection is considered dead.

Set to `0` to disable the keepalive mechanism entirely (not recommended for most deployments).

```kotlin
val client = MqttClient("sensor") {
    keepAliveSeconds = 30  // Ping every ~22.5 seconds if idle
}
```

> **Tip:** Lower values detect dead connections faster but generate more traffic. For mobile or battery-constrained devices, `60–120` seconds is a good balance. For real-time applications, `10–30` seconds provides faster failure detection.

### `cleanStart`

| | |
|---|---|
| **Type** | `Boolean` |
| **Default** | `true` |

Controls whether the broker discards existing session state on connect (MQTT §3.1.2.4).

- **`true`** — The broker discards any previous session associated with this `clientId`. Subscriptions and in-flight messages are cleared.
- **`false`** — The broker resumes the previous session, including active subscriptions and any queued QoS 1/2 messages that were not acknowledged.

```kotlin
// Resume previous session (persistent subscriptions)
val client = MqttClient("device-001") {
    cleanStart = false
    sessionExpiryInterval = 3600  // Keep session for 1 hour after disconnect
}
```

> **Limitation:** Client-side session persistence is not yet implemented. When `cleanStart=false`, the broker will resume session state (subscriptions, queued QoS 1/2 messages), but the client does not persist in-flight messages across reconnects. Packet IDs are preserved, but unacknowledged messages may be lost. Full client-side session persistence will be added in a future release.

See [Session Management](#session-management) for the full picture on persistent sessions.

### `userProperties`

| | |
|---|---|
| **Type** | `List<Pair<String, String>>` |
| **Default** | `emptyList()` |

Application-defined key-value metadata sent with the CONNECT packet. Keys may repeat. These properties are delivered to the broker but are **not** forwarded to other clients.

```kotlin
val client = MqttClient("sensor") {
    userProperties = listOf(
        "firmware-version" to "2.1.0",
        "device-type" to "ESP32",
        "region" to "us-east-1",
    )
}
```

---

## Authentication

### Basic authentication

Use `username` and `password` for simple password-based authentication (MQTT §3.1.3.5–6):

```kotlin
val client = MqttClient("secure-client") {
    username = "device-001"
    password("s3cr3t-t0k3n")  // Convenience method — encodes string as UTF-8 ByteString
}
```

The `password()` builder method accepts a `String` and encodes it as UTF-8. You can also set `password` directly as a `ByteString` for binary credentials:

```kotlin
import kotlinx.io.bytestring.ByteString

val client = MqttClient("secure-client") {
    username = "device-001"
    password = ByteString(binaryToken)
}
```

### Enhanced authentication (MQTT 5.0)

For SASL-style challenge/response flows (MQTT §4.12), set `authenticationMethod` and optionally `authenticationData` in the config. Then listen on the `authChallenges` flow to respond to broker challenges:

```kotlin
val client = MqttClient("sasl-client") {
    authenticationMethod = "SCRAM-SHA-256"
    authenticationData = ByteString(initialChallenge)
}

// Handle auth challenges from the broker
launch {
    client.authChallenges.collect { challenge ->
        val response = computeResponse(challenge.authenticationData)
        client.sendAuthResponse(response)
    }
}
```

`AuthChallenge` contains:
- `reasonCode: ReasonCode` — the reason code from the AUTH packet
- `authenticationMethod: String?` — the authentication method in use
- `authenticationData: ByteString?` — the challenge data from the broker

---

## Will Messages

A Will Message (Last Will and Testament) is published by the broker when the client disconnects **unexpectedly** — e.g., due to a network failure or keepalive timeout. If the client disconnects gracefully via `disconnect()`, the will is discarded.

### DSL builder (recommended)

```kotlin
val client = MqttClient("monitored-device") {
    will {
        topic = "devices/device-001/status"
        payload("offline")                      // String payload (sets payloadFormatIndicator = true)
        qos = QoS.AT_LEAST_ONCE
        retain = true
        willDelayInterval = 30                  // Wait 30s before publishing
        contentType = "text/plain"
    }
}
```

### Direct construction

```kotlin
val willConfig = WillConfig(
    topic = "devices/device-001/status",
    payload = "offline".encodeToByteArray(),     // ByteArray constructor
    qos = QoS.AT_LEAST_ONCE,
    retain = true,
    willDelayInterval = 30,
)

val client = MqttClient(MqttConfig(
    clientId = "monitored-device",
    will = willConfig,
))
```

### Will Delay Interval

The `willDelayInterval` (in seconds) controls how long the broker waits after detecting an unexpected disconnect before publishing the will. This gives the client a window to reconnect and cancel the will — useful for handling brief network interruptions without triggering false "offline" notifications.

```kotlin
will {
    topic = "fleet/vehicle-42/status"
    payload("offline")
    willDelayInterval = 60  // Wait 1 minute before publishing
}
```

### Will properties reference

| Property | Type | Description |
|----------|------|-------------|
| `topic` | `String` | Topic to publish to. **Required**, must not be blank. |
| `payload` / `payloadBytes` | `ByteString` | Message payload. Default: empty. |
| `qos` | `QoS` | Delivery guarantee. Default: `AT_MOST_ONCE`. |
| `retain` | `Boolean` | Retain as last known value. Default: `false`. |
| `willDelayInterval` | `Long?` | Seconds before publishing after disconnect. Range: 0..4,294,967,295. |
| `messageExpiryInterval` | `Long?` | Seconds until the will expires after publication. Range: 0..4,294,967,295. |
| `contentType` | `String?` | MIME type of the payload (e.g., `"application/json"`). |
| `responseTopic` | `String?` | Topic for request/response patterns. |
| `correlationData` | `ByteString?` | Correlation data for request/response patterns. |
| `payloadFormatIndicator` | `Boolean` | `true` if payload is UTF-8 text. Default: `false`. |
| `userProperties` | `List<Pair<String, String>>` | Key-value metadata. Default: empty. |

> **Note:** The `payload("text")` builder method automatically sets `payloadFormatIndicator = true`. Use `payload(byteArray)` or `payload(byteString)` for binary payloads.

---

## Quality of Service Defaults

### `defaultQos`

| | |
|---|---|
| **Type** | `QoS` |
| **Default** | `QoS.AT_MOST_ONCE` |

Default QoS level applied to convenience `publish()` calls when no explicit QoS is specified. Similar to Ktor's `defaultRequest {}` pattern — set it once, apply everywhere.

### `defaultRetain`

| | |
|---|---|
| **Type** | `Boolean` |
| **Default** | `false` |

Default retain flag applied to convenience `publish()` calls when no explicit retain value is specified.

### Example

```kotlin
val client = MqttClient("telemetry") {
    defaultQos = QoS.AT_LEAST_ONCE
    defaultRetain = true
}

// These use the defaults (QoS 1, retain = true)
client.publish("sensors/temp", "22.5")
client.publish("sensors/humidity", "65")

// Override per-call when needed
client.publish("sensors/temp", "22.5", qos = QoS.AT_MOST_ONCE, retain = false)
```

### QoS levels

| Level | Enum | Name | Delivery Guarantee |
|-------|------|------|--------------------|
| 0 | `QoS.AT_MOST_ONCE` | Fire and forget | No acknowledgement. Message may be lost. |
| 1 | `QoS.AT_LEAST_ONCE` | Acknowledged delivery | Broker sends PUBACK. Message may be duplicated. |
| 2 | `QoS.EXACTLY_ONCE` | Four-step handshake | PUBLISH → PUBREC → PUBREL → PUBCOMP. No loss or duplication. |

---

## Session Management

### `sessionExpiryInterval`

| | |
|---|---|
| **Type** | `Long?` |
| **Default** | `null` (use broker default) |
| **Range** | `0..4,294,967,295` |

Controls how long the broker retains session state after the client disconnects (MQTT §3.1.2.11.3). Session state includes active subscriptions and queued QoS 1/2 messages.

| Value | Behavior |
|-------|----------|
| `null` | Use broker default |
| `0` | Expire session immediately on disconnect |
| `1..4,294,967,295` | Retain session for this many seconds |
| `4,294,967,295` | Session never expires (0xFFFFFFFF) |

### Interaction with `cleanStart`

These two settings work together:

| `cleanStart` | `sessionExpiryInterval` | Behavior |
|:---:|:---:|---|
| `true` | any | Discard old session, start fresh. New session persists for the configured interval after disconnect. |
| `false` | `0` | Resume old session. Session expires immediately after this disconnect. |
| `false` | `> 0` | Resume old session. Session persists for the configured interval after disconnect. |

### Persistent sessions example

```kotlin
// Long-lived IoT device — resume subscriptions after brief disconnects
val client = MqttClient("iot-gateway-01") {
    cleanStart = false
    sessionExpiryInterval = 86400  // 24 hours
    autoReconnect = true
}
```

### Ephemeral sessions example

```kotlin
// Short-lived consumer — no session persistence
val client = MqttClient("web-dashboard") {
    cleanStart = true
    sessionExpiryInterval = 0
}
```

---

## Reconnection

The client supports automatic reconnection with exponential backoff when the connection is lost unexpectedly.

### `autoReconnect`

| | |
|---|---|
| **Type** | `Boolean` |
| **Default** | `true` |

When enabled, the client automatically attempts to reconnect after an unexpected disconnect. Subscriptions are re-established on successful reconnection.

### `reconnectBaseDelayMs`

| | |
|---|---|
| **Type** | `Long` |
| **Default** | `1000` |
| **Constraint** | Must be `> 0` |

Initial delay in milliseconds between reconnection attempts.

### `reconnectMaxDelayMs`

| | |
|---|---|
| **Type** | `Long` |
| **Default** | `30000` |
| **Constraint** | Must be `≥ reconnectBaseDelayMs` |

Maximum delay cap in milliseconds. The backoff never exceeds this value.

### Backoff formula

The delay **doubles** after each failed attempt, capped at `reconnectMaxDelayMs`:

```
Attempt 1: reconnectBaseDelayMs         (e.g., 1000ms)
Attempt 2: reconnectBaseDelayMs * 2     (e.g., 2000ms)
Attempt 3: reconnectBaseDelayMs * 4     (e.g., 4000ms)
Attempt 4: reconnectBaseDelayMs * 8     (e.g., 8000ms)
...
Attempt N: min(delay * 2, reconnectMaxDelayMs)
```

### Example

```kotlin
val client = MqttClient("resilient") {
    autoReconnect = true
    reconnectBaseDelayMs = 500
    reconnectMaxDelayMs = 60_000  // Cap at 1 minute
}

// Observe reconnection via the connectionState flow
launch {
    client.connectionState.collect { state ->
        when (state) {
            ConnectionState.CONNECTED -> println("Online")
            ConnectionState.CONNECTING -> println("Connecting...")
            ConnectionState.RECONNECTING -> println("Reconnecting...")
            ConnectionState.DISCONNECTED -> println("Offline")
        }
    }
}
```

### Connection states

| State | Description |
|-------|-------------|
| `DISCONNECTED` | Not connected to any broker (initial state). |
| `CONNECTING` | Actively establishing a connection. |
| `CONNECTED` | Connected and ready for publish/subscribe. |
| `RECONNECTING` | Attempting to recover from an unexpected disconnect with exponential backoff. |

---

## Flow Control

These MQTT 5.0 properties control how the client and broker exchange messages efficiently.

### `receiveMaximum`

| | |
|---|---|
| **Type** | `Int` |
| **Default** | `65535` |
| **Range** | `1..65535` |

Maximum number of concurrent in-flight QoS 1 and QoS 2 messages the client is willing to process (MQTT §3.1.2.11.4). The broker will not send more unacknowledged publishes than this limit.

Lower this value on resource-constrained devices to prevent message queue overflow:

```kotlin
val client = MqttClient("constrained-device") {
    receiveMaximum = 10  // Only 10 concurrent in-flight messages
}
```

### `maximumPacketSize`

| | |
|---|---|
| **Type** | `Long?` |
| **Default** | `null` (no limit) |
| **Range** | `1..4,294,967,295` |

Maximum MQTT packet size in bytes the client is willing to accept (MQTT §3.1.2.11.5). The broker will not send packets larger than this. Set this to prevent memory issues with unexpectedly large messages.

```kotlin
val client = MqttClient("limited") {
    maximumPacketSize = 1_048_576  // 1 MB max
}
```

### `topicAliasMaximum`

| | |
|---|---|
| **Type** | `Int` |
| **Default** | `0` |
| **Range** | `0..65535` |

Maximum number of topic aliases the client accepts from the broker (MQTT §3.1.2.11.6). Topic aliases allow the broker to replace repeated topic strings with short integer identifiers, reducing bandwidth on frequently published topics.

- `0` — Topic aliases not supported (default).
- `> 0` — Client accepts up to this many topic aliases from the broker.

```kotlin
val client = MqttClient("optimized") {
    topicAliasMaximum = 50  // Accept up to 50 topic aliases
}
```

### `requestResponseInformation`

| | |
|---|---|
| **Type** | `Boolean` |
| **Default** | `false` |

If `true`, requests the broker to include Response Information in the CONNACK packet (MQTT §3.1.2.11.7). This can be used as a basis for constructing response topics in request/response patterns.

### `requestProblemInformation`

| | |
|---|---|
| **Type** | `Boolean` |
| **Default** | `true` |

If `true`, allows the broker to include Reason String and User Properties in packets where a reason code indicates failure (MQTT §3.1.2.11.8). Useful for debugging; disable on bandwidth-constrained links.

---

## Logging

The library uses a pluggable logging system with zero overhead when disabled. No log strings are constructed unless the message level passes filtering.

### `logLevel`

| | |
|---|---|
| **Type** | `MqttLogLevel` |
| **Default** | `MqttLogLevel.NONE` |

Minimum severity level for log messages. Messages below this level are discarded without constructing the message string.

| Level | What it captures |
|-------|-----------------|
| `TRACE` | Wire-level detail: packet bytes, Variable Byte Integer encoding, raw frames. |
| `DEBUG` | Protocol flow: packet sent/received, state transitions, QoS handshake steps. |
| `INFO` | Lifecycle events: connected, disconnected, subscribed, unsubscribed. |
| `WARN` | Recoverable issues: reconnecting, retry attempts, PINGRESP timeout. |
| `ERROR` | Failures: connection lost, decode errors, broker rejections. |
| `NONE` | Disable all logging. No messages are delivered regardless of logger configuration. |

### `logger`

| | |
|---|---|
| **Type** | `MqttLogger?` |
| **Default** | `null` |

The `MqttLogger` interface has a single method:

```kotlin
fun log(level: MqttLogLevel, tag: String, message: String, throwable: Throwable? = null)
```

Where `tag` identifies the source component (e.g., `"MqttClient"`, `"MqttConnection"`).

### Built-in loggers

```kotlin
// Simple println logger — outputs "[LEVEL] Tag: message"
val client = MqttClient("debug") {
    logger = MqttLogger.println()
    logLevel = MqttLogLevel.DEBUG
}

// Explicit no-op logger — conveys intent more clearly than null
val client = MqttClient("silent") {
    logger = MqttLogger.noop()
}
```

### Custom logger

Implement `MqttLogger` to forward log output to your application's logging framework:

```kotlin
val client = MqttClient("production") {
    logLevel = MqttLogLevel.WARN
    logger = object : MqttLogger {
        override fun log(level: MqttLogLevel, tag: String, message: String, throwable: Throwable?) {
            // Forward to your logging framework (e.g., Timber on Android)
            when (level) {
                MqttLogLevel.ERROR -> Timber.tag(tag).e(throwable, message)
                MqttLogLevel.WARN -> Timber.tag(tag).w(throwable, message)
                else -> Timber.tag(tag).d(message)
            }
        }
    }
}
```

### Zero overhead

When `logger` is `null` or `logLevel` is `NONE`, log message lambdas are never invoked — no string allocation or concatenation occurs. This makes it safe to leave detailed log points in production code.

---

## Endpoints

An `MqttEndpoint` describes how to reach the broker. Pass it to `client.connect()`.

### TCP

Used by JVM, Android, iOS, macOS, Linux, and Windows targets:

```kotlin
// Plain TCP
client.connect(MqttEndpoint.Tcp("broker.example.com"))

// Custom port
client.connect(MqttEndpoint.Tcp("broker.example.com", port = 1884))

// TLS
client.connect(MqttEndpoint.Tcp("broker.example.com", port = 8883, tls = true))
```

Certificate validation uses the platform's default trust store.

### WebSocket

Used by browser/wasmJs targets:

```kotlin
// Secure WebSocket
client.connect(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt"))

// Plain WebSocket
client.connect(MqttEndpoint.WebSocket("ws://broker.example.com/mqtt"))

// Custom sub-protocols (default is ["mqtt"])
client.connect(MqttEndpoint.WebSocket(
    url = "wss://broker.example.com/mqtt",
    protocols = listOf("mqtt", "mqttv5"),
))
```

### URI parsing

Parse a URI string into the appropriate endpoint type:

```kotlin
val tcp = MqttEndpoint.parse("tcp://broker.example.com:1883")
val tls = MqttEndpoint.parse("ssl://broker.example.com:8883")
val ws  = MqttEndpoint.parse("wss://broker.example.com/mqtt")
```

### Supported URI schemes

| Scheme | Transport | Default Port | TLS |
|--------|-----------|:------------:|:---:|
| `tcp://` | TCP | 1883 | No |
| `mqtt://` | TCP | 1883 | No |
| `ssl://` | TCP | 8883 | Yes |
| `tls://` | TCP | 8883 | Yes |
| `mqtts://` | TCP | 8883 | Yes |
| `ws://` | WebSocket | — | No |
| `wss://` | WebSocket | — | Yes |

> **Note:** For WebSocket schemes (`ws://`, `wss://`), the full URL (including path) is passed through as-is. The port is part of the URL, not extracted separately.

### Resource management

Use the `use` extension for automatic connect/close:

```kotlin
MqttClient("sensor").use(MqttEndpoint.parse("tcp://broker:1883")) { client ->
    client.subscribe("sensors/#", QoS.AT_LEAST_ONCE)
    client.publish("sensors/temp", "22.5")
    delay(10_000)
}  // client.close() is called automatically
```

---

## Full Configuration Reference

Every `MqttConfig` property at a glance:

| Property | Type | Default | Range | Description |
|----------|------|---------|-------|-------------|
| `clientId` | `String` | `""` | — | Client identifier sent in CONNECT. Empty requests broker-assigned ID. |
| `keepAliveSeconds` | `Int` | `60` | 0..65,535 | Maximum seconds between control packets. `0` disables keepalive. |
| `cleanStart` | `Boolean` | `true` | — | If `true`, discard existing session state on connect. |
| `username` | `String?` | `null` | — | Username for password-based authentication. |
| `password` | `ByteString?` | `null` | — | Password as immutable bytes. Use `password("str")` in builder. |
| `will` | `WillConfig?` | `null` | — | Will message published on unexpected disconnect. Use `will { }` in builder. |
| `sessionExpiryInterval` | `Long?` | `null` | 0..4,294,967,295 | Seconds broker retains session after disconnect. `null` = broker default. |
| `receiveMaximum` | `Int` | `65535` | 1..65,535 | Max concurrent in-flight QoS 1/2 messages client can process. |
| `maximumPacketSize` | `Long?` | `null` | 1..4,294,967,295 | Max MQTT packet size in bytes client accepts. `null` = no limit. |
| `topicAliasMaximum` | `Int` | `0` | 0..65,535 | Max topic aliases from broker. `0` = not supported. |
| `requestResponseInformation` | `Boolean` | `false` | — | Request Response Information in CONNACK. |
| `requestProblemInformation` | `Boolean` | `true` | — | Allow Reason String and User Properties on failure packets. |
| `userProperties` | `List<Pair<String, String>>` | `emptyList()` | — | Key-value metadata sent with CONNECT. Keys may repeat. |
| `authenticationMethod` | `String?` | `null` | — | Enhanced auth method (e.g., `"SCRAM-SHA-256"`). |
| `authenticationData` | `ByteString?` | `null` | — | Initial data for enhanced authentication. |
| `autoReconnect` | `Boolean` | `true` | — | Automatically reconnect with exponential backoff on connection loss. |
| `reconnectBaseDelayMs` | `Long` | `1000` | > 0 | Initial reconnect delay in milliseconds. |
| `reconnectMaxDelayMs` | `Long` | `30000` | ≥ `reconnectBaseDelayMs` | Maximum reconnect delay cap in milliseconds. |
| `defaultQos` | `QoS` | `AT_MOST_ONCE` | — | Default QoS for convenience `publish()` calls. |
| `defaultRetain` | `Boolean` | `false` | — | Default retain flag for convenience `publish()` calls. |
| `logger` | `MqttLogger?` | `null` | — | Logger implementation. `null` = no logging, zero overhead. |
| `logLevel` | `MqttLogLevel` | `NONE` | — | Minimum log level. `NONE` disables all logging. |
