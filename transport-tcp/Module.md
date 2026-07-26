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

### Trusting a private CA

If the broker's certificate is issued by a private or self-signed CA that is not in the platform
trust store, pass a TLS customisation lambda. It receives ktor's `TLSConfigBuilder`:

```kotlin
val client = MqttClient("sensor") {
    transportFactory = TcpTransportFactory { trustManager = myTrustManager }
}
```

The lambda is applied after the SNI server name is set and before platform trust configuration, so
on Android a trust manager installed here is wrapped in the hostname-aware delegate rather than
replacing it: the trust anchors you add stay subject to the platform's domain-specific
`network_security_config.xml` rules, certificate pinning, and Certificate Transparency policy. That
is all the wrapping buys — RFC 6125 subject-name matching comes from ktor and only when the SNI
server name is set, so it does not happen for IP-literal brokers, and the platform policy checks do
not happen at all on JVM or native targets. On Android the trust manager must be one the platform
can wrap (obtained from a `TrustManagerFactory`, not hand-implemented) or the handshake fails.
The added trust applies only to this MQTT connection — unlike Android's
app-wide `network_security_config.xml` trust anchors. `trustManager` itself is available on the
JVM and Android actuals of `TLSConfigBuilder`; on Apple, Linux, and Windows the lambda still runs,
but `TLSConfigBuilder` exposes a different set of properties there.

Available on JVM, Android, iOS, macOS, Linux, and Windows. Not available on the browser (wasmJs) —
use `mqtt-client-transport-ws` there.
