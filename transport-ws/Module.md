# Module transport-ws

Binary-WebSocket transport for the MQTTastic client, built on `ktor-client-websockets`.

Add the dependency and supply [org.meshtastic.mqtt.transport.ws.WebSocketTransportFactory] to the
client config:

```kotlin
val client = MqttClient("sensor") {
    transportFactory = WebSocketTransportFactory()
}
client.connect(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt"))
```

Available on every target, including the browser (wasmJs) — where it is the only supported
transport.
