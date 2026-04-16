# Topics, Subscriptions & Quality of Service

Topics are the core routing mechanism in MQTT — every message is published to a topic, and clients subscribe to topic filters to receive messages. This guide covers how to use topics, wildcards, subscriptions, and QoS levels effectively with MQTTtastic.

## Table of Contents

- [Topics](#topics)
  - [Topic Names](#topic-names)
  - [Topic Wildcards](#topic-wildcards)
  - [Shared Subscriptions](#shared-subscriptions)
- [Subscribing](#subscribing)
  - [Basic Subscription](#basic-subscription)
  - [Bulk Subscription](#bulk-subscription)
  - [Subscription Options (MQTT 5.0)](#subscription-options-mqtt-50)
  - [Unsubscribing](#unsubscribing)
- [Receiving Messages](#receiving-messages)
  - [Message Flow](#message-flow)
  - [Filtered Flows](#filtered-flows)
- [Publishing](#publishing)
  - [Basic Publishing](#basic-publishing)
  - [Publishing with Properties (MQTT 5.0)](#publishing-with-properties-mqtt-50)
  - [Request/Response Pattern](#requestresponse-pattern)
  - [Full Message Construction](#full-message-construction)
- [Quality of Service](#quality-of-service)
  - [QoS 0 — At Most Once](#qos-0--at-most-once)
  - [QoS 1 — At Least Once](#qos-1--at-least-once)
  - [QoS 2 — Exactly Once](#qos-2--exactly-once)
  - [QoS Comparison Table](#qos-comparison-table)
  - [Default QoS](#default-qos)
- [Retained Messages](#retained-messages)

---

## Topics

### Topic Names

A topic is a hierarchical UTF-8 string used to route messages from publishers to subscribers. Levels are separated by forward slashes (`/`), forming a tree structure similar to a filesystem path.

**Examples:**

```
home/livingroom/temperature
sensors/device-001/status
factory/line-3/machine-7/rpm
```

**Rules (MQTT 5.0 §4.7):**

- Topics are **case-sensitive**: `Sensor/Temperature` ≠ `sensor/temperature`
- Must be at least 1 character long
- Maximum length is **65,535 bytes** (UTF-8 encoded)
- Must not contain the **null character** (`U+0000`)
- A **leading `/`** is valid and creates an empty first level: `/finance/stock` has three levels (`""`, `finance`, `stock`)
- Topic names used in PUBLISH packets must not contain wildcard characters (`+` or `#`)

### Topic Wildcards

Wildcards allow a single subscription to match multiple topics. They are only valid in **subscription** topic filters — never in publish topics.

#### Single-Level Wildcard (`+`)

The `+` wildcard matches **exactly one topic level** (MQTT 5.0 §4.7.1.2). It can appear at any position in the topic filter.

```
sensors/+/temperature
```

| Topic                                  | Match? |
|----------------------------------------|--------|
| `sensors/kitchen/temperature`          | ✅ Yes  |
| `sensors/bedroom/temperature`          | ✅ Yes  |
| `sensors/floor1/kitchen/temperature`   | ❌ No — `+` matches only one level |
| `sensors/temperature`                  | ❌ No — missing a level |

Multiple `+` wildcards can appear in a single filter:

```
+/temperature/+      # matches "kitchen/temperature/celsius"
building/+/room/+    # matches "building/A/room/101"
```

#### Multi-Level Wildcard (`#`)

The `#` wildcard matches **any number of levels**, including zero (MQTT 5.0 §4.7.1.3). It must be the **last character** in the topic filter and must be preceded by `/` (or stand alone).

```
sensors/#
```

| Topic                                  | Match? |
|----------------------------------------|--------|
| `sensors`                              | ✅ Yes — zero levels after `sensors` |
| `sensors/temperature`                  | ✅ Yes  |
| `sensors/floor1/kitchen/temperature`   | ✅ Yes — any depth |

`#` alone matches **every topic**:

```
#    # receives all messages on the broker
```

#### Wildcard Rules

- Wildcards are **only valid in subscriptions**, never in publishes
- `+` can appear at **any level**: `+/temperature/+` is valid
- `#` must be at the **end**: `sensors/#` ✅ — `sensors/#/temperature` ❌
- `+` and `#` can be combined: `+/sensors/#` is valid
- `$`-prefixed topics (system topics) are **not** matched by `#` or `+` when they appear as the first level — subscribe explicitly (e.g., `$SYS/#`)

### Shared Subscriptions

Shared subscriptions (MQTT 5.0 §4.8.2) enable **load balancing** — each message is delivered to only **one** client in the group, rather than all of them.

**Format:**

```
$share/{groupName}/{topicFilter}
```

- `{groupName}` — an identifier for the subscriber group (no `/` characters)
- `{topicFilter}` — the actual topic filter (supports wildcards)

**Example:** Distribute job messages across worker instances:

```kotlin
// Three clients in the same group — each gets ~1/3 of messages
client1.subscribe("\$share/processors/events/#", QoS.AT_LEAST_ONCE)
client2.subscribe("\$share/processors/events/#", QoS.AT_LEAST_ONCE)
client3.subscribe("\$share/processors/events/#", QoS.AT_LEAST_ONCE)
```

All QoS levels are supported with shared subscriptions. The broker handles the distribution — no client-side coordination is required.

---

## Subscribing

### Basic Subscription

Subscribe to a single topic filter with a specific QoS level:

```kotlin
client.subscribe("sensors/temperature", QoS.AT_LEAST_ONCE)
```

The QoS in a subscription is the **maximum QoS** the broker will use when delivering messages on that filter. If a message was published at QoS 2 but you subscribed at QoS 1, the broker downgrades delivery to QoS 1.

### Bulk Subscription

Subscribe to multiple topic filters in a single SUBSCRIBE packet (MQTT 5.0 §3.8):

```kotlin
client.subscribe(mapOf(
    "sensors/temperature" to QoS.AT_LEAST_ONCE,
    "sensors/humidity" to QoS.EXACTLY_ONCE,
    "alerts/#" to QoS.AT_MOST_ONCE
))
```

Bulk subscriptions are more efficient than individual calls — they use a single round-trip to the broker.

### Subscription Options (MQTT 5.0)

MQTT 5.0 introduces per-subscription options beyond QoS (§3.8.3.1):

- **`noLocal`** — don't receive messages you published yourself (useful for bridging scenarios)
- **`retainAsPublished`** — preserve the original retain flag on delivered messages
- **`retainHandling`** — control when retained messages are sent:
  - `RetainHandling.SEND_AT_SUBSCRIBE` — send retained messages every time you subscribe (default)
  - `RetainHandling.SEND_IF_NEW_SUBSCRIPTION` — send only on the first subscribe (not on re-subscribe)
  - `RetainHandling.DO_NOT_SEND` — never send retained messages

```kotlin
client.subscribe(
    topicFilter = "sensors/#",
    qos = QoS.AT_LEAST_ONCE,
    noLocal = true,
    retainAsPublished = true,
    retainHandling = RetainHandling.SEND_IF_NEW_SUBSCRIPTION
)
```

### Unsubscribing

Remove subscriptions with UNSUBSCRIBE (MQTT 5.0 §3.10):

```kotlin
client.unsubscribe("sensors/temperature", "alerts/#")
```

After unsubscribing, the broker stops delivering messages for the removed filters. Any in-flight QoS 1/2 deliveries may still complete.

---

## Receiving Messages

### Message Flow

Incoming messages are exposed as a Kotlin `Flow`. Collect from `client.messages` to receive all messages across all active subscriptions:

```kotlin
client.messages.collect { msg ->
    println("${msg.topic}: ${msg.payloadAsString()}")
}
```

The flow emits `MqttMessage` instances containing the topic, payload (`ByteString`), QoS level, retain flag, and any MQTT 5.0 properties.

### Filtered Flows

#### Exact Topic Match

Use `messagesForTopic()` to filter messages by an exact topic string comparison:

```kotlin
client.messagesForTopic("sensors/temperature").collect { msg ->
    val temp = msg.payloadAsString().toDouble()
    println("Temperature: $temp°C")
}
```

#### Wildcard Match

Use `messagesMatching()` to evaluate MQTT wildcard rules (`+` and `#`) client-side against each incoming message's topic:

```kotlin
client.messagesMatching("sensors/+/temperature").collect { msg ->
    // msg.topic tells you which sensor (e.g., "sensors/kitchen/temperature")
    println("${msg.topic}: ${msg.payloadAsString()}")
}
```

> **Note:** `messagesForTopic()` does exact string comparison. `messagesMatching()` evaluates MQTT wildcard rules client-side. Both filter the same underlying message flow — the subscription itself must already cover the desired topics.

---

## Publishing

### Basic Publishing

Publish a string payload using the client-wide default QoS:

```kotlin
client.publish("sensors/temperature", "22.5")
```

Specify QoS and retain explicitly:

```kotlin
client.publish("sensors/temperature", "22.5", qos = QoS.EXACTLY_ONCE, retain = true)
```

### Publishing with Properties (MQTT 5.0)

MQTT 5.0 properties (§3.3.2.3) add metadata to published messages:

```kotlin
client.publish(
    topic = "sensors/temperature",
    payload = "22.5",
    qos = QoS.AT_LEAST_ONCE,
    properties = PublishProperties(
        messageExpiryInterval = 3600,  // expire after 1 hour
        contentType = "text/plain",
        userProperties = listOf("unit" to "celsius", "source" to "sensor-001")
    )
)
```

### Request/Response Pattern

MQTT 5.0 supports request/response via `responseTopic` and `correlationData` properties (§4.10):

```kotlin
// Requester
client.publish(
    topic = "services/math/add",
    payload = """{"a": 1, "b": 2}""",
    properties = PublishProperties(
        responseTopic = "responses/client-001/math",
        correlationData = ByteString("req-42".encodeToByteArray()),
        contentType = "application/json"
    )
)

// Listen for response
client.messagesForTopic("responses/client-001/math").collect { response ->
    val correlationId = response.properties.correlationData
        ?.toByteArray()?.decodeToString()
    println("Response for $correlationId: ${response.payloadAsString()}")
}
```

The responder reads `responseTopic` from the incoming message and publishes the result back to that topic, echoing the `correlationData` so the requester can match responses to requests.

### Full Message Construction

For complete control, construct an `MqttMessage` directly:

```kotlin
client.publish(MqttMessage(
    topic = "events/alert",
    payload = ByteString(
        """{"severity":"high","message":"Temperature exceeded threshold"}""".encodeToByteArray()
    ),
    qos = QoS.EXACTLY_ONCE,
    retain = false,
    properties = PublishProperties(
        contentType = "application/json",
        messageExpiryInterval = 86400,  // 24 hours
        userProperties = listOf("schema-version" to "2")
    )
))
```

---

## Quality of Service

MQTT defines three QoS levels that control delivery guarantees between a client and the broker (MQTT 5.0 §4.3). QoS is set **per message** (on publish) and **per subscription** (maximum QoS the broker will use for delivery).

### QoS 0 — At Most Once

**Fire and forget** — the message is delivered at most once with no acknowledgment (§4.3.1).

```
Publisher  ──PUBLISH──▶  Broker
                          (no response)
```

The message may be lost if the connection drops during transmission. No retry is attempted.

```kotlin
client.publish("sensors/heartbeat", "alive", qos = QoS.AT_MOST_ONCE)
```

**Best for:** High-frequency telemetry, sensor readings, or status heartbeats where occasional loss is acceptable and low overhead matters.

### QoS 1 — At Least Once

**Acknowledged delivery** — the broker confirms receipt with a PUBACK packet (§4.3.2).

```
Publisher  ──PUBLISH──▶  Broker
Publisher  ◀──PUBACK───  Broker
```

If the sender doesn't receive PUBACK, it retransmits the PUBLISH with the DUP flag set. This guarantees delivery but may result in **duplicate messages**.

```kotlin
client.publish("alerts/door-opened", "front-door", qos = QoS.AT_LEAST_ONCE)
```

**Best for:** Important notifications, state updates, or alerts where duplicates are acceptable but loss is not.

### QoS 2 — Exactly Once

**Four-step handshake** — guarantees the message arrives exactly once with no duplicates (§4.3.3).

```
Publisher  ──PUBLISH──▶  Broker
Publisher  ◀──PUBREC───  Broker     (received)
Publisher  ──PUBREL──▶   Broker     (release)
Publisher  ◀──PUBCOMP──  Broker     (complete)
```

This is the safest but most expensive QoS level — each message requires four packets and the broker must track state per packet ID.

If the broker responds to PUBLISH with a PUBREC containing an error reason code (≥ 0x80), the QoS 2 flow terminates immediately — no PUBREL is sent and the publish is considered failed.

```kotlin
client.publish("commands/firmware-update", firmwareUrl, qos = QoS.EXACTLY_ONCE)
```

**Best for:** Financial transactions, billing events, command execution, or any scenario where duplicate processing would cause harm.

### QoS Comparison Table

| | QoS 0 | QoS 1 | QoS 2 |
|---|---|---|---|
| **Guarantee** | At most once | At least once | Exactly once |
| **Packets** | 1 (PUBLISH) | 2 (PUBLISH + PUBACK) | 4 (PUBLISH + PUBREC + PUBREL + PUBCOMP) |
| **Duplicates** | No | Possible | No |
| **Message loss** | Possible | No | No |
| **Use case** | Telemetry | Notifications | Commands |
| **Overhead** | Lowest | Low | Highest |

### Default QoS

Set a client-wide default to avoid repeating the QoS on every publish:

```kotlin
val client = MqttClient("sensor-hub") {
    defaultQos = QoS.AT_LEAST_ONCE  // all publishes default to QoS 1
    defaultRetain = false
}

// These all use QoS 1:
client.publish("sensors/temp", "22.5")
client.publish("sensors/humidity", "65")

// Override for a specific publish:
client.publish("sensors/heartbeat", "alive", qos = QoS.AT_MOST_ONCE)
```

---

## Retained Messages

Retained messages (MQTT 5.0 §3.3.1.3) tell the broker to **store the last message** published to a topic. When a new client subscribes, the broker immediately delivers the retained message — the subscriber doesn't have to wait for the next publish.

```kotlin
// Publish retained device status — new subscribers get it immediately
client.publish("devices/thermostat/status", "online", retain = true)
```

**Clearing a retained message:** Publish an empty payload with `retain = true`:

```kotlin
client.publish("devices/thermostat/status", "", retain = true)
```

**Controlling retained message delivery:** Use the `retainHandling` subscription option to fine-tune when retained messages are sent:

```kotlin
// Only receive retained messages on the first subscribe, not on re-subscribe
client.subscribe(
    topicFilter = "devices/+/status",
    qos = QoS.AT_LEAST_ONCE,
    retainHandling = RetainHandling.SEND_IF_NEW_SUBSCRIPTION
)
```

> **Tip:** Retained messages are useful for "last known state" patterns — device status, configuration, or any value where new subscribers need the current state immediately without waiting for the next update.
