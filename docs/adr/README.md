# Architecture Decision Records

This directory contains ADRs — short documents capturing architecturally-significant
decisions made on MQTTastic Client KMP, the context in which they were made, and
their consequences.

Format: [Michael Nygard's template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

| # | Title | Status |
|---|---|---|
| [0001](0001-transport-abstraction.md) | Transport abstraction as a pure-common interface | Accepted (partially superseded by 0006) |
| [0002](0002-zero-dependency-policy.md) | Zero non-essential dependencies in `commonMain` | Accepted |
| [0003](0003-sealed-packet-hierarchy.md) | Sealed `MqttPacket` hierarchy with free-function codecs | Accepted |
| [0004](0004-qos-state-machine.md) | In-memory QoS 1/2 state machine with session resumption hooks | Accepted |
| [0005](0005-coroutines-and-flow-api.md) | Coroutines-first public API (`suspend` + `Flow`) | Accepted |
| [0006](0006-multi-module-distribution.md) | Multi-module distribution with a public transport SPI | Accepted |
| [0007](0007-mutex-send-serialization.md) | Single-`Mutex` send serialization | Accepted |
| [0008](0008-public-api-allowlist.md) | Internal-by-default with an explicit public-API allowlist | Accepted |
| [0009](0009-dual-protocol-version-support.md) | Dual MQTT 3.1.1 + 5.0 support with automatic fallback | Accepted |
| [0010](0010-packet-id-allocation.md) | Monotonic, `Mutex`-guarded packet-ID allocation | Accepted |
