# WebSocket TLS Trust Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `WebSocketTransportFactory` an optional `configureTls: (TLSConfigBuilder.() -> Unit)?` hook so a `wss://` broker behind a private or self-signed CA is reachable without app-wide trust changes.

**Architecture:** `:transport-ws` stops relying on ktor engine auto-detection. Client construction moves behind an internal `expect fun buildWsHttpClient`, actualised three ways: a new intermediate `cioMain` source set (jvm + android + apple + linux) that applies the caller's hook to `CIO`'s `engine { https { … } }` and then wraps trust for Android, plus `mingwMain` (WinHttp) and `wasmJsMain` (Js) actuals that ignore the hook. The public signature uses ktor's `TLSConfigBuilder` — the same type `:transport-tcp` uses — so there is no `expect`/`actual` on the public API.

**Tech Stack:** Kotlin 2.4.10, Gradle 9.6.1, ktor 3.5.1 (`ktor-client-cio`, `ktor-client-winhttp`, `ktor-client-js`, `ktor-network-tls`), kotlinx-coroutines 1.11.0, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-07-26-ws-tls-trust-hook-design.md`. **Issue:** #107.

## Global Constraints

- Every change is additive and binary-compatible. `WebSocketTransportFactory()` and `WebSocketTransport()` must keep linking for already-compiled callers — declare the zero-arg secondary constructors explicitly, because klib ABI dumps list constructors individually.
- No new runtime dependencies outside the ktor / kotlinx-coroutines / kotlinx-io umbrella. `ktor-server-cio`, `ktor-server-websockets`, and `ktor-network-tls-certificates` are added in Task 5 as `testImplementation` only.
- `:core` gains nothing. No ktor TLS types may reach it (ADR-0006, enforced by `core/build.gradle.kts`'s `verifyModuleBoundary`).
- `:transport-ws` must not depend on `:transport-tcp`. `configurePlatformTrust` is deliberately duplicated; each copy's KDoc names its sibling.
- Ordering rule, non-negotiable: the caller's hook runs **before** `configurePlatformTrust`, so Android wraps the caller's trust manager rather than replacing it.
- Existing test-naming styles differ by file — `WebSocketTransportFactoryTest` uses backtick names, `:transport-tcp`'s tests use camelCase. Match the file you are editing; use camelCase in new files.
- Gates that must pass before any task is called done: `./gradlew spotlessApply` then `./gradlew spotlessCheck detekt jvmTest wasmJsTest apiCheck`. Full `allTests` and `koverVerify` run in Task 6.
- Zero lint tolerance: a task with a `detekt` or `spotlessCheck` failure is not finished.

---

### Task 1: Explicit per-engine client construction (no behaviour change)

Replaces auto-detection with a named engine per platform, behind an internal seam. No public API change, no TLS yet — this task exists so the source-set restructuring is reviewable on its own.

**Files:**
- Modify: `transport-ws/build.gradle.kts:48-74` (source sets)
- Create: `transport-ws/src/commonMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.kt`
- Create: `transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.cio.kt`
- Create: `transport-ws/src/mingwMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.mingw.kt`
- Create: `transport-ws/src/wasmJsMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.wasmJs.kt`
- Modify: `transport-ws/src/commonMain/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTransport.kt:56-62`
- Test: `transport-ws/src/commonTest/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTransportFactoryTest.kt` (existing — must keep passing)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `internal expect fun buildWsHttpClient(maxFrameSize: Long): HttpClient` in package `org.meshtastic.mqtt.transport.ws`. Task 2 extends this signature; Task 3 adds a call inside the CIO actual.

- [ ] **Step 1: Restructure the source sets**

Replace the whole `sourceSets { … }` block in `transport-ws/build.gradle.kts` (currently lines 48-74) with this. The four separate CIO dependency blocks collapse into one `cioMain` declaration:

```kotlin
    sourceSets {
        val commonMain by getting
        // Intermediate source set for the four targets that use the CIO engine. It holds the
        // single CIO client builder and, from Task 3, the TLS trust plumbing — written once
        // instead of four times. Deliberately spans JVM-family and native targets, so it may
        // only use API present in both (CIO and TLSConfigBuilder both are; TLSConfigBuilder's
        // JVM-only `trustManager` is touched solely from the androidMain actual).
        val cioMain by creating { dependsOn(commonMain) }

        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }

        cioMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        // Ktor HttpClient engines — CIO where available, platform engines elsewhere.
        jvmMain.get().dependsOn(cioMain)
        findByName("androidMain")?.dependsOn(cioMain)
        findByName("appleMain")?.dependsOn(cioMain)
        findByName("linuxMain")?.dependsOn(cioMain)

        findByName("mingwMain")?.dependencies {
            implementation(libs.ktor.client.winhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
```

- [ ] **Step 2: Write the failing common seam**

Create `transport-ws/src/commonMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.kt`. Copy the 16-line GPL header from `WebSocketTransport.kt` verbatim as the file's first lines (spotless enforces it), then:

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient

/**
 * Builds the [HttpClient] used for the WebSocket connection, with the WebSockets plugin
 * installed and a platform-appropriate engine selected.
 *
 * Engine auto-detection cannot be used here: configuring engine-level TLS requires naming
 * the engine, and a star-projected `HttpClientConfig<*>` cannot reach `engine { }` at all.
 *
 * @param maxFrameSize the WebSocket frame safety cap, in bytes.
 */
internal expect fun buildWsHttpClient(maxFrameSize: Long): HttpClient
```

- [ ] **Step 3: Run the build to verify it fails**

Run: `./gradlew :transport-ws:compileKotlinJvm`
Expected: FAIL — `Expected function 'buildWsHttpClient' has no actual declaration in module …`.

- [ ] **Step 4: Write the three actuals**

`transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.cio.kt` (GPL header first, as in every file below):

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/** CIO engine — JVM, Android, Apple, and Linux. */
internal actual fun buildWsHttpClient(maxFrameSize: Long): HttpClient =
    HttpClient(CIO) {
        install(WebSockets) {
            this.maxFrameSize = maxFrameSize
        }
    }
```

`transport-ws/src/mingwMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.mingw.kt`:

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import io.ktor.client.plugins.websocket.WebSockets

/** WinHttp engine — Windows. Trust is handled by the platform certificate store. */
internal actual fun buildWsHttpClient(maxFrameSize: Long): HttpClient =
    HttpClient(WinHttp) {
        install(WebSockets) {
            this.maxFrameSize = maxFrameSize
        }
    }
```

`transport-ws/src/wasmJsMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.wasmJs.kt`:

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets

/** Js engine — browser (wasmJs). Trust is entirely the browser's; nothing is configurable. */
internal actual fun buildWsHttpClient(maxFrameSize: Long): HttpClient =
    HttpClient(Js) {
        install(WebSockets) {
            this.maxFrameSize = maxFrameSize
        }
    }
```

- [ ] **Step 5: Call the seam from the transport**

In `WebSocketTransport.connect`, replace this (lines 56-62):

```kotlin
        val httpClient =
            HttpClient {
                install(WebSockets) {
                    maxFrameSize = MAX_FRAME_SIZE
                }
            }
        client = httpClient
```

with:

```kotlin
        val httpClient = buildWsHttpClient(MAX_FRAME_SIZE)
        client = httpClient
```

Then delete the now-unused imports `io.ktor.client.HttpClient`'s *usage* stays (the `client` field is typed `HttpClient?`), but `io.ktor.client.plugins.websocket.WebSockets` is no longer referenced in this file — remove that import. Leave everything else in the file untouched.

- [ ] **Step 6: Verify compilation and existing tests across engines**

Run: `./gradlew :transport-ws:jvmTest :transport-ws:wasmJsTest :transport-ws:compileKotlinMingwX64 :transport-ws:compileKotlinLinuxX64`
Expected: PASS. All existing `WebSocketTransportFactoryTest` cases still green. If `compileKotlinMingwX64`/`LinuxX64` cannot run on this host, note it and rely on CI.

- [ ] **Step 7: Lint**

Run: `./gradlew spotlessApply && ./gradlew :transport-ws:spotlessCheck :transport-ws:detekt`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add transport-ws/build.gradle.kts transport-ws/src/commonMain transport-ws/src/cioMain transport-ws/src/mingwMain transport-ws/src/wasmJsMain
git commit -m "refactor(transport): select the WebSocket ktor engine explicitly"
```

---

### Task 2: The public `configureTls` hook

**Files:**
- Modify: `transport-ws/build.gradle.kts` (add `api(libs.ktor.network.tls)` to `commonMain`)
- Modify: `transport-ws/src/commonMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.kt`
- Modify: `transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.cio.kt`
- Modify: `transport-ws/src/mingwMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.mingw.kt`
- Modify: `transport-ws/src/wasmJsMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.wasmJs.kt`
- Modify: `transport-ws/src/commonMain/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTransport.kt`
- Create: `transport-ws/src/commonTest/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTransportFactoryTlsTest.kt`
- Modify: `transport-ws/api/jvm/transport-ws.api`, `transport-ws/api/transport-ws.klib.api` (via `apiDump`)

**Interfaces:**
- Consumes: `internal expect fun buildWsHttpClient(maxFrameSize: Long): HttpClient` from Task 1.
- Produces:
  - `public class WebSocketTransport(internal val configureTls: (TLSConfigBuilder.() -> Unit)? = null)` with a `public constructor()`.
  - `public class WebSocketTransportFactory(private val configureTls: (TLSConfigBuilder.() -> Unit)? = null)` with a `public constructor()`.
  - `internal expect fun buildWsHttpClient(host: String, maxFrameSize: Long, configureTls: (TLSConfigBuilder.() -> Unit)?): HttpClient` — Task 3 adds a `configurePlatformTrust(host)` call after the hook inside the CIO actual.

- [ ] **Step 1: Write the failing test**

Create `transport-ws/src/commonTest/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTransportFactoryTlsTest.kt` (GPL header first). Method names are camelCase — this is a new file, matching `:transport-tcp`'s test style:

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.network.tls.TLSConfigBuilder
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.plus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the caller-supplied TLS hook on [WebSocketTransportFactory] (issue #107).
 *
 * The hook only runs during a real TLS handshake, so these tests pin the surrounding
 * contract: endpoint selection, transport type, hook forwarding, and `+` composition.
 * The handshake itself is covered by the JVM end-to-end test in Task 5.
 */
class WebSocketTransportFactoryTlsTest {
    private val endpoint = MqttEndpoint.WebSocket("wss://broker.example.com/mqtt")

    @Test
    fun factoryWithTlsHookStillSupportsOnlyWebSocketEndpoints() {
        val factory = WebSocketTransportFactory { serverName = "override.example.com" }
        assertTrue(factory.supports(endpoint))
        assertFalse(factory.supports(MqttEndpoint.Tcp("broker.example.com")))
    }

    @Test
    fun factoryForwardsTlsHookToCreatedTransport() {
        // Guards against create() silently dropping configureTls: the transport must hold the
        // very lambda instance the factory was constructed with.
        val hook: TLSConfigBuilder.() -> Unit = { serverName = "override.example.com" }
        val transport = WebSocketTransportFactory(hook).create(endpoint)
        assertIs<WebSocketTransport>(transport)
        assertSame(hook, transport.configureTls)
    }

    @Test
    fun noArgFactoryProducesTransportWithoutHook() {
        val transport = WebSocketTransportFactory().create(endpoint)
        assertIs<WebSocketTransport>(transport)
        assertNull(transport.configureTls)
    }

    @Test
    fun factoryWithTlsHookStillComposesWithPlus() {
        val combined = WebSocketTransportFactory { serverName = "override.example.com" } +
            WebSocketTransportFactory()
        assertIs<WebSocketTransport>(combined.create(endpoint))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :transport-ws:jvmTest --tests "org.meshtastic.mqtt.transport.ws.WebSocketTransportFactoryTlsTest"`
Expected: FAIL at compilation — `Unresolved reference: TLSConfigBuilder` (the dependency is not on the classpath yet) and no matching constructor.

- [ ] **Step 3: Expose `ktor-network-tls` as `api`**

In `transport-ws/build.gradle.kts`, the `commonMain.dependencies` block becomes:

```kotlin
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            // api, not implementation: TLSConfigBuilder appears in WebSocketTransportFactory's
            // public constructor signature, so consumers need it on their compile classpath.
            // ktor-network-tls publishes a klib for every target this module builds, wasmJs
            // included — `trustManager` is a JVM/Android-only property of that builder.
            api(libs.ktor.network.tls)
        }
```

- [ ] **Step 4: Thread the hook through the seam**

`WsHttpClient.kt` (commonMain) — replace the `expect` declaration and its KDoc:

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient
import io.ktor.network.tls.TLSConfigBuilder

/**
 * Builds the [HttpClient] used for the WebSocket connection, with the WebSockets plugin
 * installed and a platform-appropriate engine selected.
 *
 * Engine auto-detection cannot be used here: configuring engine-level TLS requires naming
 * the engine, and a star-projected `HttpClientConfig<*>` cannot reach `engine { }` at all.
 *
 * [configureTls] is honoured only where the engine exposes a TLS configuration — CIO, on JVM,
 * Android, Apple, and Linux. On Windows (WinHttp) and in the browser (wasmJs / Js) it is
 * silently ignored: WinHttp exposes no TLS-config surface and the browser cannot influence
 * trust at all.
 *
 * @param host the broker host from the endpoint URL, used for trust evaluation.
 * @param maxFrameSize the WebSocket frame safety cap, in bytes.
 * @param configureTls optional caller customisation of the engine's TLS configuration.
 */
internal expect fun buildWsHttpClient(
    host: String,
    maxFrameSize: Long,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
): HttpClient
```

`WsHttpClient.cio.kt` — apply the hook:

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.tls.TLSConfigBuilder

/**
 * CIO engine — JVM, Android, Apple, and Linux.
 *
 * The caller's hook is applied to CIO's `https` [TLSConfigBuilder], which is the same builder
 * type `:transport-tcp` configures. `serverName` is deliberately not set here: the CIO *client*
 * derives SNI from the request URL when it opens the connection, whereas this block runs once at
 * client construction.
 */
internal actual fun buildWsHttpClient(
    host: String,
    maxFrameSize: Long,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
): HttpClient =
    HttpClient(CIO) {
        install(WebSockets) {
            this.maxFrameSize = maxFrameSize
        }
        engine {
            https {
                configureTls?.invoke(this)
            }
        }
    }
```

`WsHttpClient.mingw.kt` and `WsHttpClient.wasmJs.kt` — same engine bodies as Task 1, with the widened signature and the hook explicitly discarded. For mingw:

```kotlin
/**
 * WinHttp engine — Windows. Trust is handled by the platform certificate store.
 *
 * [configureTls] is ignored: `WinHttpClientEngineConfig` exposes no TLS-configuration surface.
 * Documented in `Module.md`. Switching Windows to CIO would make the hook work, but CIO's
 * native TLS has no Windows trust-store integration, so it would risk breaking ordinary `wss://`.
 */
@Suppress("UNUSED_PARAMETER")
internal actual fun buildWsHttpClient(
    host: String,
    maxFrameSize: Long,
    configureTls: (TLSConfigBuilder.() -> Unit)?,
): HttpClient =
    HttpClient(WinHttp) {
        install(WebSockets) {
            this.maxFrameSize = maxFrameSize
        }
    }
```

For wasmJs, identical but with `HttpClient(Js)` and this KDoc:

```kotlin
/**
 * Js engine — browser (wasmJs).
 *
 * [configureTls] is ignored: a browser page cannot influence TLS trust in any way. Documented
 * in `Module.md`.
 */
```

Add `import io.ktor.network.tls.TLSConfigBuilder` to both.

- [ ] **Step 5: Add the constructor parameters**

In `WebSocketTransport.kt`, add the import `io.ktor.http.Url` and `io.ktor.network.tls.TLSConfigBuilder`, then change the class declaration:

```kotlin
/**
 * WebSocket-based [MqttTransport] for all platforms, including the browser (wasmJs).
 *
 * Uses Ktor's multiplatform HttpClient with a per-platform engine (CIO on JVM/Android/Apple/Linux,
 * WinHttp on Windows, Js on wasmJs/browser). One binary WebSocket frame = one complete MQTT packet,
 * so no stream parsing is needed — the WebSocket layer handles framing.
 *
 * @param configureTls optional hook applied to the engine's TLS configuration before platform
 *   trust is configured. Use it to trust a private or self-signed CA for this connection only.
 *   Honoured on JVM, Android, Apple, and Linux; ignored on Windows and in the browser.
 */
public class WebSocketTransport(
    internal val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransport {
    public constructor() : this(null)
```

In `connect`, derive the host from the URL and pass both through — replacing the Step 5 line from Task 1:

```kotlin
        val httpClient = buildWsHttpClient(Url(endpoint.url).host, MAX_FRAME_SIZE, configureTls)
        client = httpClient
```

And the factory at the bottom of the same file:

```kotlin
/**
 * [MqttTransportFactory] that builds a [WebSocketTransport] for [MqttEndpoint.WebSocket] endpoints.
 *
 * Add `org.meshtastic:mqtt-client-transport-ws` and pass an instance to
 * [org.meshtastic.mqtt.MqttConfig.Builder.transportFactory]. This is the only transport available
 * on the browser (wasmJs) target.
 *
 * To trust a broker whose certificate chain is not in the platform CA store — a private or
 * self-signed CA — supply [configureTls]. The scope is this MQTT connection only, unlike Android's
 * app-wide `network_security_config.xml` trust anchors:
 *
 * ```kotlin
 * transportFactory = WebSocketTransportFactory { trustManager = myTrustManager }
 * ```
 *
 * @param configureTls optional hook applied to ktor's [TLSConfigBuilder] for every transport this
 *   factory creates. It runs before platform trust is configured, so on Android a trust manager
 *   installed here is reached through the hostname-aware `checkServerTrusted` overload rather than
 *   bypassing it. Note that installing a trust manager replaces the platform's trust decision —
 *   network-security-config anchors, pinning, and Certificate Transparency policy then hold only
 *   insofar as that manager enforces them. Honoured on JVM, Android, Apple, and Linux; silently
 *   ignored on Windows (WinHttp has no TLS-config surface) and in the browser (which cannot
 *   influence trust). On Apple and Linux `TLSConfigBuilder` has no `trustManager`, so little is
 *   configurable there in practice.
 */
public class WebSocketTransportFactory(
    private val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransportFactory {
    public constructor() : this(null)

    override fun supports(endpoint: MqttEndpoint): Boolean = endpoint is MqttEndpoint.WebSocket

    override fun create(endpoint: MqttEndpoint): MqttTransport = WebSocketTransport(configureTls)
}
```

If `io.ktor.http.Url` does not resolve, add `ktor-http = { module = "io.ktor:ktor-http", version.ref = "ktor" }` to `gradle/libs.versions.toml` and `implementation(libs.ktor.http)` to `commonMain`. Try without it first — `ktor-client-core` exposes `ktor-http` as an `api` dependency.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :transport-ws:jvmTest --tests "org.meshtastic.mqtt.transport.ws.WebSocketTransportFactoryTlsTest"`
Expected: PASS, 4 tests.

Then confirm nothing else broke: `./gradlew :transport-ws:jvmTest :transport-ws:wasmJsTest`
Expected: PASS.

- [ ] **Step 7: Regenerate the API baselines**

Run: `./gradlew :transport-ws:apiDump && ./gradlew :transport-ws:apiCheck`
Expected: PASS. `git diff transport-ws/api` should show only *added* lines: a one-arg constructor on each class alongside the existing zero-arg one, plus a `getConfigureTls`-style accessor if BCV records it. If any line was **removed**, stop — that is a binary-compatibility break and means the explicit `public constructor()` is missing.

- [ ] **Step 8: Lint and commit**

```bash
./gradlew spotlessApply && ./gradlew :transport-ws:spotlessCheck :transport-ws:detekt
git add transport-ws
git commit -m "feat(transport): add TLS trust hook to WebSocketTransportFactory"
```

---

### Task 3: Android hostname-aware trust wrapping

Duplicates `:transport-tcp`'s `configurePlatformTrust` into `:transport-ws` and calls it *after* the caller's hook.

**Files:**
- Create: `transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.kt`
- Create: `transport-ws/src/jvmMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.jvm.kt`
- Create: `transport-ws/src/androidMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.android.kt`
- Create: `transport-ws/src/appleMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.apple.kt`
- Create: `transport-ws/src/linuxMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.linux.kt`
- Modify: `transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/WsHttpClient.cio.kt`
- Modify: `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt` (sibling cross-reference only)
- Test: `transport-ws/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTrustManagerTest.kt`

**Interfaces:**
- Consumes: `buildWsHttpClient(host, maxFrameSize, configureTls)` from Task 2.
- Produces: `internal expect fun TLSConfigBuilder.configurePlatformTrust(host: String)` in `cioMain`, plus `internal fun TLSConfigBuilder.applyWsTls(host: String, configureTls: (TLSConfigBuilder.() -> Unit)?, platformTrust: TLSConfigBuilder.(String) -> Unit = { configurePlatformTrust(it) })` — the single ordering call site, with a test seam for `platformTrust`.

- [ ] **Step 1: Write the failing test**

Create `transport-ws/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketTrustManagerTest.kt` (GPL header first):

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.network.tls.TLSConfigBuilder
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * JVM-only checks on the ordering inside [applyWsTls], using `trustManager` — a property that
 * exists only on the JVM and Android actuals of [TLSConfigBuilder], so it cannot be asserted
 * from `commonTest`.
 *
 * On the JVM `configurePlatformTrust` is a no-op, so a hook-installed trust manager survives
 * untouched. On Android it is deliberately *not* preserved by identity — it gets wrapped in a
 * hostname-aware delegate, which needs the `X509TrustManagerExtensions` framework class and is
 * therefore out of unit-test scope. The ordering itself is asserted here via the
 * `platformTrust` test seam.
 */
class WebSocketTrustManagerTest {
    private object FakeTrustManager : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun hookInstalledTrustManagerSurvivesApplyWsTls() {
        val builder = TLSConfigBuilder()
        builder.applyWsTls("broker.example.com") { trustManager = FakeTrustManager }
        assertSame(FakeTrustManager, builder.trustManager)
    }

    @Test
    fun callerHookRunsBeforePlatformTrust() {
        // The Android wrapper reads whatever trustManager is on the builder, so the caller's
        // assignment must already be present when platform trust runs. Reversing the two would
        // silently drop Android's hostname-aware checkServerTrusted path.
        val order = mutableListOf<String>()
        val builder = TLSConfigBuilder()
        builder.applyWsTls(
            host = "broker.example.com",
            configureTls = { order += "caller" },
            platformTrust = { order += "platform" },
        )
        assertEquals(listOf("caller", "platform"), order)
    }

    @Test
    fun platformTrustReceivesTheHostUnchanged() {
        // Includes the IP-literal case: Android's 2-arg overload throws whenever the security
        // config holds any domain-specific entry, regardless of target host, so an IP-only
        // broker needs the wrapper too.
        val seen = mutableListOf<String>()
        TLSConfigBuilder().applyWsTls("192.168.1.50", null) { seen += it }
        assertEquals(listOf("192.168.1.50"), seen)
    }

    @Test
    fun nullHookIsANoOp() {
        val builder = TLSConfigBuilder()
        builder.applyWsTls("broker.example.com", null)
        assertSame(null, builder.trustManager)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :transport-ws:jvmTest --tests "org.meshtastic.mqtt.transport.ws.WebSocketTrustManagerTest"`
Expected: FAIL at compilation — `Unresolved reference: applyWsTls`.

- [ ] **Step 3: Write the `cioMain` expect + ordering function**

Create `transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.kt` (GPL header first):

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.network.tls.TLSConfigBuilder

/**
 * Applies platform-specific TLS trust configuration to the [TLSConfigBuilder].
 *
 * On Android, this installs a hostname-aware trust manager that satisfies
 * `NetworkSecurityTrustManager`'s requirement for the 3-arg
 * `checkServerTrusted(chain, authType, hostname)` overload when `network_security_config.xml`
 * contains domain-specific configurations. The platform throws from the 2-arg overload whenever
 * *any* domain-specific config is present — regardless of the target host — so this must run for
 * IP-literal brokers too, not only DNS hostnames.
 *
 * On JVM, Apple, and Linux this is a no-op — the platform default suffices, and non-JVM
 * `TLSConfigBuilder` has no `trustManager` property at all.
 *
 * **Sibling copy.** `:transport-ws` cannot depend on `:transport-tcp` — that module has no wasmJs
 * target, and WS-only consumers should not pull a TCP artifact — so this logic is deliberately
 * duplicated from
 * `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt` and its
 * per-platform actuals. **Any fix here must be applied there too, and vice versa.**
 *
 * @param host the broker host (DNS name or IP literal) used for trust evaluation.
 */
internal expect fun TLSConfigBuilder.configurePlatformTrust(host: String)

/**
 * Applies the MQTT TLS configuration to this [TLSConfigBuilder] in the one correct order:
 *
 * 1. [configureTls] — the caller's hook, so it can install its own trust manager (e.g. a private CA).
 * 2. [configurePlatformTrust] — reads whatever trust manager is now on the builder and, on Android,
 *    wraps it in a hostname-aware delegate.
 *
 * Step 1 must precede step 2. What step 2 provides is the call path: whatever trust manager is on
 * the builder is reached through Android's hostname-aware
 * `checkServerTrusted(chain, authType, hostname)` overload, which `NetworkSecurityTrustManager`
 * requires whenever `network_security_config.xml` holds any domain-specific configuration.
 * Reversing the two steps would discard that wrapper, leaving ktor's 2-arg call to hit the bare
 * manager.
 *
 * Be precise about what this ordering does *not* provide. Installing a trust manager via
 * [configureTls] *replaces the platform's trust decision*: that manager's anchors are used instead
 * of the platform's, and `network_security_config.xml` anchors, certificate pinning, and
 * Certificate Transparency policy are then enforced only insofar as that manager enforces them
 * itself. Wrapping preserves the hostname-aware *call path*, not the platform's *policy*.
 *
 * Unlike `:transport-tcp`'s `applyMqttTls`, this does not set `serverName`: the CIO client derives
 * SNI — and with it ktor's RFC 6125 subject-name check — from the request URL when it opens the
 * connection, whereas this runs once at client construction.
 *
 * @param host the broker host (DNS name or IP literal), used for trust evaluation.
 * @param configureTls optional caller customisation; `null` preserves the default behaviour.
 * @param platformTrust test seam for [configurePlatformTrust]; production callers use the default.
 */
internal fun TLSConfigBuilder.applyWsTls(
    host: String,
    configureTls: (TLSConfigBuilder.() -> Unit)? = null,
    platformTrust: TLSConfigBuilder.(String) -> Unit = { configurePlatformTrust(it) },
) {
    configureTls?.invoke(this)
    platformTrust(host)
}
```

- [ ] **Step 4: Write the four actuals**

`PlatformTls.jvm.kt` (jvmMain):

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.network.tls.TLSConfigBuilder

/** See the sibling note on the `expect` declaration: mirrored in `:transport-tcp`. */
internal actual fun TLSConfigBuilder.configurePlatformTrust(host: String) {
    // No-op: JVM's default X509TrustManager handles the 2-arg checkServerTrusted correctly.
}
```

`PlatformTls.apple.kt` (appleMain) and `PlatformTls.linux.kt` (linuxMain) are the same file with this comment body instead:

```kotlin
    // No-op: native TLSConfigBuilder does not expose a trustManager property.
```

`PlatformTls.android.kt` (androidMain) is a verbatim port of
`transport-tcp/src/androidMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.android.kt`
— read that file and copy it, changing only the `package` line to
`org.meshtastic.mqtt.transport.ws` and adding this paragraph to the function's KDoc:

```
 * **Sibling copy** of `:transport-tcp`'s `PlatformTls.android.kt`. Any fix here must be applied
 * there too, and vice versa.
```

Do not paraphrase or "improve" that file: the error-message wording, the `X509TrustManagerExtensions`
wrapping, the `TrustManagerFactory` fallback, and the `host.isBlank()` guard are all load-bearing and
were reviewed in #103.

- [ ] **Step 5: Add the reciprocal cross-reference**

In `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt`, append to the KDoc of `configurePlatformTrust` (just before the `@param host` line):

```
 * **Sibling copy.** The same logic is duplicated in
 * `transport-ws/src/cioMain/kotlin/org/meshtastic/mqtt/transport/ws/PlatformTls.kt` and its
 * actuals, because `:transport-ws` cannot depend on this module. **Any fix here must be applied
 * there too, and vice versa.**
```

- [ ] **Step 6: Call it from the CIO client builder**

In `WsHttpClient.cio.kt`, replace the `engine { https { … } }` block:

```kotlin
        engine {
            https {
                applyWsTls(host, configureTls)
            }
        }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :transport-ws:jvmTest`
Expected: PASS — 4 new `WebSocketTrustManagerTest` cases plus everything from Tasks 1-2.

Run: `./gradlew :transport-ws:compileKotlinLinuxX64 :transport-ws:wasmJsTest`
Expected: PASS. (Android compilation is covered by `./gradlew :transport-ws:assemble` or CI; run `./gradlew :transport-ws:compileDebugKotlinAndroid` if that task exists in this build.)

- [ ] **Step 8: Confirm the API surface did not change**

Run: `./gradlew :transport-ws:apiCheck`
Expected: PASS with no dump changes — everything added in this task is `internal`.

- [ ] **Step 9: Lint and commit**

```bash
./gradlew spotlessApply && ./gradlew :transport-ws:spotlessCheck :transport-ws:detekt :transport-tcp:spotlessCheck
git add transport-ws transport-tcp
git commit -m "feat(transport): wrap WS trust manager for Android hostname checks"
```

---

### Task 4: Private-CA `wss://` end-to-end test

The test that actually discharges the issue's acceptance criterion instead of asserting it.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `transport-ws/build.gradle.kts` (`jvmTest` dependencies)
- Test: `transport-ws/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketPrivateCaTest.kt`

**Interfaces:**
- Consumes: `WebSocketTransport(configureTls)` from Task 2, the trust ordering from Task 3.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the test-only dependencies**

In `gradle/libs.versions.toml`, under `[libraries]` next to the other `ktor-` entries:

```toml
ktor-network-tls-certificates = { module = "io.ktor:ktor-network-tls-certificates", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
ktor-server-websockets = { module = "io.ktor:ktor-server-websockets", version.ref = "ktor" }
```

In `transport-ws/build.gradle.kts`, inside `sourceSets { … }`, after the `wasmJsMain` block:

```kotlin
        jvmTest.get().dependencies {
            // Test-only: a local wss:// server with a generated self-signed certificate, so the
            // private-CA path is proven rather than asserted. Not part of the published artifact.
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.network.tls.certificates)
        }
```

- [ ] **Step 2: Write the failing test**

Create `transport-ws/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/ws/WebSocketPrivateCaTest.kt` (GPL header first):

```kotlin
package org.meshtastic.mqtt.transport.ws

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.meshtastic.mqtt.MqttEndpoint
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end proof of issue #107's acceptance criterion: a `wss://` broker whose certificate is
 * anchored in a private CA is reachable through the [WebSocketTransportFactory] hook alone, with
 * no app-wide or JVM-wide trust changes.
 *
 * The server speaks WebSocket, not MQTT — what is under test is the TLS trust decision and the
 * transport's binary framing, not the protocol layered on top.
 */
class WebSocketPrivateCaTest {
    private val alias = "mqtt-ws-test"
    private val password = "changeit"
    private lateinit var keyStoreFile: File
    private lateinit var keyStore: KeyStore
    private var port = 0
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    @BeforeTest
    fun startServer() {
        keyStore =
            buildKeyStore {
                certificate(alias) {
                    this.password = this@WebSocketPrivateCaTest.password
                    domains = listOf("localhost")
                }
            }
        keyStoreFile = File.createTempFile("mqtt-ws-test", ".jks")
        keyStoreFile.outputStream().use { keyStore.store(it, password.toCharArray()) }

        port = ServerSocket(0).use { it.localPort }

        server =
            embeddedServer(CIO, serverConfig {
                module {
                    install(WebSockets)
                    routing {
                        webSocket("/mqtt") {
                            for (frame in incoming) {
                                if (frame is Frame.Binary) send(Frame.Binary(true, frame.data))
                            }
                        }
                    }
                }
            }) {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = alias,
                    keyStorePassword = { password.toCharArray() },
                    privateKeyPassword = { password.toCharArray() },
                ) {
                    this.port = this@WebSocketPrivateCaTest.port
                    this.keyStorePath = keyStoreFile
                }
            }.also { it.start(wait = false) }
    }

    @AfterTest
    fun stopServer() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        keyStoreFile.delete()
    }

    private val endpoint get() = MqttEndpoint.WebSocket("wss://localhost:$port/mqtt")

    /** A trust store holding only the generated certificate — the "private CA". */
    private fun privateCaTrustManager(): X509TrustManager {
        val trustStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("private-ca", keyStore.getCertificate(alias))
            }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    @Test
    fun handshakeFailsWithoutTheHook() = runTest {
        val transport = WebSocketTransportFactory().create(endpoint)
        assertFailsWith<Exception> {
            withTimeout(20_000) { transport.connect(endpoint) }
        }
        transport.close()
    }

    @Test
    fun handshakeSucceedsAndFramesRoundTripWithTheHook() = runTest {
        val tm = privateCaTrustManager()
        val transport = WebSocketTransportFactory { trustManager = tm }.create(endpoint)
        withTimeout(20_000) {
            transport.connect(endpoint)
            assertTrue(transport.isConnected)
            val payload = byteArrayOf(0x10, 0x02, 0x00, 0x04)
            transport.send(payload)
            assertContentEquals(payload, transport.receive())
        }
        transport.close()
    }
}
```

- [ ] **Step 3: Run the test to verify it fails, then make it compile**

Run: `./gradlew :transport-ws:jvmTest --tests "org.meshtastic.mqtt.transport.ws.WebSocketPrivateCaTest"`

The ktor **server** API in Step 2 is written against ktor 3.5.1 from documentation, not from this
repo (no ktor server dependency existed before this task). If it fails to compile, fix the test
harness — not the production code:

- `serverConfig { module { … } }` / `embeddedServer(factory, config) { connectors }` shapes moved
  between ktor 3.x minors; check `io.ktor.server.engine.EmbeddedServerKt` in the resolved artifact
  or ktor's docs for 3.5.x.
- CIO's `sslConnector` may require `keyStorePath` to be set (it is, above) and may reject a
  `port = 0`, hence the pre-picked free port.
- `EmbeddedServer<*, *>`'s generic signature is awkward to name; if it fights you, store the server
  in a `var server: Any?` and cast at `stop`, or hold it via a nullable `AutoCloseable` wrapper.

Once it compiles, expect: `handshakeFailsWithoutTheHook` PASSES (the JVM does not trust the
generated cert) and `handshakeSucceedsAndFramesRoundTripWithTheHook` PASSES. If the *second* test
fails, that is a real defect in Tasks 2-3 — do not weaken the test; debug the hook.

If `handshakeFailsWithoutTheHook` unexpectedly *passes the handshake*, something is trusting the
cert globally (a `cacerts` entry or a stale `javax.net.ssl.trustStore` system property). Investigate
before proceeding; a silently-always-trusting environment makes the second test meaningless.

- [ ] **Step 4: Settle the open `serverName` question**

The spec left one thing to determine empirically: whether a `serverName` a caller assigns inside the
hook survives CIO's own per-request SNI assignment. Now that a live TLS server exists, answer it.
Add this test temporarily:

```kotlin
    @Test
    fun serverNameSetInsideTheHookLosesToCioRequestSni() = runTest {
        val tm = privateCaTrustManager()
        val transport =
            WebSocketTransportFactory {
                trustManager = tm
                serverName = "not-the-server.example.com"
            }.create(endpoint)
        // If the connect succeeds, CIO overrode the hook's serverName with the request host.
        // If it fails on subject-name mismatch, the hook's value won.
        withTimeout(20_000) { transport.connect(endpoint) }
        transport.close()
    }
```

Run it, then keep it with whichever name matches reality (rename to
`serverNameSetInsideTheHookWinsOverCioRequestSni` and wrap in `assertFailsWith` if the hook wins),
and record the finding in one sentence in both `WsHttpClient.cio.kt`'s KDoc and the `Module.md`
paragraph from Task 5. Do not leave the question open — a caller reading "this hook does not set
the SNI server name" will reasonably wonder whether they can.

- [ ] **Step 5: Confirm no public API change and lint**

Run: `./gradlew :transport-ws:apiCheck && ./gradlew spotlessApply && ./gradlew :transport-ws:spotlessCheck :transport-ws:detekt`
Expected: PASS. Test-only dependencies do not affect the dumps.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml transport-ws/build.gradle.kts transport-ws/src/jvmTest
git commit -m "test(transport): prove private-CA wss:// reachability via the hook"
```

---

### Task 5: Documentation

**Files:**
- Modify: `transport-ws/Module.md`
- Modify: `README.md:335-392` (the "Custom TLS trust" section)
- Modify: `AGENTS.md:46-47` and `AGENTS.md:53-56`
- Modify: `CHANGELOG.md:8` (under `## [Unreleased]`)

**Interfaces:**
- Consumes: the public API from Task 2.
- Produces: nothing.

- [ ] **Step 1: Extend `transport-ws/Module.md`**

Insert this before the closing "Available on every target…" paragraph, mirroring the structure of
`transport-tcp/Module.md`'s "Trusting a private CA" section:

```markdown
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

Unlike the TCP transport, this hook does not set the SNI server name: the CIO client derives SNI
from the request URL, which is also what gates ktor's RFC 6125 subject-name check.
```

Note for the implementer: the fenced `kotlin` block above is nested inside a fenced markdown block
in *this plan*. Write it into `Module.md` as a normal triple-backtick block.

- [ ] **Step 2: Update the README**

Two edits in the "Custom TLS trust" section:

1. Replace the opening sentence "By default the TCP transport validates the broker certificate
   against the platform CA store." with: "By default both transports validate the broker
   certificate against the platform CA store."
2. Replace the composition snippet and the final paragraph (currently ending "The WebSocket
   transport has no equivalent hook yet.") with:

```markdown
The hook composes with transport selection as usual, and both transports take the same lambda type,
so one trust manager can serve both:

```kotlin
transportFactory = TcpTransportFactory { trustManager = myPrivateCaTrustManager } +
    WebSocketTransportFactory { trustManager = myPrivateCaTrustManager }
```

`TLSConfigBuilder` comes from `io.ktor:ktor-network-tls`, exposed transitively by both
`mqtt-client-transport-tcp` and `mqtt-client-transport-ws` — no extra dependency needed.
`trustManager` specifically is available on the JVM and Android actuals of `TLSConfigBuilder`; on
Apple and Linux the hook still runs, but `TLSConfigBuilder` exposes a different set of properties
there. The WebSocket hook is additionally ignored on Windows (the WinHttp engine has no
TLS-configuration surface) and in the browser (which cannot influence trust) — see
`transport-ws/Module.md` for the per-target table.
```

- [ ] **Step 3: Update `AGENTS.md`**

In the module layout block, change the `:transport-ws` entry to note the new source set:

```
:transport-ws              commonMain (WebSocketTransport) + cioMain (CIO engine + TLS trust hook)
                           + per-platform Ktor engine deps. Targets: all, incl. wasmJs.
```

And replace the "There is **no** custom `nonWebMain` source set any more…" sentence with:

```markdown
The one custom intermediate source set is `:transport-ws`'s `cioMain` (jvm + android + apple +
linux), which holds the single CIO `HttpClient` builder and the TLS trust plumbing. The old
`nonWebMain` set is gone: `:transport-tcp` simply omits the wasmJs target, so its `commonMain` is
effectively the old "non-web" set.
```

- [ ] **Step 4: Add the changelog entry**

Under `## [Unreleased]` in `CHANGELOG.md`:

```markdown
### Added

- `WebSocketTransportFactory` now accepts an optional TLS customisation lambda, the WebSocket
  counterpart to the `TcpTransportFactory` hook added in 0.6.0. Both take the same
  `TLSConfigBuilder` receiver, so a single trust manager can serve `ssl://` and `wss://` brokers
  behind a private or self-signed CA — scoped to the MQTT connection, with no app-wide
  `network_security_config.xml` anchor. Honoured on JVM, Android, Apple, and Linux; ignored on
  Windows (WinHttp exposes no TLS-configuration surface) and in the browser. The existing no-arg
  constructor is unchanged (#107).

### Changed

- `:transport-ws` now selects its Ktor engine explicitly per platform (CIO on JVM/Android/Apple/
  Linux, WinHttp on Windows, Js on wasmJs) instead of relying on auto-detection. The engines are
  the same ones auto-detection resolved, so behaviour is unchanged.
```

- [ ] **Step 5: Verify the docs build**

Run: `./gradlew :transport-ws:dokkaGeneratePublicationHtml`
Expected: PASS with no unresolved-link warnings for the new KDoc references.

- [ ] **Step 6: Commit**

```bash
git add transport-ws/Module.md README.md AGENTS.md CHANGELOG.md
git commit -m "docs: document the WebSocket TLS trust hook"
```

---

### Task 6: Full baseline verification

**Files:** none created; fixes land in whichever file fails.

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: a branch ready for PR.

- [ ] **Step 1: Run the full gate**

Run: `./gradlew spotlessCheck detekt allTests apiCheck koverVerify`
Expected: PASS. Notes on likely failures:

- `koverVerify` (≥80%): the new `configurePlatformTrust` no-op actuals and the ignored-hook engine
  actuals are hard to cover. If coverage drops below the threshold, do **not** lower it — check
  first whether `WebSocketPrivateCaTest` is actually running (it covers the CIO path end to end).
- `allTests` includes `wasmJsTest`, which is what proves the wasmJs actual compiles and the Js
  engine still works.

- [ ] **Step 2: Confirm binary compatibility by inspection**

Run: `git diff main -- transport-ws/api`
Expected: additions only. Any removed line in `transport-ws.klib.api` or `api/jvm/transport-ws.api`
means an existing consumer would fail to link — stop and fix.

- [ ] **Step 3: Verify the module boundary still holds**

Run: `./gradlew :core:check`
Expected: PASS, including `verifyModuleBoundary` and the Konsist architecture suite. `:core` was not
touched, so this is a regression guard rather than a change.

- [ ] **Step 4: Commit any fixes and push**

```bash
git add -A
git commit -m "chore: satisfy verification gates for the WS TLS hook"
git push -u origin ws-tls-trust-hook
```

Then open the PR with title `feat(transport): add TLS trust hook to WebSocketTransportFactory`, a
bullet list of the changes, `Fixes #107`, and an explicit note that the Meshtastic-Android
`<certificates src="user"/>` anchor in `base-config` can come out once both transports are wired.
