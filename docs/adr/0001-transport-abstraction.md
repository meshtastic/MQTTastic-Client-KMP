# 1. Transport abstraction as a pure-common interface

## Status

Accepted.

## Context

MQTT 5.0 is a wire protocol; it is indifferent to whether bytes flow over raw
TCP, TLS, or WebSocket frames. Kotlin Multiplatform, by contrast, cares deeply
about which source set a given API lives in — `java.net.Socket` cannot cross the
boundary into `commonMain`, and the browser's `wasmJs` target has no concept of
a raw socket at all.

We needed a single architectural seam that:

- Lets every byte of protocol logic (encoding, decoding, the client state
  machine, QoS handling, keepalive, auto-reconnect) live in `commonMain`, so
  every fix applies to all nine targets simultaneously.
- Supports multiple underlying transports (TCP, TLS-over-TCP, WebSocket) without
  leaking platform details into public API.
- Supports the `wasmJs` target, which only has WebSocket.

## Decision

`MqttTransport` is an **internal** interface declared in `commonMain`. It is
**not** an `expect`/`actual` declaration — that would couple the API surface to
platform availability. Instead, concrete implementations (`TcpTransport`,
`WebSocketTransport`) live in platform source sets and are selected at runtime
by a small `expect`/`actual` factory keyed on the `MqttEndpoint` type.

- `nonWebMain` (a custom intermediate source set shared by `jvmMain`,
  `androidMain`, and all `nativeMain` variants) provides both `TcpTransport`
  (Ktor `ktor-network` + `ktor-network-tls`) and `WebSocketTransport`
  (Ktor `ktor-client-websockets`).
- `wasmJsMain` provides only `WebSocketTransport`, backed by the browser
  WebSocket API via Ktor.
- `MqttClient` and `MqttConnection` hold a reference to `MqttTransport` and
  never know which implementation they have.

## Consequences

**Positive**
- ~95 % of the codebase lives in `commonMain`, including every test that
  matters (QoS state machine, encoder/decoder round-trips, keepalive timing).
- New transports (QUIC, Unix sockets, in-process testing) can be added without
  touching protocol logic.
- Tests use an in-memory `FakeTransport` to exercise the real client against
  scripted packet sequences — no network or timing flakes.

**Negative**
- Developers editing transport code must understand Kotlin's source-set
  hierarchy and `dependsOn` wiring, which is not the default template shape.
- The `wasmJs` target cannot be configured with a `tcp://…` URI at runtime;
  this is enforced by the factory throwing on unsupported endpoints rather
  than by a compile error.
