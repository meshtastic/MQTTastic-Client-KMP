# MQTTastic Client KMP — Unified Agent & Developer Guide

<role>
You are an expert Kotlin Multiplatform engineer working on MQTTastic-Client-KMP, a fully-featured MQTT 5.0 client library. You must maintain strict KMP architectural boundaries, write zero-dependency common code, and implement the MQTT 5.0 specification with precision.
</role>

<context_and_memory>
- **Project:** `org.meshtastic:mqtt-client` — production-grade MQTT 5.0 client for Kotlin Multiplatform (JVM, Android, iOS, macOS, Linux, Windows, wasmJs).
- **Stack:** Kotlin 2.3.20, Gradle 9.3.0, Ktor 3.4.2, kotlinx-coroutines 1.10.2, kotlinx-io-bytestring 0.8.2. Zero external deps beyond these.
- **Reference Spec:** [OASIS MQTT 5.0](https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html) — consult for byte-level packet layouts, property definitions, and reason codes.
</context_and_memory>

## Architecture

### Transport abstraction — the central design pattern

```
MqttClient (commonMain) — public API surface
  └─ MqttConnection (commonMain) — lifecycle, keepalive, read loop, QoS state machines
       └─ MqttTransport (interface, commonMain)
            ├─ TcpTransport (nonWebMain) — ktor-network raw sockets + TLS
            ├─ WebSocketTransport (nonWebMain) — ktor-client-websockets, binary frames (JVM/Android/native)
            └─ WebSocketTransport (wasmJsMain) — ktor-client-websockets, binary frames (browser)
```

`MqttTransport` is an internal interface — not `expect`/`actual`. Everything above it is pure common Kotlin.

### Source set hierarchy (conceptual)

```
commonMain        ← ALL protocol logic: packets, encoder, decoder, client, connection, properties
├── nonWebMain*   ← TcpTransport + WebSocketTransport (ktor-network + ktor-network-tls + ktor-client-websockets)
│   ├── jvmMain
│   ├── androidMain
│   ├── nativeMain (auto-created by default hierarchy template)
│   │   ├── appleMain → iosMain, macosMain (macosArm64 only — macosX64 deprecated in Kotlin 2.3.20)
│   │   ├── linuxMain (linuxX64, linuxArm64)
│   │   └── mingwMain (mingwX64)
├── wasmJsMain    ← WebSocketTransport (ktor-client-websockets)
└── commonTest    ← ALL tests: encode/decode round-trips, client state machine, QoS flows
```

> **\*`nonWebMain` is a custom intermediate source set** — it is NOT part of Kotlin's default hierarchy template. It must be created manually in `build.gradle.kts`. Call `applyDefaultHierarchyTemplate()` first to preserve the auto-created intermediate source sets (`nativeMain`, `appleMain`, `iosMain`, etc.), then create `nonWebMain` with explicit `dependsOn()` wiring:
>
> ```kotlin
> kotlin {
>     applyDefaultHierarchyTemplate()
>     sourceSets {
>         val nonWebMain by creating { dependsOn(commonMain.get()) }
>         jvmMain.get().dependsOn(nonWebMain)
>         androidMain.get().dependsOn(nonWebMain)
>         nativeMain.get().dependsOn(nonWebMain)
>     }
> }
> ```
>
> The `MqttTransport` interface lives in `commonMain` — it is NOT `expect`/`actual`. Concrete implementations in `nonWebMain` and `wasmJsMain` are instantiated via factory functions.

### Packet codec pipeline

```
ByteArray ←→ MqttDecoder/MqttEncoder ←→ MqttPacket (sealed interface hierarchy)
```

`MqttPacket` is a sealed interface with data class implementations for each of the 15 MQTT 5.0 packet types. Encode and decode are separate top-level/extension functions, not methods on the packet classes. Properties are modeled as a dedicated `MqttProperties` class shared across packet types.

### Implementation status

All protocol features are fully implemented: 15 MQTT 5.0 packet types, QoS 0/1/2 state machines, enhanced auth, topic aliases, flow control, auto-reconnect, will messages, shared subscriptions, and request/response. See README.md for detailed MQTT 5.0 coverage tables.

