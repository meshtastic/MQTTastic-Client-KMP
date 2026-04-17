# 4. In-memory QoS 1/2 state machine with session resumption hooks

## Status

Accepted.

## Context

MQTT 5.0 §4.3 requires QoS 1 and QoS 2 publishes to be tracked end-to-end:

- **QoS 1** — `PUBLISH` → `PUBACK`. The sender must retain the message until
  the `PUBACK` arrives, retransmitting with `DUP=1` on reconnection.
- **QoS 2** — `PUBLISH` → `PUBREC` → `PUBREL` → `PUBCOMP`, with additional
  retransmission rules at every step.

Brokers advertise a `Receive Maximum` property that caps the number of
concurrent in-flight QoS 1/2 messages; clients must honour it.

The state machine can, in principle, be persisted across process restarts
(for `cleanStart = false` sessions). Persistence requires durable storage,
which is platform-specific and imposes runtime costs.

## Decision

The QoS state machine is implemented **in-memory** in `MqttConnection` using:

- A `Mutex`-guarded 16-bit packet-ID allocator (`PacketIdAllocator`) that
  wraps at 65,535.
- A `Map<PacketId, InFlightRecord>` keyed by packet ID, tracking the current
  phase (awaiting PUBACK, awaiting PUBREC, awaiting PUBCOMP).
- A `Semaphore` sized to the broker's `Receive Maximum`, blocking `publish()`
  when the limit is reached.

Session resumption across process restarts is **not** built in by default,
but the in-memory map is deliberately isolated behind a narrow interface so a
future `SessionStore` abstraction can plug in without changing the client's
public API.

## Consequences

**Positive**
- Zero I/O on the hot publish path. Every platform behaves identically.
- The happy-path and every retransmit edge case is exercised by tests against
  `FakeTransport`, including duplicate-detection via the `DUP` flag.
- A future persistence layer is additive, not a rewrite.

**Negative**
- Consumers relying on `cleanStart = false` today should not expect in-flight
  messages to survive a process restart. This is documented on `MqttConfig`.
- Long-lived processes with very bursty QoS 2 traffic hold the in-flight map
  in memory until the broker ACKs. Mitigation: the map is sized by
  `Receive Maximum`, which the broker controls.
