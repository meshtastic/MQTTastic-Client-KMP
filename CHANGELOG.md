# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-04-28

### Added
- **Full MQTT 3.1.1 protocol support** with seamless version auto-negotiation (#36)
  - All 15 encoder/decoder functions accept a `version` parameter
  - 3.1.1 wire protocol differences: no properties, 1-byte CONNACK return codes, QoS-only SUBSCRIBE options, body-less DISCONNECT, no AUTH packet
  - `negotiateVersion: Boolean = true` config flag — tries V5.0 first, falls back to V3.1.1 on `UNSUPPORTED_PROTOCOL_VERSION`
  - `negotiatedProtocolVersion` public property on `MqttClient`
  - New `MqttProtocolVersion` public enum (`V3_1_1`, `V5_0`)

### Fixed
- Correct decoding of 3.1.1-format CONNACK (2 bytes) when a V3.1.1-only broker rejects a V5.0 CONNECT (#36)

### Changed
- Bump Compose Multiplatform to 1.11.0-beta03, Material3 to 1.11.0-alpha07, Adaptive to 1.3.0-alpha07, compileSdk to 37 (#31)
- Bump Kotlin to 2.3.21
- Bump Ktor ecosystem to 3.4.3
- Bump AGP to 9.2.0
- Bump Develocity plugin to 4.4.1

## [0.2.0] - 2026-04-17

### Changed (BREAKING)
- **`ConnectionState` is now a `sealed class`** (was an `enum`) so disconnect / reconnect events
  carry diagnostic context. Pattern-match with `is` instead of `==`:
  - `Connecting` (object)
  - `Connected` (object)
  - `Reconnecting(attempt, lastError)` — current attempt count and last failure observed
  - `Disconnected(reason)` — `reason: MqttException?`. Use `Disconnected.Idle` for the
    no-reason singleton (idle/initial/intentional-close states).
- **Migration:** `state == ConnectionState.CONNECTED` → `state is ConnectionState.Connected`,
  `state == ConnectionState.DISCONNECTED` → `state is ConnectionState.Disconnected`.

### Added
- `MqttClient.probe(endpoint, timeoutMs, configure)` — one-shot connectivity diagnostic
  that performs a CONNECT/CONNACK handshake against an `MqttEndpoint`, classifies the
  outcome into a public `ProbeResult` sealed class (`Success`, `Rejected`, `DnsFailure`,
  `TcpFailure`, `TlsFailure`, `Timeout`, `Other`), and tears the transient connection
  back down. Designed for "Test Connection" affordances in consumer settings UIs.
- `ProbeResult` and `ProbeServerInfo` public types — `Success.serverInfo` exposes a
  curated subset of broker CONNACK properties (`assignedClientIdentifier`,
  `serverKeepAliveSeconds`, `maximumQosOrdinal`, `retainAvailable`, etc.) for capability
  diagnostics without leaking the internal `MqttProperties` shape.
- `DEFAULT_PROBE_TIMEOUT_MS` (5 000 ms) — public default for the probe wall-clock budget.
- `ConnectionState.Disconnected.reason`: surfaces the failure that caused an unexpected
  disconnect — `MqttException.ConnectionRejected` for broker rejections,
  `MqttException.ConnectionLost` for transport-side / protocol-violation tear-downs,
  including the server's `ReasonCode` from a server-initiated DISCONNECT (§4.13).
- `ConnectionState.Reconnecting.lastError`: the most recent reconnect attempt failure,
  enabling consumers to render meaningful "retrying — DNS failed (3/10)" UX.

## [0.1.0] - 2026-04-16

### Added
- **Full MQTT 5.0 client** — complete implementation of all 15 packet types with encode/decode
- **`MqttClient`** — public API with `connect`, `disconnect`, `publish`, `subscribe`, `unsubscribe`, `close`, and use-after-close protection
- **`MqttException`** — sealed exception hierarchy: `ConnectionRejected` (broker rejected CONNECT), `ConnectionLost` (unexpected disconnect), `ProtocolError` (malformed packets)
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
- **`WebSocketTransport`** (nonWebMain + wasmJsMain) — binary WebSocket transport on all platforms via ktor-client
- **Automatic reconnection** with configurable exponential backoff and subscription re-establishment
- **Enhanced authentication** — AUTH packet challenge/response flow (§4.12)
- **Topic aliases** — bidirectional client↔server mapping (§3.3.2.3.4)
- **Flow control** — Receive Maximum enforcement (§3.3.4)
- **Request/Response** — set `PublishProperties.responseTopic` and `correlationData` on any publish (§4.10)
- **QoS 2 duplicate detection** — inbound packet ID tracking prevents duplicate delivery
- **`@Throws` annotations** on all public suspend functions for JVM/Swift interop
- **Builder DSL** — `MqttConfig.build { clientId = "x"; keepAliveSeconds = 30 }`
- **Spec-compliant validation** — reserved bits in CONNECT/CONNACK/SUBSCRIBE decoded packets (§3.1.2.3, §3.2.2.1, §3.8.3.1)
- **Build tooling** — Binary Compatibility Validator, Dokka, Kover (≥80% line coverage), [Konsist](https://docs.konsist.lemonappdev.com/) architectural tests enforcing no `java.*`/`javax.*`/`android.*`/`platform.*` imports in `commonMain` and `internal` visibility on transport and property types
- **Single-source versioning** — `GROUP`, `POM_ARTIFACT_ID`, and `VERSION_NAME` live in `gradle.properties` and are consumed automatically by the vanniktech maven-publish plugin; the sample Android app derives its `versionCode`/`versionName` from the same property
- Input validation on all public types (port range, topic alias, subscription identifiers, `MqttMessage` topic must not be empty, etc.)
- KDoc on all public API types with spec references, examples, and parameter documentation
- **Consumer convenience APIs:**
  - `MqttMessage.payloadAsString()` — decode UTF-8 payload without boilerplate
  - `MqttEndpoint.parse(uri)` — parse `tcp://`, `ssl://`, `mqtts://`, `tls://`, `ws://`, `wss://` URIs
  - `MqttClient(clientId) { ... }` — factory function combining client ID with config DSL
  - `client.messagesForTopic(topic)` — exact-match filtered message flow
  - `client.messagesMatching(filter)` — wildcard-aware message flow (`+`, `#`)
  - `client.publish(topic, payload, qos, properties)` — string publish with optional properties
  - `client.use(endpoint) { ... }` — structured connect/close lifecycle
- **Ktor-aligned API surface** — DSL patterns mirror Ktor's `HttpClient { }` conventions with `@MqttDsl` scope safety
- `defaultQos` and `defaultRetain` config properties for client-level publish defaults
- 345 tests across 17 test classes covering encode/decode, client state machine, QoS flows, properties, logging, convenience APIs
- Integration test suite (Docker-based Mosquitto broker)
- **CI/CD** — GitHub Actions workflows for build/test matrix and Maven Central publishing, [CodeQL](https://codeql.github.com/) security scanning (`java-kotlin` + `actions`, weekly, `security-and-quality` queries), and a release pipeline that ships sample app artifacts (`.apk`, linux-x64/arm64 `.deb`, `.dmg`, `.msi`, and a `wasmJs` browser zip) across a per-OS runner matrix
- **Compose compiler metrics** — opt-in reports on the sample modules via `-PenableComposeMetrics=true`
