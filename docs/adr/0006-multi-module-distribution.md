# 6. Multi-module distribution with a public transport SPI

## Status

Accepted. Supersedes the packaging/visibility decisions in
[0001](0001-transport-abstraction.md).

## Context

The library originally shipped as a single artifact (`org.meshtastic:mqtt-client`)
whose `commonMain` baked in **both** transports — `TcpTransport`
(`ktor-network` + `ktor-network-tls`) and `WebSocketTransport`
(`ktor-client-websockets`). Every consumer paid for both even when they used one:
a browser/wasmJs app that only speaks WSS still pulled in the TCP/TLS stack, and a
backend that only speaks TCP still pulled in the WebSocket client.

Transport selection was wired with an **`internal expect`/`actual`
`createPlatformTransport`** factory and an `internal` `MqttTransport` interface.
That mechanism is the reason the library could not be split without an API change:
Kotlin does not allow an `expect` declaration in one Gradle module with `actual`s
in another, and a separate `:transport-tcp` module cannot implement an `internal`
interface that lives in `:core`.

The sibling project [`meshtastic/meshtastic-sdk`](https://github.com/meshtastic/meshtastic-sdk)
already faces the same multi-transport problem (BLE / TCP / serial) and resolves it
with a `:core` + per-transport-module layout, a public `RadioTransport` SPI, a BOM,
and `build-logic` convention plugins. We adopt the same shape.

## Decision

Split the single `:library` into per-coordinate modules:

| Module | Artifact | Targets |
|---|---|---|
| `:core` | `org.meshtastic:mqtt-client-core` | all, incl. wasmJs |
| `:transport-tcp` | `org.meshtastic:mqtt-client-transport-tcp` | JVM, Android, Apple, Linux, Windows |
| `:transport-ws` | `org.meshtastic:mqtt-client-transport-ws` | all, incl. wasmJs |
| `:bom` | `org.meshtastic:mqtt-client-bom` | (Java platform) |

To make the split possible, the transport seam becomes **public API**:

- `MqttTransport` is now a **public** interface (the published SPI).
- `MqttTransportFactory` is a new public SPI; each transport module ships one
  (`TcpTransportFactory`, `WebSocketTransportFactory`), and they compose with `+`.
- `VariableByteInt` (the VBI framing helper `TcpTransport` needs to find packet
  boundaries on a byte stream) is public, mirroring `meshtastic-sdk`'s public
  `WireFraming`.
- The `expect`/`actual createPlatformTransport` factory is **removed**. The
  consumer supplies a factory via `MqttConfig.Builder.transportFactory`; `:core`
  has zero compile-time dependency on any transport module, and each transport
  module declares `api(project(":core"))`.

Shared build configuration (KMP target set, publishing coordinates derived from
the module name) lives in `build-logic` convention plugins (`mqtt.kmp.library`,
`mqtt.publishing`). The `:bom` pins every artifact to one version.

## Consequences

**Positive**
- Consumers depend only on the transport(s) they use; wasmJs apps no longer pull
  in `ktor-network`/TLS, and backends no longer pull in the WebSocket client.
- The architecture is enforceable in the build graph — `:core` declares no
  dependency on a transport module (verified by the architecture test suite).
- Per-module ABI baselines give a cleaner SemVer signal per coordinate.
- Future transports (QUIC, Unix sockets) ship as new modules without taxing
  existing consumers.

**Negative**
- **Breaking change.** The `org.meshtastic:mqtt-client` coordinate is replaced by
  `mqtt-client-core` + a transport module; consumers must update their dependency
  list and set `transportFactory`. Documented in CHANGELOG with a migration snippet.
- `MqttTransport` and `VariableByteInt` are now public surface and bound by SemVer.
- More publication coordinates, build files, and ABI baselines to maintain.
