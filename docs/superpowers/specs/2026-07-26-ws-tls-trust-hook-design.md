# TLS trust configuration hook for the WebSocket transport

Design for [issue #107](https://github.com/meshtastic/MQTTastic-Client-KMP/issues/107). Follow-on
to [#102 / #103](2026-07-25-tls-trust-hook-design.md), which added the same capability to the TCP
transport.

## Problem

`TcpTransportFactory` accepts a `configureTls: (TLSConfigBuilder.() -> Unit)?` hook, so a caller can
reach an `ssl://` broker anchored in a private or self-signed CA without granting app-wide trust.
`WebSocketTransportFactory` has no equivalent, so a `wss://` broker with the same CA remains
unreachable.

The consequence is that the #103 hook cannot actually be adopted. In Meshtastic-Android,
`network_security_config.xml` carries `<certificates src="user"/>` inside `base-config` — an anchor
that applies to *every* HTTPS connection the app makes — and the app registers
`TcpTransportFactory() + WebSocketTransportFactory()` so users can enter `ws://`/`wss://` brokers.
Removing the app-wide anchor today would keep private-CA `ssl://` working and break private-CA
`wss://`. Both transports must be configurable before the anchor can come out.

Concretely, as of 0.6.1:

- `WebSocketTransportFactory` and `WebSocketTransport` are `final` with no-arg constructors.
- `WebSocketTransport.connect` builds its client with a bare `HttpClient { install(WebSockets) { … } }`
  (`transport-ws/src/commonMain/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTransport.kt:56-61`),
  relying on ktor engine auto-detection, and never exposes engine configuration.

## Approach

Add an optional `configureTls: (TLSConfigBuilder.() -> Unit)?` to `WebSocketTransportFactory`,
threaded to `WebSocketTransport`, applied to the ktor engine's TLS configuration on the platforms
whose engine has one.

### Why `TLSConfigBuilder`, and not the shape the issue sketches

The issue proposes an `HttpClientConfig<*>` receiver. That cannot work: `HttpClientConfig<T>`
declares `fun engine(block: T.() -> Unit)`, and under the star projection `T` is in an `in`
position, so `engine { }` is not callable at all. A caller handed an `HttpClientConfig<*>` can
install plugins but can never reach TLS settings.

The issue also assumes there is no shared lambda type to unify on — as did #103's spec, which
recorded that `:transport-ws` "configures a ktor `HttpClient` and never touches `TLSConfigBuilder`".
That was true of the code, but the type is in fact available everywhere we need it:

- The engines already wired in `transport-ws/build.gradle.kts` are CIO on jvm/android/apple/linux,
  WinHttp on mingw, and Js on wasmJs.
- `CIOEngineConfig` exposes `https: TLSConfigBuilder` and `https { }` — the *same* builder type
  `:transport-tcp` configures.
- `ktor-network-tls` publishes a klib for every target this project builds, wasmJs included
  (verified against Maven Central at ktor 3.5.1: `ktor-network-tls-wasm-js` contains
  `io.ktor.network.tls.TLSConfigBuilder` via `TLSConfigBuilder.nonJvm.kt`). `trustManager` is a
  JVM/Android-only property of that builder, exactly as on native.

So the hook can use the identical type on both transports, with no `expect`/`actual` on the public
API and no new public type name. `:core` gains no ktor TLS types, so ADR-0006 and
`verifyModuleBoundary` are unaffected.

Two alternatives were considered and rejected:

- **A caller-supplied `HttpClient` (or `() -> HttpClient`).** Maximum power and the smallest diff,
  but the ergonomics diverge from #103, the caller takes on engine-lifecycle knowledge, and Android
  callers lose the hostname-aware trust-manager wrapping that `:transport-tcp` applies for free.
- **Both that and the lambda.** Two code paths to document and test for one issue; YAGNI.

## Public API

```kotlin
public class WebSocketTransportFactory(
    private val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransportFactory {
    public constructor() : this(null)

    override fun supports(endpoint: MqttEndpoint): Boolean = endpoint is MqttEndpoint.WebSocket

    override fun create(endpoint: MqttEndpoint): MqttTransport = WebSocketTransport(configureTls)
}

public class WebSocketTransport(
    internal val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransport {
    public constructor() : this(null)
}
```

This mirrors `:transport-tcp` member for member, including the explicit zero-arg secondary
constructors — Kotlin synthesises one for an all-defaults constructor on JVM, but klib ABI dumps
list constructors explicitly, so declaring it keeps the native/wasm ABI additive.

Usage is then symmetric across both transports:

```kotlin
transportFactory =
    TcpTransportFactory { trustManager = myTrustManager } +
        WebSocketTransportFactory { trustManager = myTrustManager }
```

`WebSocketTransport.configureTls` is `internal val` rather than private so `commonTest` can assert
the factory threads the hook through, matching `TcpTransport`.

## The seam

`WebSocketTransport.connect` currently calls `HttpClient { }` and lets ktor auto-detect the engine.
Engine-specific TLS configuration requires naming the engine, so client construction moves behind
one internal expect/actual in `transport-ws/src/commonMain`:

```kotlin
internal expect fun buildWsHttpClient(
    host: String,
    maxFrameSize: Long,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
): HttpClient
```

`host` comes from `Url(endpoint.url).host` (`ktor-http`, available on all targets). `connect` calls
`buildWsHttpClient` in place of its current inline `HttpClient { }`; the rest of `connect`, `send`,
`receive`, and `close` is unchanged, as is `MAX_FRAME_SIZE`.

### Source-set layout

A new intermediate source set `cioMain` holds the single CIO implementation, so it is written once
rather than four times:

```
commonMain            expect buildWsHttpClient
  └─ cioMain          actual (CIO) + expect TLSConfigBuilder.configurePlatformTrust
       ├─ jvmMain     actual configurePlatformTrust — no-op
       ├─ androidMain actual configurePlatformTrust — HostnameAwareTrustManager
       ├─ appleMain   actual configurePlatformTrust — no-op
       └─ linuxMain   actual configurePlatformTrust — no-op
  ├─ mingwMain        actual (WinHttp) — hook ignored
  └─ wasmJsMain       actual (Js)      — hook ignored
```

`cioMain` deliberately spans JVM-family and native targets; it may only use API present in both,
which CIO and `TLSConfigBuilder` are. `trustManager` is JVM-only and is referenced solely from the
`androidMain` actual. `AGENTS.md` currently states that no custom intermediate source sets remain
(the old `nonWebMain` having been removed), so that sentence needs updating. Wiring goes through
`findByName("androidMain")`, as the existing per-engine dependency blocks already do, because that
source set comes from the AGP KMP plugin.

The CIO actual:

```kotlin
internal actual fun buildWsHttpClient(
    host: String,
    maxFrameSize: Long,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
): HttpClient = HttpClient(CIO) {
    install(WebSockets) { this.maxFrameSize = maxFrameSize }
    engine {
        https {
            configureTls?.invoke(this)   // caller first…
            configurePlatformTrust(host) // …so Android wraps their trustManager, not the reverse
        }
    }
}
```

### Ordering, and the one difference from `applyMqttTls`

The caller's lambda runs before `configurePlatformTrust`, for exactly the reason #103 established:
on Android, `configurePlatformTrust` reads `trustManager` off the builder and wraps whatever it
finds in a hostname-aware delegate, so the caller's assignment must already be present. Reversing
the two would discard the wrapper and silently drop Android's 3-arg `checkServerTrusted` path,
which `NetworkSecurityTrustManager` requires whenever `network_security_config.xml` holds any
domain-specific configuration.

`applyMqttTls`'s first step — `serverName = sniServerName(host)` — has no counterpart here. The CIO
*client* derives SNI from the request URL when it opens the connection, whereas `engine { https { } }`
is configured once at client construction. Setting `serverName` there would be at best redundant.
Whether a `serverName` a caller assigns inside the hook survives CIO's own assignment is a ktor
precedence detail to confirm during implementation and document in whichever direction it turns
out; the hook's advertised purpose is trust, not SNI.

What the ordering does **not** provide is unchanged from #103 and must be restated in the KDoc:
installing a trust manager *replaces the platform's trust decision*. Network-security-config
anchors, pinning, and Certificate Transparency policy then hold only insofar as that manager
enforces them. Wrapping preserves the hostname-aware *call path*, not the platform's *policy*.

### Duplicating `configurePlatformTrust`

`:transport-ws` cannot depend on `:transport-tcp`: that module has no wasmJs target, and WS-only
consumers should not pull a TCP artifact. `configurePlatformTrust` — the `expect` plus the Android
`HostnameAwareTrustManager` and the three no-op actuals — is therefore copied into
`org.meshtastic.mqtt.transport.ws`, roughly 90 lines including KDoc.

This is a deliberate accepted cost, chosen over a fourth published artifact (whose API would become
de-facto public to Maven consumers, for a ~90-line internal helper) and over cross-module `srcDir`
wiring (surprising to Konsist/BCV/Kover and to future readers). The mitigation is explicit: each
copy's KDoc names the other file as the sibling that must change in lockstep, and the JVM test
asserting hook-then-platform ordering is mirrored in both modules.

## Unsupported targets

On mingw (WinHttp exposes no TLS-config surface) and wasmJs (the browser cannot influence trust at
all), the lambda is simply never invoked. No throw, no warning: common code can pass the hook
unconditionally and still compile and connect everywhere. The documented risk is that a caller may
believe a private CA is trusted on Windows when it is not, and learn otherwise from a handshake
failure. `Module.md` and the KDoc state the supported set plainly.

Native CIO targets (apple, linux) do invoke the lambda, but `TLSConfigBuilder` there has no
`trustManager`, so in practice there is little a caller can do — the same situation as
`:transport-tcp`, and documented the same way.

`ktor-client-cio-mingwx64` does exist, so switching Windows to CIO would make the hook work there.
It is rejected: CIO's native TLS has no Windows trust-store integration, so the switch would risk
breaking ordinary `wss://` on Windows to gain a niche capability.

## Build and compatibility

- `transport-ws/build.gradle.kts`: add `api(libs.ktor.network.tls)` to `commonMain` — a public
  signature now names `TLSConfigBuilder`, so consumers need it on their compile classpath.
  Engine dependencies are unchanged. Add the `cioMain` source set and its `dependsOn` wiring.
- `./gradlew apiDump` regenerates `transport-ws/api/transport-ws.klib.api` and the JVM dump; both
  are committed.
- Binary compatible: every new parameter is defaulted and the zero-arg constructors are declared
  explicitly, so already-compiled callers of `WebSocketTransportFactory()` keep linking.
- No change to `:core`, `:transport-tcp`, or the BOM. No Konsist allowlist change — the ADR-0008
  suite covers `:core` only.

## Testing

`commonTest` (extends `WebSocketTransportFactoryTest`):

- `WebSocketTransportFactory { }.create(endpoint)` returns a `WebSocketTransport` whose
  `configureTls` is the same lambda instance — the factory threads the hook through.
- A no-arg `WebSocketTransportFactory()` yields a transport with a `null` hook.
- `supports()` and the `+` combinator still behave as before with a hook present.

`jvmTest`:

- Mirror of `TcpTransportTrustManagerTest`: a `trustManager` assigned inside the hook is still the
  builder's `trustManager` after `configurePlatformTrust` runs. JVM's actual is a no-op, so this
  proves the hook reaches ktor's real builder state rather than a discarded copy.
- End-to-end, and the test that actually discharges the acceptance criterion rather than asserting
  it: stand up a local `wss://` server with a generated self-signed certificate, then assert that
  `WebSocketTransport().connect(…)` **fails** the handshake and that a transport built with a hook
  installing a `KeyStore`-backed `TrustManagerFactory` manager **connects and round-trips a binary
  frame** through `send`/`receive` against an echoing server. The server speaks WebSocket, not MQTT
  — this proves the trust hook and the transport's framing, which is all that is in question here.
  Test-only dependencies: `ktor-server-cio`,
  `ktor-server-websockets`, and `ktor-network-tls-certificates` (for `buildKeyStore`). These are
  `testImplementation`-scoped and do not affect the published artifacts or the zero-dependency
  policy for main source sets.

  As-built note: the server engine actually used is `ktor-server-netty`, not `ktor-server-cio` as
  drafted above — ktor's server-side CIO engine does not support HTTPS/TLS, which this test
  requires. `ktor-server-netty` is `testImplementation`-scoped only, same as the other two.

Not covered: the Android wrapping order, for the same reason as #103 —
`X509TrustManagerExtensions` is a framework class, so proving a caller's manager ends up inside
`HostnameAwareTrustManager` needs an instrumentation test. Out of scope; the single call site is
the mitigation.

Existing `spotlessCheck`, `detekt`, `allTests`, `apiCheck`, and `koverVerify` (≥80%) gates all
apply. `wasmJsTest` must still pass, which exercises the wasmJs actual's compilation.

## Documentation

- `transport-ws/Module.md` — a private-CA usage snippet plus a trust section matching
  `transport-tcp/Module.md`: caller-before-platform ordering, what installing a trust manager
  replaces, and the per-target support table (JVM/Android useful; apple/linux invoked but inert;
  mingw/wasmJs ignored).
- `transport-tcp/Module.md` and the duplicated `PlatformTls` KDoc — cross-references naming the
  sibling copy.
- `README.md` TLS section — extend the #103 pointer to cover both transports, framed as the
  replacement for the app-wide `<certificates src="user"/>` workaround.
- `AGENTS.md` — correct the "no custom intermediate source set any more" statement to describe
  `cioMain`.
- `CHANGELOG.md` — an entry under the next release.

## Out of scope (YAGNI)

- No `host` parameter on the lambda — a factory can close over whatever it needs.
- No caller-supplied `HttpClient` escape hatch, and no plugin-installation hook.
- No `MqttConfig`-level plumbing or transport-neutral trust abstraction in `:core`.
- No engine change on Windows, and no attempt to make native trust configurable.
- No wasmJs target for `:transport-tcp`. `ktor-network` does publish a wasmJs klib as of 3.1.3, but
  its socket implementation is Node-only — it imports `node:net` and resolves to `null` off Node —
  and this project declares `wasmJs { browser() }`. Adding a `nodejs()` target is a separate
  question.
