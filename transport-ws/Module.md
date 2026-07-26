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

## Trusting a private CA

If the broker's certificate is issued by a private or self-signed CA that is not in the platform
trust store, pass a TLS customisation lambda. It receives ktor's `TLSConfigBuilder` — the same type
`mqtt-client-transport-tcp` uses, so one trust manager can serve both transports:

```kotlin
val client = MqttClient("sensor") {
    transportFactory = TcpTransportFactory { trustManager = myTrustManager } +
        WebSocketTransportFactory { trustManager = myTrustManager }
}
```

The lambda is applied before platform trust configuration. On Android that ordering means the trust
manager on the builder is reached through the hostname-aware
`checkServerTrusted(chain, authType, hostname)` overload, which the platform requires whenever
`network_security_config.xml` holds any domain-specific configuration.

Be precise about what that does *not* buy you. Installing your own trust manager **replaces the
platform's trust decision**: your anchors are used instead of the platform's, and
`network_security_config.xml` anchors, certificate pinning, and Certificate Transparency policy are
then enforced only insofar as your manager enforces them itself. Those platform policies apply as
before only if you leave `trustManager` unset. Wrapping preserves the hostname-aware *call path*,
not the platform's *policy*.

On Android the manager must be one `X509TrustManagerExtensions` can wrap: either obtained from a
`TrustManagerFactory`, or declaring the three-arg `checkServerTrusted(chain, authType, host)` that
the platform looks up reflectively. Otherwise the handshake fails with an `IllegalArgumentException`
explaining this.

The added trust applies only to this MQTT connection — unlike Android's app-wide
`network_security_config.xml` trust anchors.

Where the hook takes effect depends on the platform's ktor engine:

| Target | Engine | `configureTls` |
| --- | --- | --- |
| JVM, Android | CIO | Honoured; `trustManager` available |
| iOS, macOS, Linux | CIO | Invoked, but `TLSConfigBuilder` has no `trustManager` there |
| Windows | WinHttp | **Ignored** — the engine exposes no TLS-configuration surface |
| Browser (wasmJs) | Js | **Ignored** — a page cannot influence TLS trust |

The two ignored cases are deliberately silent so that shared common code can pass the hook
unconditionally. On Windows in particular, a private CA must be installed in the system
certificate store instead.

This hook does not set the SNI server name itself: the CIO client derives SNI from the request URL,
which is also what gates ktor's RFC 6125 subject-name check. A `serverName` assigned inside the hook
does take precedence, though — it becomes the name the certificate is verified against, so it must
be present in the certificate's subject names.

Available on every target, including the browser (wasmJs) — where it is the only supported
transport.
