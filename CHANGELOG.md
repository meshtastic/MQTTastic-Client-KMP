# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `MqttException.ConnectionFailed` — a new subtype for a connect attempt that failed before the
  broker accepted or refused it: DNS, TCP connect, TLS handshake, a socket that closed
  mid-handshake, or a CONNACK that never arrived.

### Changed

- **`connect()` no longer reports every failure as `MqttException.ConnectionRejected`.** It now
  classifies by which side of the handshake failed, because a consumer cannot write a correct retry
  policy without that distinction: a rejection means the broker read the CONNECT and said no, so
  retrying the same configuration is pointless, whereas a network failure is exactly what a retry
  is for. Previously both arrived as `ConnectionRejected`, so a TCP timeout or a TLS chain failure
  was indistinguishable from a bad password — a caller that stopped on `ConnectionRejected` gave up
  permanently on a transient network blip, and one that retried hammered a broker that would never
  accept it. Downstream consumers had to reach past the type and re-derive the answer from reason
  codes.

  `connect()` now throws:

  - `ConnectionRejected` only for a genuine CONNACK carrying an error reason code — including an
    unsupported protocol version that could not be renegotiated, and including a CONNACK whose
    reason code is `PROTOCOL_ERROR`, since the broker still answered. `serverReference` is
    preserved.
  - `ConnectionFailed` for transport and I/O failures, with the original platform exception kept as
    `cause`.
  - `ProtocolError` when the broker answered but violated the spec (a malformed CONNACK, an
    unexpected packet type mid-handshake).

  Reason codes alone could not express this — a broker may legitimately refuse a CONNECT with
  `PROTOCOL_ERROR`, and a transport failure has no broker reason code at all and synthesises
  `UNSPECIFIED_ERROR` — so the connection layer now records the failure's origin explicitly.

  **Migration:** callers matching `MqttException.ConnectionRejected` to stop retrying keep working
  and get more accurate: only real refusals land there now. Code that relied on `ConnectionRejected`
  as the catch-all for *any* connect failure should add a `ConnectionFailed` branch, or catch
  `MqttException`. Auto-reconnect behaviour is unchanged — it already classified by reason code and
  never stopped on a transport failure.

### Fixed

- MQTT 5.0 → 3.1.1 version negotiation now also triggers when a broker **silently closes** the
  connection on an MQTT 5.0 CONNECT instead of answering with an `UNSUPPORTED_PROTOCOL_VERSION`
  CONNACK. Many older brokers and gateways (observed live on `mqtt.defcon.run:4433`) drop the
  connection without any reply, which previously surfaced as a bare transport error
  (`EOFException: Not enough data available`) — the Meshtastic Android app misreported it as a
  credentials problem, while iOS clients, which speak 3.1.1 natively, connected fine. The client
  and `MqttClient.probe` now retry once with MQTT 3.1.1 when `negotiateVersion` is enabled (the
  default). The fallback is phase-gated: it fires only when the connection failed *after* the
  CONNECT packet was written and *before* a CONNACK arrived. DNS, TCP, and TLS failures — and
  CONNACK timeouts, where the broker kept the connection open — do not trigger it, so real network
  errors are never masked and dead hosts don't pay a doubled connect latency. Those failures are
  still reported accurately, as `ConnectionFailed` per the classification change above. The probe's
  retry runs within the remaining `timeoutMs` budget, preserving its total wall-clock contract.
  `probe` additionally gains the explicit `UNSUPPORTED_PROTOCOL_VERSION` fallback the client
  already had, so probing a 3.1.1-only broker now reports `Success` instead of `Rejected`.
- A failed handshake no longer leaks the socket. Rejections raised while awaiting CONNACK — an
  unexpected packet type, an AUTH packet during an MQTT 3.1.1 handshake — took a rethrow path that
  skipped transport cleanup, as did an invalid Maximum QoS value in the CONNACK. Handshake cleanup
  is now centralised and covers every rejection path.

## [0.7.0] - 2026-07-26

### Added

