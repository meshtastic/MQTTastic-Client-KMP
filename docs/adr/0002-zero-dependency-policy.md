# 2. Zero non-essential dependencies in `commonMain`

## Status

Accepted.

## Context

MQTT clients are often embedded in resource-constrained contexts: IoT
gateways, mobile apps shipping to stores with binary-size budgets, browsers
downloading `.wasm` payloads over cellular, and CLI tools that value fast
startup. Every transitive dependency we pull into `commonMain` ships to
every consumer of the library on every platform.

Shipping is forever. Removing a dep once it is in the public API is a major
version bump.

## Decision

`commonMain` depends on exactly three external artifacts:

1. `io.ktor:ktor-network` / `ktor-client-websockets` — only for the transport
   layer's I/O primitives (`ByteReadChannel`, `ByteWriteChannel`).
2. `org.jetbrains.kotlinx:kotlinx-coroutines-core` — for `suspend`, `Flow`,
   `Mutex`, and `StateFlow`.
3. `org.jetbrains.kotlinx:kotlinx-io-bytestring` — for the immutable
   `ByteString` used in the public `MqttMessage.payload` surface.

No logging framework, no serialization library, no reactive-streams adapter,
no DI container. Every feature that would normally pull in a dep is either
implemented directly (e.g. the `MqttLogger` interface) or punted to the
consumer (e.g. payload parsing is their responsibility).

## Consequences

**Positive**
- Consumers pay for only what they use. Adding MQTTtastic to an Android app
  does not drag in SLF4J, Jackson, RxJava, or a Netty subset.
- Supply-chain attack surface is minimal and fully auditable in
  `gradle/libs.versions.toml`.
- Version upgrades are low-risk: our three deps move at predictable cadences
  and have excellent compatibility stories.

**Negative**
- We re-implement small utilities (VBI encoding, topic-filter matching) that
  a bigger dep might provide. Mitigation: each utility is fully unit-tested
  and under 200 lines.
- We cannot assume consumers are using any particular logging or serialization
  stack, so the public API has to be framework-neutral — slightly more verbose
  for consumers who *are* already using those frameworks.
