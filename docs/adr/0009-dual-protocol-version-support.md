# 9. Dual MQTT 3.1.1 + 5.0 support with automatic fallback

## Status

Accepted.

## Context

The library leads with MQTT 5.0 — properties, reason codes, topic aliases,
enhanced auth, shared subscriptions. But a large installed base of brokers and
devices still speaks only MQTT 3.1.1, and the primary downstream consumer
(Meshtastic) connects to a mix. Supporting only 5.0 would shut those out;
maintaining a separate 3.1.1 client would duplicate the codec and state machine.

(This ADR records a decision the original issue framed as "should we add 3.1.1?".
By the time it was written, 3.1.1 was already implemented — this captures *why it
stays* and how it coexists with 5.0, rather than leaving it as an open question.)

## Decision

A single codec and connection state machine handle both versions, selected by a
public `MqttProtocolVersion` enum (`V3_1_1` = protocol level 4, `V5_0` = level 5)
threaded through the encoder, decoder, and `MqttConnection`.

- Default is `V5_0`. With `negotiateVersion = true` (the default), a broker that
  rejects the 5.0 CONNECT with `UNSUPPORTED_PROTOCOL_VERSION` triggers an
  automatic retry on a fresh transport as 3.1.1; the negotiated version is then
  remembered across reconnects.
- `MqttProtocolVersion.supportsProperties` gates 5.0-only wire features in the
  codec.
- `MqttConfig.validateV311Compatibility()` fails fast (eagerly when 3.1.1 is
  selected, and before a fallback retry) if a 5.0-only option is configured —
  session expiry, enhanced auth, user properties, topic aliases, maximum packet
  size, request-response-information, or a non-default receive maximum, plus
  5.0-only will properties.

## Consequences

**Positive**
- One implementation covers both protocol versions; fixes and tests apply to both.
- Consumers get transparent interop — point at any broker and the client settles
  on a version it accepts.
- 3.1.1's limitations surface as clear, eager validation errors rather than
  obscure broker rejections.

**Negative**
- The codec carries version conditionals, so contributors must consider both
  versions when touching wire format.
- Fallback opens a second connection attempt; the first transport is closed and a
  fresh one is created, costing one extra round trip when a broker rejects 5.0.