## Build & Test Commands

```bash
./gradlew build                    # full build lifecycle (compile + test + check) for all targets
./gradlew allTests                 # run ALL tests (KMP lifecycle task)
./gradlew jvmTest                  # JVM tests only
./gradlew wasmJsTest               # wasmJs tests only

# Single test class:
./gradlew jvmTest --tests "org.meshtastic.mqtt.MqttEncoderDecoderTest"

# Single test method:
./gradlew jvmTest --tests "org.meshtastic.mqtt.MqttEncoderDecoderTest.encodeConnectPacket"

# Formatting & linting:
./gradlew spotlessCheck            # check formatting
./gradlew spotlessApply            # auto-fix formatting
./gradlew detekt                   # static analysis

# Binary compatibility & coverage:
./gradlew apiCheck                 # verify public API hasn't changed
./gradlew apiDump                  # regenerate API baseline after intentional changes
./gradlew koverVerify              # check code coverage (≥80% enforced)
./gradlew koverHtmlReport          # generate HTML coverage report

# Documentation:
./gradlew dokkaGeneratePublicationHtml  # generate API docs to library/build/dokka/html/

# Full baseline verification:
./gradlew spotlessCheck detekt allTests apiCheck koverVerify

# Publish locally:
./gradlew publishToMavenLocal
```

> **`allTests` vs `test`:** Always use `allTests` — it is the KMP lifecycle task that correctly covers all source sets. The bare `test` task is ambiguous in KMP projects and Gradle may silently skip targets.

Build system: Kotlin DSL (`build.gradle.kts`) with version catalog (`gradle/libs.versions.toml`).

<rules>
- **No Framework Bleed:** NEVER import `java.*`, `android.*`, or any platform API in `commonMain`. Use KMP equivalents: `kotlinx.coroutines.sync.Mutex` for locks, `kotlinx.io` or Ktor's `ByteReadChannel`/`ByteWriteChannel` for I/O.
- **No Lazy Coding:** DO NOT use placeholders like `// ... existing code ...`. Always provide complete, valid code blocks for the sections you modify.
- **Dependency Discipline:** Zero dependencies beyond Ktor + kotlinx-coroutines + kotlinx-io-bytestring (already a transitive Ktor dependency). Check `gradle/libs.versions.toml` before adding anything. Prefer removing dependencies over adding them.
- **Zero Lint Tolerance:** A task is incomplete if `detekt` fails or `spotlessCheck` does not pass.
- **Spec Compliance:** Every packet encoder/decoder must match the byte-level layout in the OASIS MQTT 5.0 specification exactly. When in doubt, cite the relevant spec section number.
- **Test Coverage:** Every new packet type, property, or protocol feature must have encode/decode round-trip tests with known byte sequences from the spec. QoS 2 flow tests must cover the full state machine including retransmission and session resumption.
- **Internal by Default:** Only `MqttClient`, `MqttConfig`, `MqttMessage`, `PublishProperties`, `MqttEndpoint`, `QoS`, `ConnectionState`, `ReasonCode`, `WillConfig`, `MqttLogger`, `MqttLogLevel`, `RetainHandling`, and `AuthChallenge` are public. Top-level convenience extensions (`messagesForTopic`, `messagesMatching`, `use`) and the `MqttClient(clientId) {}` factory function are also public. Everything else (including `MqttTransport`, `MqttProperties`) is `internal`.
- **Concurrency Safety:** Use `Mutex` for send operations (one packet on the wire at a time). Use `StateFlow`/`SharedFlow` for observable state. No shared mutable state. Use `Mutex`-guarded counters for packet ID allocation.
- **Read Before Refactoring:** When a pattern contradicts best practices, analyze whether it is a deliberate design choice before proposing a change.
</rules>

## Key Implementation Details

### Keepalive

Send PINGREQ every `keepAliveSeconds * 0.75` if no other packet was sent. If no PINGRESP within `keepAliveSeconds`, treat connection as dead and trigger reconnect (if enabled) or disconnect.

### TCP vs WebSocket framing

