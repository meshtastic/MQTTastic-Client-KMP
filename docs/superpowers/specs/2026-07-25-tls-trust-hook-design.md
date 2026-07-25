# TLS trust configuration hook for the TCP transport

Design for [issue #102](https://github.com/meshtastic/MQTTastic-Client-KMP/issues/102).

## Problem

A caller cannot influence TLS trust for the TCP transport. Connecting to a broker whose
certificate chain is anchored in a private or self-signed CA — the common self-hosted case —
is therefore impossible without replacing the transport wholesale.

As of 0.5.0 there is no seam:

- `TcpTransportFactory` is `final` with a no-arg constructor; `TcpTransport` is `final` too.
- `MqttConfig.Builder` exposes no TLS property.
- `MqttEndpoint.Tcp(host, port, tls: Boolean)` carries only an on/off flag.
- ktor's `TLSConfigBuilder` is configured in a private lambda inside `TcpTransport.connect`
  (`transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt:102-108`),
  so it never reaches the caller.

The only workaround available to an Android consumer is to opt the *entire app* into trusting
user-installed CAs via `network_security_config.xml`, which applies to every HTTPS connection
the app makes rather than just the MQTT socket. The alternative — reimplementing
`MqttTransport` + `MqttTransportFactory` in the application — duplicates packet framing and
reconnect handling in every consumer that needs custom trust.

## Approach

Add an optional TLS-customisation lambda to `TcpTransportFactory`, threaded through to
`TcpTransport`. This is option 1 from the issue.

The issue's option 2 (a unified `MqttConfig.Builder.tlsConfig {}`) was rejected: it would pull
ktor TLS types into `:core`, breaking the transport-free boundary that ADR-0006 establishes and
that `core/build.gradle.kts`'s `verifyModuleBoundary` check plus the Konsist suite enforce.
There is also no shared lambda type to unify on — `:transport-ws` configures a ktor `HttpClient`
and never touches `TLSConfigBuilder`. Option 3 (making `TcpTransport` `open`) was rejected as a
strictly worse version of option 1.

## Public API

```kotlin
public class TcpTransportFactory(
    private val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransportFactory {
    override fun supports(endpoint: MqttEndpoint): Boolean = endpoint is MqttEndpoint.Tcp

    override fun create(endpoint: MqttEndpoint): MqttTransport = TcpTransport(configureTls)
}

public class TcpTransport(
    private val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransport
```

Both parameters are defaulted, so existing call sites are untouched. The hook composes with the
existing factory `+` operator:

```kotlin
transportFactory = TcpTransportFactory { trustManager = myTrustManager } + WebSocketTransportFactory()
```

`TcpTransport`'s constructor parameter is public for symmetry and for callers who construct the
transport directly, but the factory is the intended entry point.

## The seam

The TLS setup currently inlined in `TcpTransport.connect` moves into a single internal function:

```kotlin
internal fun TLSConfigBuilder.applyMqttTls(
    host: String,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
) {
    serverName = sniServerName(host)
    configureTls?.invoke(this)      // caller first…
    configurePlatformTrust(host)    // …so Android wraps their trustManager, not the reverse
}
```

`connect` then calls `applyMqttTls(endpoint.host, configureTls)` inside `rawSocket.tls(tlsContext) { … }`.

### Ordering: caller lambda runs *before* `configurePlatformTrust`

This corrects a contradiction in the issue text, which asks for the hook to run *after* the
platform defaults while also stating that a caller-supplied trust manager would "compose with
Android's hostname-aware checking rather than bypass it." Only one of those is achievable.

`configurePlatformTrust` on Android
(`transport-tcp/src/androidMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.android.kt:41-52`)
reads `trustManager` off the builder, falling back to the platform default, and wraps whatever it
finds in `HostnameAwareTrustManager`. Composition therefore requires the caller's assignment to
already be present — i.e. the caller's lambda must run first. Running it last would replace
`HostnameAwareTrustManager` outright and silently drop Android's 3-arg hostname verification.

Running the caller first delivers the intended behaviour: a private-CA trust manager is still
subject to the platform's hostname-aware check.

Ordering is enforced structurally rather than by convention — `applyMqttTls` is the only place the
order can be expressed, so there is no second call site to drift.

`serverName` remains first and is not exposed for override beyond what the lambda can already do;
a caller may reassign it inside the lambda if they need to.

## Build and compatibility

- `transport-tcp/build.gradle.kts`: `libs.ktor.network.tls` moves from `implementation` to `api`.
  A public signature now names `TLSConfigBuilder`, so consumers need it on their compile classpath.
  `libs.ktor.network` stays `implementation`.
- `./gradlew apiDump` regenerates `transport-tcp/api/transport-tcp.klib.api` and the JVM dump.
  Both are committed.
- Binary compatibility holds. Kotlin emits a zero-arg constructor for an all-defaults constructor,
  so already-compiled callers of `TcpTransportFactory()` keep linking.
- No change to `:core`, `:transport-ws`, or the BOM.

## Testing

Extends the pure-function style already in
`transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTlsTest.kt`.
`TLSConfigBuilder` is directly instantiable in common code, so no broker or socket is needed.

`commonTest`:

- `applyMqttTls` sets `serverName` to the host for a DNS name and to `null` for IPv4 and IPv6
  literals — the existing SNI-suppression guarantee, now asserted through the new entry point.
- The lambda is invoked exactly once (recorder counter).
- A `null` lambda is a no-op and leaves `serverName` behaviour unchanged.
- `TcpTransportFactory { }` still reports `supports()` correctly, `create()` returns a
  `TcpTransport`, and the instance composes with `WebSocketTransportFactory` via `+`.

`jvmTest`:

- A `trustManager` assigned inside the lambda is still the builder's `trustManager` afterwards.
  JVM's `configurePlatformTrust` is a no-op, so this proves the hook reaches ktor's real builder
  state rather than a discarded copy.

Not covered: the Android wrapping order. `X509TrustManagerExtensions` is an Android framework
class, so asserting that a caller's manager ends up inside `HostnameAwareTrustManager` requires an
instrumentation test. That is out of scope; the single-call-site structure of `applyMqttTls` is the
mitigation.

Existing `koverVerify` (≥80%), `detekt`, `spotlessCheck`, and `apiCheck` gates all apply.

## Documentation

- `transport-tcp/Module.md` — a private-CA usage snippet, and a note that the caller's
  configuration is applied before platform trust so Android hostname verification is preserved.
- `README.md` TLS section — a pointer to the hook, framed as the replacement for the app-wide
  `<certificates src="user"/>` workaround.
- `AGENTS.md` — no change needed; the public-surface list already covers transport modules
  generically via "Transport modules add `TcpTransport`/`TcpTransportFactory`…".

## Out of scope (YAGNI)

- No `host` parameter on the lambda — a factory can close over whatever it needs.
- No second, post-platform-trust seam.
- No WebSocket equivalent. `:transport-ws` would need a differently-typed `HttpClient` hook; it can
  be added later without disturbing this API.
- No `MqttConfig`-level plumbing or transport-neutral trust abstraction in `:core`.
