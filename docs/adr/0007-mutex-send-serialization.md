# 7. Single-`Mutex` send serialization

## Status

Accepted.

## Context

MQTT runs over a single ordered byte stream. Two coroutines writing a packet
concurrently would interleave bytes on the wire and corrupt the stream — there is
no framing that lets a broker recover from a half-written CONNECT spliced with a
PUBLISH. The client is explicitly coroutine-friendly: `publish`, `subscribe`,
`unsubscribe`, and the keepalive loop can all be invoked from different coroutines
at once.

We considered three ways to guarantee one-packet-at-a-time writes:

1. A dedicated writer coroutine fed by a `Channel<ByteArray>` (actor pattern).
2. A lock-free queue drained by a single consumer.
3. A `kotlinx.coroutines.sync.Mutex` held across each transport `send`.

## Decision

Each transport serializes writes with a `kotlinx.coroutines.sync.Mutex` held for
the duration of a single `send` (`sendMutex.withLock { … }`). The client similarly
guards connection lifecycle transitions with a `connectionMutex`. No raw bytes
reach the transport outside that lock.

`Mutex` won over the actor/queue approaches because:

- It keeps `publish` a straightforward `suspend` call whose completion means "the
  bytes are on the wire," rather than "the bytes are queued" — simpler error and
  back-pressure semantics for callers.
- It needs no extra long-lived coroutine or channel lifecycle to manage alongside
  the existing read loop.
- It is pure `commonMain` (`kotlinx.coroutines.sync`), with no platform primitives.

## Consequences

**Positive**
- Wire integrity is guaranteed by a single, easy-to-audit invariant.
- `publish`/`subscribe` semantics are synchronous-feeling and easy to reason about.
- Packet-ID allocation reuses the same `Mutex`-guarded discipline (see
  [0010](0010-packet-id-allocation.md)).

**Negative**
- A slow or blocked write holds the lock and serializes all senders behind it; the
  transport must enforce its own write timeouts so a wedged socket cannot stall the
  client indefinitely.
- Throughput is bounded by single-stream write latency — acceptable for MQTT, which
  is a single ordered connection by design.
