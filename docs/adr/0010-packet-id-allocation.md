# 10. Monotonic, `Mutex`-guarded packet-ID allocation

## Status

Accepted.

## Context

QoS 1 and QoS 2 publishes, plus SUBSCRIBE/UNSUBSCRIBE, carry a 2-byte Packet
Identifier (§2.2.1). An identifier must be unique among the packets currently
in flight for a connection and is released once its acknowledgement
(PUBACK/PUBCOMP/SUBACK/UNSUBACK) arrives. The valid range is 1..65,535 (0 is
reserved), so the allocator must wrap and must not hand out an ID that is still
in flight. Allocation can be requested concurrently from multiple coroutines
(see [0007](0007-mutex-send-serialization.md)).

## Decision

A monotonic 16-bit counter, guarded by a `kotlinx.coroutines.sync.Mutex`,
allocates IDs. It increments and wraps from 65,535 back to 1 (never 0), and
skips any value currently recorded as in flight in the in-flight map keyed by
packet ID. The same map correlates incoming acknowledgements
(PUBACK/PUBREC/PUBREL/PUBCOMP) back to their originating request.

## Consequences

**Positive**
- Pure `commonMain`, no platform atomics — the `Mutex` discipline matches the
  one used for send serialization, so there is a single concurrency model to
  reason about.
- Wrapping + skip-in-flight guarantees correctness even on long-lived, high-
  throughput connections.

**Negative**
- Allocation is serialized on the mutex; acceptable because packet emission is
  already serialized on the wire.
- If every one of the 65,535 IDs were simultaneously in flight, allocation would
  have nowhere to go — bounded in practice by the broker's Receive Maximum
  (§3.1.2.11.4), which the client honors to cap concurrent in-flight publishes.
