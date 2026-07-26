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

## Trusting a private CA

If the broker's certificate is issued by a private or self-signed CA that is not in the platform
trust store, pass a TLS customisation lambda. It receives ktor's `TLSConfigBuilder`:

```kotlin
val client = MqttClient("sensor") {
    transportFactory = TcpTransportFactory { trustManager = myTrustManager }
}
```

The lambda is applied after the SNI server name is set and before platform trust configuration. On
Android that ordering means the trust manager on the builder is reached through the hostname-aware
`checkServerTrusted(chain, authType, hostname)` overload, which the platform requires whenever
`network_security_config.xml` holds any domain-specific configuration.

Be precise about what that does *not* buy you. Installing your own trust manager **replaces the
platform's trust decision**: your anchors are used instead of the platform's, and
`network_security_config.xml` anchors, certificate pinning, and Certificate Transparency policy are
then enforced only insofar as your manager enforces them itself. Those platform policies apply as
before only if you leave `trustManager` unset. Wrapping preserves the hostname-aware *call path*,
not the platform's *policy*. RFC 6125 subject-name matching is separate again — it comes from ktor
and only when the SNI server name is set, so it does not happen for IP-literal brokers, and no
platform wrapping happens at all on JVM or native targets.

On Android the manager must be one `X509TrustManagerExtensions` can wrap: either obtained from a
`TrustManagerFactory`, or declaring the three-arg `checkServerTrusted(chain, authType, host)` that
the platform looks up reflectively. Otherwise the handshake fails with an `IllegalArgumentException`
explaining this.

The added trust applies only to this MQTT connection — unlike Android's app-wide
`network_security_config.xml` trust anchors. `trustManager` itself is available on the JVM and
Android actuals of `TLSConfigBuilder`; on Apple, Linux, and Windows the lambda still runs, but
`TLSConfigBuilder` exposes a different set of properties there.

Available on JVM, Android, iOS, macOS, Linux, and Windows. Not available on the browser (wasmJs) —
use `mqtt-client-transport-ws` there.
