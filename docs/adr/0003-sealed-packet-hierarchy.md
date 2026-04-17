# 3. Sealed `MqttPacket` hierarchy with free-function codecs

## Status

Accepted.

## Context

MQTT 5.0 defines 15 control-packet types, each with its own variable header,
payload layout, and set of permitted properties. There are two idiomatic ways
to model this in Kotlin:

1. A `sealed interface MqttPacket` with one data class per packet type, plus
   free-function `encode`/`decode` codecs.
2. An abstract class with virtual `encode()`/`decode()` methods on each
   subtype.

Option 2 keeps data and behaviour together but forces every packet class to
carry I/O responsibilities and mix mutable encoder state into what should be
an immutable value.

## Decision

`MqttPacket` is a **sealed interface** with a data class per type. All codec
logic lives in top-level functions (`encodePacket`, `decodePacket`) in the
`packet` sub-package, dispatching on `when` over the sealed hierarchy. The
compiler enforces exhaustiveness: adding a packet type fails the build until
every codec branch is updated.

Properties are modelled once as a dedicated `MqttProperties` data class
shared across every packet type that uses them, rather than being duplicated
on each subclass.

## Consequences

**Positive**
- Packets are pure data: free to construct in tests, pattern-match in
  application code, and serialize with structural equality.
- Adding a new property or packet type is a mechanical, compiler-guided
  refactor — no virtual dispatch surprises.
- Codec behaviour is trivially testable: `encodePacket(packet).let(::decodePacket) == packet`.

**Negative**
- Free-function codecs mean the encoder/decoder files are large (hundreds of
  lines each). Mitigation: they are organised by packet type with clear
  section comments, and `detekt`'s cyclomatic-complexity rule is
  explicitly suppressed on the `when` dispatch.
- Consumers who want to "ask a packet to encode itself" must call the free
  function instead. In practice, consumers don't touch packets directly —
  they use the high-level `MqttClient` API.
