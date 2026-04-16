---
name: mqtt-kmp
description: 'MQTTtastic Client KMP — Kotlin Multiplatform MQTT 5.0 client library project knowledge'
---

# MQTTtastic Client KMP

## Project Facts
- **Language:** Kotlin 2.3.20 (Multiplatform)
- **Build:** Gradle Kotlin DSL, version catalog at `gradle/libs.versions.toml`
- **Targets:** jvm, android, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, linuxArm64, mingwX64, wasmJs
- **Dependencies:** Ktor 3.4.2, kotlinx-coroutines 1.10.2, kotlinx-io-bytestring 0.8.2 (zero external deps beyond these)
- **Protocol:** Full MQTT 5.0 (all 15 packet types, QoS 0/1/2, enhanced auth, topic aliases, flow control)

## Architecture
- `commonMain` — ALL protocol logic (packets, codec, client, connection, properties)
- `nonWebMain` — TCP + WebSocket transports (ktor-network, ktor-client-websockets)
- `wasmJsMain` — Browser WebSocket transport
- `MqttTransport` — internal interface, sole platform abstraction boundary
- Public API: `MqttClient`, `MqttConfig`, `MqttMessage`, `QoS`, `ConnectionState`, `ReasonCode`, `MqttEndpoint`

## Key Constraints
- No `java.*`, `android.*`, `platform.*` imports in `commonMain`
- No dependencies beyond Ktor + coroutines + kotlinx-io
- Packet codecs must match OASIS MQTT 5.0 byte-level spec exactly
- All new features need encode/decode round-trip tests
- Code coverage ≥80% enforced via koverVerify
- `spotlessCheck` + `detekt` + `apiCheck` must pass

## File Locations
| What | Where |
|------|-------|
| Protocol logic | `library/src/commonMain/kotlin/org/meshtastic/mqtt/` |
| Platform transports | `library/src/nonWebMain/kotlin/`, `library/src/wasmJsMain/kotlin/` |
| Tests | `library/src/commonTest/kotlin/` |
| Build config | `build.gradle.kts`, `gradle/libs.versions.toml` |
| Sample app | `sample/src/commonMain/kotlin/` |
| Agent instructions | `AGENTS.md` (source of truth), `.github/instructions/` |

## Build Commands
```bash
./gradlew build                    # full build + test + check
./gradlew allTests                 # all KMP tests
./gradlew jvmTest                  # JVM tests only
./gradlew spotlessApply            # auto-fix formatting
./gradlew detekt                   # static analysis
./gradlew apiCheck                 # public API compatibility
./gradlew koverVerify              # coverage check (≥80%)
```

## Git Conventions
- Conventional Commits: `<type>(<scope>): <subject>`
- Types: feat, fix, docs, style, refactor, test, chore
- Scopes: packet, transport, client, codec, qos, props, build, ci, deps
