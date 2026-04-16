# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-04-16

### Added
- **Full MQTT 5.0 client** — complete implementation of all 15 packet types with encode/decode
- **`MqttClient`** — public API with `connect`, `disconnect`, `publish`, `subscribe`, `unsubscribe`, `close`
- **`MqttConnection`** — internal connection manager with QoS 0/1/2 state machines, keepalive, read loop
- **`MqttConfig`** — configuration data class with all CONNECT packet fields + `MqttConfig.build {}` DSL
- **`WillConfig`** — will message configuration with Will Delay Interval and Will Properties
- **`MqttMessage`** — message data class with `ByteString` payload and MQTT 5.0 `PublishProperties`
- **`MqttEndpoint`** — sealed interface for TCP and WebSocket connection endpoints
- **`QoS`** — enum with all three MQTT quality of service levels
- **`ConnectionState`** — enum for observable client state
- **`MqttLogger`** — public logging interface with configurable log levels (TRACE→NONE)
  - `MqttLogger.println()` — simple console logger for debugging
  - `MqttLogger.noop()` — explicitly silent logger
  - `MqttLoggerInternal` — zero-cost inline lambda filtering
- **`MqttProperties`** — 28-property model covering all MQTT 5.0 properties with encode/decode
- **`ReasonCode`** — 43 MQTT 5.0 reason codes
- **`MqttPacket`** — sealed interface hierarchy for all 15 packet types
- **`MqttEncoder`** / **`MqttDecoder`** — packet encode/decode functions
- **`PacketType`** — enum covering all 15 MQTT 5.0 packet types with fixed-header flag validation (§2.1.3)
- **`VariableByteInt`** — encoder/decoder per MQTT 5.0 §1.5.5
- **`PacketIdAllocator`** — Mutex-guarded 16-bit packet ID counter
- **`TopicValidator`** — topic name and filter wildcard validation per §4.7
- **`RetainHandling`** — subscription retain handling options (§3.8.3.1)
- **`TcpTransport`** (nonWebMain) — TCP/TLS transport via ktor-network
- **`WebSocketTransport`** (wasmJsMain) — binary WebSocket transport via ktor-client
- **Automatic reconnection** with configurable exponential backoff and subscription re-establishment
- **Enhanced authentication** — AUTH packet challenge/response flow (§4.12)
- **Topic aliases** — bidirectional client↔server mapping (§3.3.2.3.4)
- **Flow control** — Receive Maximum enforcement (§3.3.4)
- **Request/Response** — set `PublishProperties.responseTopic` and `correlationData` on any publish (§4.10)
- **QoS 2 duplicate detection** — inbound packet ID tracking prevents duplicate delivery
- **`@Throws` annotations** on all public suspend functions for JVM/Swift interop
- **Builder DSL** — `MqttConfig.build { clientId = "x"; keepAliveSeconds = 30 }`
- **Spec-compliant validation** — reserved bits in CONNECT/CONNACK/SUBSCRIBE decoded packets (§3.1.2.3, §3.2.2.1, §3.8.3.1)
- **Build tooling** — Binary Compatibility Validator, Dokka, Kover (≥80% line coverage)
- Input validation on all public types (port range, topic alias, subscription identifiers, etc.)
- KDoc on all public API types with spec references, examples, and parameter documentation
- **Consumer convenience APIs:**
  - `MqttMessage.payloadAsString()` — decode UTF-8 payload without boilerplate
  - `MqttEndpoint.parse(uri)` — parse `tcp://`, `ssl://`, `mqtts://`, `tls://`, `ws://`, `wss://` URIs
  - `MqttClient(clientId) { ... }` — factory function combining client ID with config DSL
  - `client.messagesForTopic(topic)` — exact-match filtered message flow
  - `client.messagesMatching(filter)` — wildcard-aware message flow (`+`, `#`)
  - `client.publish(topic, payload, qos, properties)` — string publish with optional properties
  - `client.use(endpoint) { ... }` — structured connect/close lifecycle
- 345 tests across 17 test classes covering encode/decode, client state machine, QoS flows, properties, logging, convenience APIs
- Integration test suite (Docker-based Mosquitto broker)

### Changed
- **Ktor-aligned API surface** — DSL patterns now mirror Ktor's `HttpClient { }` conventions:
  - `@MqttDsl` annotation on builder classes (like `@KtorDsl`) for scope safety
  - `will { topic = ...; payload("...") }` nested DSL block in `MqttConfig.Builder`
  - `WillConfig.build { }` companion factory
  - `defaultQos` and `defaultRetain` config properties for client-level publish defaults (like Ktor's `defaultRequest {}`)
  - Expanded `MqttClient` class KDoc to comprehensive IDE getting-started guide
- `publish(topic, String)` convenience now takes nullable `qos: QoS?` and `retain: Boolean?` — `null` falls through to `MqttConfig.defaultQos`/`defaultRetain`
- **API simplification** — consolidated publish API from 4 overloads to 2 (`publish(MqttMessage)` + `publish(topic, String, qos, retain, properties)`)
- Moved `ReasonCode` and `RetainHandling` from `org.meshtastic.mqtt.packet` to `org.meshtastic.mqtt` — consumers import from the main package
- Made internal buffer constants (`MESSAGE_BUFFER_CAPACITY`, `AUTH_BUFFER_CAPACITY`, `MAX_REDIRECTS`) private

### Removed
- `publish(topic, ByteArray, qos, retain)` — use `publish(MqttMessage(topic, byteArrayPayload))` instead
- `publishWithResponse(...)` — use `publish(topic, payload, properties = PublishProperties(responseTopic = ...))` instead
- `subscribe(qos, vararg topics)` extension — use `subscribe(mapOf("a" to qos, "b" to qos))` instead
