# Module mqtt-client

A fully-featured MQTT 5.0 client for Kotlin Multiplatform. One API across JVM,
Android, iOS, macOS, Linux, Windows, and `wasmJs`. Protocol logic lives in
`commonMain`; only transport primitives are platform-specific.

Start with [MqttClient][org.meshtastic.mqtt.MqttClient] and the
`MqttClient(clientId) { ... }` builder. Subscribe with
[subscribe][org.meshtastic.mqtt.MqttClient.subscribe] and collect
[messages][org.meshtastic.mqtt.MqttClient.messages].

See the [library README](https://github.com/meshtastic/MQTTastic-Client-KMP/blob/main/library/README.md)
for an end-to-end quickstart and the
[Architecture Decision Records](https://github.com/meshtastic/MQTTastic-Client-KMP/tree/main/docs/adr)
for the "why" behind the design.

# Package org.meshtastic.mqtt

Public MQTT 5.0 client API. Everything consumers need to connect, subscribe,
publish, and observe messages.

Key entry points:

- [MqttClient][org.meshtastic.mqtt.MqttClient] — the top-level client.
- [MqttConfig][org.meshtastic.mqtt.MqttConfig] — builder-style configuration.
- [MqttEndpoint][org.meshtastic.mqtt.MqttEndpoint] — TCP vs WebSocket, with
  `parse(uri)` for `tcp://`, `tls://`, `ws://`, `wss://` URIs.
- [MqttMessage][org.meshtastic.mqtt.MqttMessage] — immutable message model
  backed by [kotlinx.io.bytestring.ByteString].
- [QoS][org.meshtastic.mqtt.QoS] — quality-of-service levels.
- [MqttException][org.meshtastic.mqtt.MqttException] — sealed error hierarchy.
- [MqttLogger][org.meshtastic.mqtt.MqttLogger] — pluggable logging SAM.

# Package org.meshtastic.mqtt.packet

Internal packet codecs and wire-format helpers. Not part of the public API —
exposed here for Dokka completeness but marked `internal` at compile time.