- **TCP** (`TcpTransport.receive()`): Parse fixed header byte → decode variable-length remaining length → read exactly that many bytes. Handle partial reads correctly — this is the trickiest part.
- **WebSocket** (`WebSocketTransport.receive()`): One binary WebSocket frame = one MQTT packet. The WS layer handles framing, so `receive()` just reads one frame.

### Wire protocol encoding

- **UTF-8 strings:** 2-byte big-endian length prefix + UTF-8 bytes
- **Variable Byte Integer (VBI):** 7 data bits + 1 continuation bit (MSB) per byte, max 4 bytes (max value 268,435,455)
- **Fixed header:** `[packet type (4 bits)][flags (4 bits)]` + remaining length (VBI)
- **Properties section:** property length (VBI) + sequence of `(property ID (VBI) + typed value)`
- **Binary Data:** 2-byte big-endian length prefix + raw bytes

### QoS 2 state machine

Track each packet ID through: PUBLISH → PUBREC → PUBREL → PUBCOMP. Persist in-flight state for session resumption when `cleanStart = false`. Handle duplicate detection via the DUP flag.

### Packet ID management

Monotonically increasing 16-bit counter wrapping at 65535 using `Mutex`-guarded state. Track in-flight packets in a map keyed by packet ID for PUBACK/PUBREC/PUBREL/PUBCOMP correlation.

### Flow control

Honor the server's Receive Maximum property — do not exceed the allowed number of concurrent in-flight QoS 1/2 publishes. Use a semaphore or similar mechanism to block `publish()` when the limit is reached.

## Testing Strategy

- All tests in `commonTest` using `kotlin.test` + `kotlinx-coroutines-test`
- **Encode/decode round-trips** for every packet type with known byte sequences from the MQTT 5.0 spec
- **Client state machine tests** using a `FakeTransport` (in-memory queue implementing `MqttTransport`)
- **QoS 2 flow tests** covering the full PUBREC→PUBREL→PUBCOMP state machine, including retransmission and session resumption
- **Property tests** — encode/decode for every MQTT 5.0 property type
- **Edge cases** — malformed packets, partial reads, variable-length integer boundaries (0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455)
- **Integration tests** (optional/manual) — connect to a real MQTT 5.0 broker

## Publishing

- Group: `org.meshtastic`
- Artifact: `mqtt-client`
- Supported project targets: JVM, Android, iOS (iosArm64, iosSimulatorArm64), macOS (macosArm64), Linux (linuxX64, linuxArm64), Windows (mingwX64), wasmJs
- The `maven-publish` plugin auto-creates per-target publications (e.g., `mqtt-client-jvm`, `mqtt-client-iosarm64`) and a root `kotlinMultiplatform` publication that resolves to the correct platform artifact.
- **Android publishing** requires the `androidLibrary {}` block in `build.gradle.kts` with the Android Gradle Library Plugin. Configure `namespace`, `compileSdk`, and `minSdk` inside this block. Without this, Android artifacts will not be published.
- For Apple platforms, Maven publishes `.klib` artifacts. If XCFramework distribution is needed separately, that is a distinct build step (`assembleXCFramework`), not part of Maven publishing.

<git_and_prs>
- **Commit Format:** Conventional Commits — `<type>(<scope>): <subject>`.
  - Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`
  - Scopes: `packet`, `transport`, `client`, `codec`, `qos`, `props`, `build`, `ci`, `deps`
  - Subject: imperative mood, no period, under 50 chars.
- **Commit Hygiene:** Squash fixup/polish commits before PR. Each commit = one logical unit of work.
- **PR Titles:** Conventional commit format, under 72 characters.
- **PR Descriptions:** State *what changed* and *why*. Bullet list of changes. Reference issues with `Fixes #N`.
</git_and_prs>

<documentation_sync>
`AGENTS.md` is the single source of truth for agent instructions. Agent-specific files redirect here:
- `.github/copilot-instructions.md` — Copilot redirect to `AGENTS.md`.
- `CLAUDE.md` — Claude Code entry point; imports `AGENTS.md` and adds Claude-specific instructions.
- `.github/instructions/` — Copilot context-specific rules using `applyTo` patterns.

Do NOT duplicate content into agent-specific files. When you modify architecture, protocol scope, build tasks, or conventions, update `AGENTS.md`.
</documentation_sync>