- `WebSocketTransportFactory` now accepts an optional TLS customisation lambda, the WebSocket
  counterpart to the `TcpTransportFactory` hook added in 0.6.0. Both take the same
  `TLSConfigBuilder` receiver, so a single trust manager can serve `ssl://` and `wss://` brokers
  behind a private or self-signed CA — scoped to the MQTT connection, with no app-wide
  `network_security_config.xml` anchor. The lambda runs on JVM, Android, Apple, and Linux; ignored
  on Windows (WinHttp exposes no TLS-configuration surface) and in the browser. The private-CA
  trust manager itself is available only on JVM and Android, where `TLSConfigBuilder` exposes
  `trustManager` — on Apple and Linux the lambda still runs but has no equivalent property to set.
  The existing no-arg constructor is unchanged (#107).

### Changed

- `:transport-ws` now selects its Ktor engine explicitly per platform (CIO on JVM/Android/Apple/
  Linux, WinHttp on Windows, Js on wasmJs) instead of relying on auto-detection. These are the
  engines the library itself ships for each target; auto-detection resolves via `ServiceLoader`
  over the *consuming app's* classpath, not the library's, so an app that also pulls in another
  Ktor engine (e.g. `ktor-client-okhttp`, common on Android) may previously have had its WebSocket
  MQTT connection served by that engine instead of CIO, with different proxy, DNS, and socket
  defaults. Such an app will now always get CIO for this transport.
- The detekt gate is now real (#110). The bare `detekt` task that CI and the documented local gate
  invoked is `NO-SOURCE` in every module — the plugin only recognises the JVM-style
  `src/main/kotlin` layout, and the per-source-set tasks it registers were never wired into
  `check` — so no static analysis had been running. A `detektAll` aggregator is now registered per
  module and wired into `check`, and the findings it surfaced are cleared. Those fixes are
  suppressions with written justifications plus one equivalent rewrite
  (`throw IllegalStateException(…)` → `error(…)`); no behaviour changed and no public API moved.

## [0.6.1] - 2026-07-26

**The published library artifacts are unchanged from 0.6.0.** `mqtt-client-core`,
`mqtt-client-transport-tcp`, `mqtt-client-transport-ws`, and `mqtt-client-bom` are
byte-identical; there is no reason to upgrade a consuming project from 0.6.0. This
release exists solely to produce the sample application artifacts that 0.6.0's release
run failed to build.

### Fixed

- The `:sample` module's `wasmJs` browser target had gone missing from
  `sample/build.gradle.kts`, orphaning the fully-populated `sample/src/wasmJsMain/`
  source set. Because the release workflow's Linux leg passes all of its sample tasks
  to a single Gradle invocation, the unresolvable `:sample:wasmJsBrowserDistribution`
  task aborted that invocation at task-selection time — so the 0.6.0 release shipped
  **no Android `.apk`, no Linux `.deb`, and no wasm browser bundle** (the macOS `.dmg`
  and Windows `.msi` legs were unaffected). The target is restored and all three
  artifacts build again (#105).

  Restoring it required moving `:transport-tcp` off the sample's `commonMain`: raw TCP
  cannot run in a browser, so that module deliberately omits the `wasmJs` target. The
  sample now selects its transports through a `platformTransportFactory()`
  `expect`/`actual` seam — TCP + WebSocket on Android, desktop, and iOS; WebSocket only
  in the browser. Sample-only; no library module changed.

## [0.6.0] - 2026-07-26

### Added
- **TLS trust customisation for the TCP transport** (#103). `TcpTransportFactory` now
  accepts an optional `configureTls: (TLSConfigBuilder.() -> Unit)?` lambda, so a caller
  can reach a broker whose certificate chain is anchored in a private or self-signed CA
  without replacing the transport wholesale:

  ```kotlin
  transportFactory = TcpTransportFactory { trustManager = myTrustManager } +
      WebSocketTransportFactory()
  ```

  The hook runs **before** platform trust is applied, so an explicitly supplied
  `trustManager` wins over the platform default. Purely additive: the no-argument
  constructor is retained, so existing `TcpTransportFactory()` call sites compile and
  link unchanged. On Android this replaces the previous workaround of opting the entire
  app into user-installed CAs via `network_security_config.xml`, which affected every
  HTTPS connection the app made. See `transport-tcp/Module.md` for the trust-ordering
  details and caveats.

### Changed
- The public API surface is now locked by `explicitApi()` plus checked-in ABI dumps for
  the JVM **and** klib (native/wasm) surfaces, enforced by `apiCheck` in CI (#87). No
  existing declaration changed — this guards the surface against accidental drift.
- `transport-ws` now has test coverage for `WebSocketTransportFactory`; the module
  previously shipped with no tests of its own (#94).
- Build tooling: AGP 9.3.1 (#100), Kover 0.9.9 (#84). **Kotlin remains 2.4.10**, so
  native and iOS consumers need no toolchain change when upgrading from 0.5.0.

### Security
- Every third-party GitHub Action is pinned to a full commit SHA, and OpenSSF Scorecard
  analysis now runs on the repository (#89, #96). CI-only — published artifacts are
  byte-for-byte unaffected.

## [0.5.0] - 2026-07-16

### Changed
- **Built with Kotlin 2.4.10** (#75, #76). Native and iOS consumers need a
  Kotlin 2.4.x toolchain to consume the published klibs; JVM and Android
  consumers are unaffected. This is the reason for the minor (rather than
  patch) version bump.
- Ktor 3.5.1 (#70), kotlinx-io-bytestring 0.9.1 (#71).
- Android target is now configured via the `android {}` block; the deprecated
  `androidLibrary {}` DSL is gone (#68). Build-internal; published artifacts
  are unchanged.
- Build tooling: Gradle 9.6.1 (#72), build tools updates (#73, #77),
  Develocity plugin 4.5.0 (#74).

### Security
- Pinned transitive npm `ws` to >= 8.21.0 in the wasm browser-test harness
  (memory-exhaustion DoS, Dependabot alert #2) (#79). Test-scope only — `ws`
  is not part of any published artifact.

## [0.4.0] - 2026-06-22

### Changed
- **BREAKING — split into per-transport modules.** The single `org.meshtastic:mqtt-client` artifact
  is replaced by a `:core` plus per-transport modules and a BOM (#27):
  - `org.meshtastic:mqtt-client-core` — all protocol logic + the transport SPI
  - `org.meshtastic:mqtt-client-transport-tcp` — `TcpTransport` (TCP/TLS; every target except browser)
  - `org.meshtastic:mqtt-client-transport-ws` — `WebSocketTransport` (every target incl. browser)
  - `org.meshtastic:mqtt-client-bom` — pins the above to one version

  Consumers now pull in only the transport(s) they use and must supply a factory:
  ```kotlin
  implementation(platform("org.meshtastic:mqtt-client-bom:<version>"))
  implementation("org.meshtastic:mqtt-client-core")
  implementation("org.meshtastic:mqtt-client-transport-tcp")
  // val client = MqttClient("id") { transportFactory = TcpTransportFactory() }
  ```
- **BREAKING — transport is now a public SPI.** `MqttTransport` and the new `MqttTransportFactory`
  (with a `+` combinator) are public; the `expect`/`actual` `createPlatformTransport` factory was
  removed. Set `MqttConfig.Builder.transportFactory` before connecting. The VBI framing helper
  `VariableByteInt`/`VbiResult` is now public so transport modules can frame packets. See
  [ADR-0006](docs/adr/0006-multi-module-distribution.md).

### Added
- `build-logic` convention plugins (`mqtt.kmp.library`, `mqtt.publishing`) and a multi-module Dokka
  + Kover aggregation at the root.
- ADRs 0006–0010 (multi-module distribution, `Mutex` send serialization, public-API allowlist,
  dual 3.1.1/5.0 support, packet-ID allocation); the Konsist architecture suite now enforces the
  public-API allowlist, packet internality/immutability, and the `:core` ⊥ transport build-graph
  boundary (#28, #29).

### Fixed
- Android TLS handshake to a private MQTT broker addressed by an IP literal failed with
  "Domain specific configurations require that hostname aware checkServerTrusted(...) is
  used". The hostname-aware trust manager is now installed for IP-literal hosts too, not
  only DNS names — SNI stays suppressed for IPs per RFC 6066 (#67, meshtastic/Meshtastic-Android#5894).

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
- **Single-source versioning** — version is derived from git tags at build time via `git describe`; `GROUP` and `POM_ARTIFACT_ID` live in `gradle.properties`; the sample Android app derives its `versionCode`/`versionName` from `project.version`
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
