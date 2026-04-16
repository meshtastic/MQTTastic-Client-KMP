---
applyTo: "**/commonTest/**/*.kt"
---

# Testing Rules

- Use `kotlin.test` + `kotlinx-coroutines-test`. No JUnit-specific APIs in common tests.
- Use `FakeTransport` (in-memory queue) for client state machine tests — no real network connections.
- Every new packet type, property, or feature needs encode/decode round-trip tests with known byte sequences.
- Always cover VBI boundary values (0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455).
- Always cover edge cases: malformed packets, empty/max-size payloads, zero vs many properties.
- See AGENTS.md §Testing Strategy for the full testing approach.
