# Module transport-tcp

Raw-TCP transport for the MQTTastic client, with optional TLS, built on `ktor-network`.

Add the dependency and supply [org.meshtastic.mqtt.transport.tcp.TcpTransportFactory] to the
client config:

```kotlin
val client = MqttClient("sensor") {
    transportFactory = TcpTransportFactory()
}
client.connect(MqttEndpoint.Tcp("broker.example.com", port = 8883, tls = true))
```

Available on JVM, Android, iOS, macOS, Linux, and Windows. Not available on the browser (wasmJs) —
use `mqtt-client-transport-ws` there.
