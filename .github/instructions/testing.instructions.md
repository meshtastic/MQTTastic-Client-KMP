---
applyTo: "**/commonTest/**/*.kt"
---

# Testing Rules

- Use `kotlin.test` + `kotlinx-coroutines-test`. No JUnit-specific APIs in common tests.
- **Encode/decode round-trips:** Every packet type must have tests encoding to bytes and decoding back, verifying against known byte sequences from the MQTT 5.0 spec.
- **FakeTransport:** Client state machine tests use a `FakeTransport` — an in-memory queue implementing `MqttTransport`. Do not use real network connections in unit tests.
- **QoS 2 flow tests:** Cover the full PUBLISH → PUBREC → PUBREL → PUBCOMP state machine, including retransmission, duplicate detection (DUP flag), and session resumption.
- **Property tests:** Encode/decode for every MQTT 5.0 property type.
- **Edge cases to always cover:**
  - Malformed packets (truncated, invalid type codes, wrong remaining length)
  - Variable-length integer boundary values (0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455)
  - Empty payloads, maximum-size payloads
  - UTF-8 strings with multi-byte characters
  - Packets with zero properties vs packets with many properties
