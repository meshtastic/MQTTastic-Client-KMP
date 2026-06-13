# 8. Internal-by-default with an explicit public-API allowlist

## Status

Accepted.

## Context

The library targets nine platforms and is published to Maven Central, so every
`public` declaration is a SemVer commitment across all of them. The protocol
implementation is large — 15 packet types, a codec, properties, the connection
state machine — and almost none of it should be callable by consumers. Without a
deliberate stance, "public by accident" leaks (a helper made `public` to satisfy a
test, a packet field exposed for debugging) accumulate and become impossible to
remove without a breaking change.

## Decision

Everything is `internal` unless it appears on a short, explicit allowlist. The
public surface is:

`MqttClient`, `MqttConfig`, `MqttMessage`, `PublishProperties`, `MqttEndpoint`,
`QoS`, `ConnectionState`, `ReasonCode`, `WillConfig`, `MqttLogger`,
`MqttLogLevel`, `RetainHandling`, `AuthChallenge`, `MqttProtocolVersion`, the
transport SPI (`MqttTransport`, `MqttTransportFactory` and its `plus` operator —
see [0006](0006-multi-module-distribution.md)), the framing helper
`VariableByteInt`, and the convenience extensions (`messagesForTopic`,
`messagesMatching`, `use`) plus the `MqttClient(clientId) {}` factory. Transport
modules add `TcpTransport`/`TcpTransportFactory` and
`WebSocketTransport`/`WebSocketTransportFactory`.

This is enforced two ways:

- **`binary-compatibility-validator`** (`apiCheck`) fails CI when the published ABI
  drifts from the per-module `api/` baselines — the backstop that catches a leak
  once it ships into the dump.
- **A Konsist architecture suite** (`:core` `jvmTest`) fails on the *introducing*
  PR with a readable message: every top-level `public` declaration in `:core` must
  be on the allowlist, the `MqttPacket` hierarchy and codec stay `internal`, and
  `:core` must not depend on a transport module.

## Consequences

**Positive**
- The bar for promoting a type to `public` is explicit and reviewable — adding to
  the allowlist is a one-line, intentional diff.
- `apiCheck` catches drift mechanically; the Konsist suite catches it earlier with
  a clearer error than an ABI dump diff.

**Negative**
- Contributors must mark new declarations `internal` or consciously allowlist them;
  the architecture test will fail otherwise. This is the intended friction.
- The allowlist lives in the test source and must be kept in step with the genuine
  public set (the `apiCheck` baseline is the second line of defense).
