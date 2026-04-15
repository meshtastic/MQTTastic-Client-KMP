---
applyTo: "**/packet/**/*.kt,**/codec/**/*.kt"
---

# Packet Codec Rules

- Every encoder/decoder MUST match the byte-level layout in the OASIS MQTT 5.0 specification exactly.
- When implementing or modifying a packet codec, cite the relevant spec section (e.g., §3.1 CONNECT, §3.3 PUBLISH).
- `MqttPacket` is a sealed interface hierarchy — one data class implementation per packet type (15 total).
- Encode and decode are separate top-level functions, not methods on packet classes.
- Properties are modeled as a dedicated `MqttProperties` class, not scattered across packet subclasses.
- **UTF-8 strings:** 2-byte big-endian length prefix + UTF-8 bytes.
- **Variable Byte Integer (VBI):** 7 data bits + 1 continuation bit (MSB), max 4 bytes, max value 268,435,455.
- **Binary Data:** 2-byte big-endian length prefix + raw bytes.
- **Fixed header:** `[packet type (4 bits)][flags (4 bits)]` + remaining length (VBI).
- **Properties section:** property length (VBI) + sequence of `(property ID (VBI) + typed value)`.
- Every new packet type or property MUST have encode/decode round-trip tests with known byte sequences.
- Test VBI boundary values: 0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455.
