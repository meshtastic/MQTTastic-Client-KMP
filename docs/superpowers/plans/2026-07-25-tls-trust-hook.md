# TLS Trust Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a caller customise TLS trust for the MQTT TCP socket alone by passing a `TLSConfigBuilder.() -> Unit` lambda to `TcpTransportFactory`.

**Architecture:** The TLS setup currently inlined in `TcpTransport.connect` moves into one internal extension function, `TLSConfigBuilder.applyMqttTls(host, configureTls)`. That function is both the single production call site and the unit-test target. It applies `serverName` first, then the caller's lambda, then `configurePlatformTrust(host)` — in that order, so that a caller-assigned `trustManager` gets *wrapped* by Android's `HostnameAwareTrustManager` rather than replacing it. The lambda is stored on `TcpTransportFactory` and handed to each `TcpTransport` it creates.

**Tech Stack:** Kotlin 2.4.10 Multiplatform, Gradle 9.6.1, ktor-network + ktor-network-tls 3.5.1, `kotlin.test`, binary-compatibility-validator (klib + JVM dumps), detekt, spotless/ktlint, kover.

Spec: `docs/superpowers/specs/2026-07-25-tls-trust-hook-design.md`. Issue: [#102](https://github.com/meshtastic/MQTTastic-Client-KMP/issues/102).

## Global Constraints

- Module touched: `:transport-tcp` only. No changes to `:core`, `:transport-ws`, or `:bom`.
- Never import `java.*` or `android.*` in `commonMain` or `commonTest`. `trustManager` is a JVM/Android-only property of `TLSConfigBuilder` — any test touching it belongs in `jvmTest`.
- Zero new dependencies. `ktor-network-tls` is already declared; only its configuration scope changes.
- Every new/modified `.kt` file needs the GPL-3.0 header from `config/spotless/copyright.kt`. `spotlessApply` will insert it.
- Public API additions must be `public` and reflected in **both** `transport-tcp/api/jvm/transport-tcp.api` and `transport-tcp/api/transport-tcp.klib.api` via `./gradlew apiDump`.
- Both new constructor parameters are defaulted (`= null`) so existing source and compiled callers keep working.
- Commit format: Conventional Commits, `<type>(<scope>): <subject>`, imperative, no period, subject under 50 chars. Scope for this work: `transport`.
- Final gate before handing off: `./gradlew spotlessCheck detekt allTests apiCheck koverVerify` must pass.

---

## File Structure

| File | Responsibility | Action |
| --- | --- | --- |
| `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt` | Existing `expect fun configurePlatformTrust`. Gains the new `applyMqttTls` orchestrator — it belongs beside the platform-trust declaration it sequences, not in the transport. | Modify |
| `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt` | Transport + factory. Gains the constructor parameters; its inline `tls {}` body shrinks to one `applyMqttTls` call. | Modify |
| `transport-tcp/build.gradle.kts` | Promote `ktor-network-tls` from `implementation` to `api`. | Modify |
| `transport-tcp/api/jvm/transport-tcp.api` | JVM ABI baseline. | Regenerate |
| `transport-tcp/api/transport-tcp.klib.api` | Native/common ABI baseline. | Regenerate |
| `transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTlsTest.kt` | Existing SNI guard tests. Gains `applyMqttTls` ordering/invocation tests. | Modify |
| `transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportFactoryTlsTest.kt` | Factory-level tests: the hook-carrying factory still `supports`/`create`s correctly and composes with `+`. | Create |
| `transport-tcp/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTrustManagerTest.kt` | JVM-only proof that a lambda-assigned `trustManager` reaches ktor's real builder state. | Create |
| `transport-tcp/Module.md` | Dokka module doc — private-CA snippet. | Modify |
| `README.md` | New `### Custom TLS trust` section before `## Android / KMP Integration`. | Modify |

Task 1 delivers the seam and its common tests. Task 2 wires the public API, the build change, and the ABI dumps. Task 3 adds the JVM trust-manager proof. Task 4 documents. Tasks 2 and 3 each depend on the one before; a reviewer can reject any of them independently.

---

### Task 1: The `applyMqttTls` seam

Extract the TLS configuration into one internal function with the correct ordering, and cover it in `commonTest`. No public API change yet — `TcpTransport` calls the new function with a `null` lambda, so behaviour is identical to today.

**Files:**
- Modify: `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt` (append after line 38)
- Modify: `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt:102-108`
- Test: `transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTlsTest.kt`

**Interfaces:**
- Consumes: existing `internal fun sniServerName(host: String): String?` and `internal expect fun TLSConfigBuilder.configurePlatformTrust(host: String)`, both already in this package.
- Produces: `internal fun TLSConfigBuilder.applyMqttTls(host: String, configureTls: (TLSConfigBuilder.() -> Unit)? = null)`. Task 2 calls this with a non-null lambda.

- [ ] **Step 1: Write the failing tests**

Append these four tests inside the existing `class TcpTransportTlsTest` in
`transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTlsTest.kt`
(before the closing brace). Also add `import io.ktor.network.tls.TLSConfigBuilder` to the import
block — the existing imports are `kotlin.test.*` assertions only.

```kotlin
    @Test
    fun applyMqttTlsSetsSniForDnsHost() {
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("broker.example.com")
        assertEquals("broker.example.com", builder.serverName)
    }

    @Test
    fun applyMqttTlsSuppressesSniForIpLiteral() {
        val ipv4 = TLSConfigBuilder()
        ipv4.applyMqttTls("192.168.1.50")
        assertNull(ipv4.serverName)

        val ipv6 = TLSConfigBuilder()
        ipv6.applyMqttTls("2001:db8::1")
        assertNull(ipv6.serverName)
    }

    @Test
    fun applyMqttTlsInvokesCallerHookExactlyOnce() {
        var calls = 0
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("broker.example.com") { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun applyMqttTlsRunsCallerHookAfterServerNameIsSet() {
        // The hook must observe serverName already assigned, so a caller can read or
        // override it. This also pins the hook ahead of configurePlatformTrust, which
        // on Android wraps whatever trustManager the hook installed.
        var observed: String? = "not-yet-run"
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("broker.example.com") { observed = serverName }
        assertEquals("broker.example.com", observed)
    }

    @Test
    fun applyMqttTlsWithoutHookStillSetsSni() {
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("mqtt.local", configureTls = null)
        assertEquals("mqtt.local", builder.serverName)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :transport-tcp:jvmTest --tests "org.meshtastic.mqtt.transport.tcp.TcpTransportTlsTest"
```

Expected: compilation failure — `Unresolved reference: applyMqttTls`. A compile error is the
correct "fail" signal for a Kotlin TDD cycle; do not proceed until you have seen it.

- [ ] **Step 3: Implement `applyMqttTls`**

Append to `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt`,
after the existing `configurePlatformTrust` declaration:

```kotlin
/**
 * Applies the MQTT TLS configuration to this [TLSConfigBuilder] in the one correct order.
 *
 * This is the single call site for TLS setup, so the ordering below cannot drift:
 *
 * 1. [serverName] — the SNI value, `null` for IP literals (RFC 6066 §3 forbids them).
 * 2. [configureTls] — the caller's hook, so it can read or override the SNI value and
 *    install its own trust manager (e.g. a private CA).
 * 3. [configurePlatformTrust] — reads whatever trust manager is now on the builder and,
 *    on Android, wraps it in a hostname-aware delegate.
 *
 * Step 2 must precede step 3. Reversing them would let a caller-supplied trust manager
 * *replace* Android's `HostnameAwareTrustManager`, silently dropping the platform's 3-arg
 * `checkServerTrusted` hostname verification. Running the caller first composes instead:
 * a private-CA trust manager is still subject to the hostname check.
 *
 * @param host the broker host (DNS name or IP literal), used for both SNI and trust evaluation.
 * @param configureTls optional caller customisation; `null` preserves the default behaviour.
 */
internal fun TLSConfigBuilder.applyMqttTls(
    host: String,
    configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) {
    serverName = sniServerName(host)
    configureTls?.invoke(this)
    configurePlatformTrust(host)
}
```

- [ ] **Step 4: Route `TcpTransport.connect` through the seam**

In `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt`,
replace this block (currently lines 102-108):

```kotlin
                            rawSocket.tls(tlsContext) {
                                serverName = tlsServerName
                                // Configure trust with the real host (DNS name or IP literal),
                                // not the SNI value: tlsServerName is null for IP literals, but
                                // Android still needs a host for the hostname-aware trust check.
                                configurePlatformTrust(endpoint.host)
                            }
```

with:

```kotlin
                            rawSocket.tls(tlsContext) { applyMqttTls(endpoint.host) }
```

Then delete the now-unused local `val tlsServerName = sniServerName(endpoint.host)` on the
preceding line (currently line 87) — `applyMqttTls` computes the SNI value itself. Leave the
`sniServerName` and `isIpLiteral` top-level functions in place; they are still used by
`applyMqttTls` and by the existing tests.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :transport-tcp:jvmTest --tests "org.meshtastic.mqtt.transport.tcp.TcpTransportTlsTest"
```

Expected: PASS, 9 tests (4 pre-existing SNI tests + 5 new).

- [ ] **Step 6: Verify no other target regressed and the code is clean**

```bash
./gradlew :transport-tcp:allTests :transport-tcp:detekt
./gradlew spotlessApply
```

Expected: all green. `spotlessApply` may reformat; that is fine.

- [ ] **Step 7: Commit**

```bash
git add transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/PlatformTls.kt \
        transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt \
        transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTlsTest.kt
git commit -m "refactor(transport): extract applyMqttTls TLS seam"
```

---

### Task 2: Public `configureTls` hook on the factory and transport

Expose the lambda, promote the ktor TLS dependency so consumers can name `TLSConfigBuilder`, and regenerate the ABI baselines.

**Files:**
- Modify: `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt` (class declarations at lines 52 and 271)
- Modify: `transport-tcp/build.gradle.kts:44-49`
- Modify: `transport-tcp/api/jvm/transport-tcp.api`, `transport-tcp/api/transport-tcp.klib.api`
- Test: `transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportFactoryTlsTest.kt` (create)

**Interfaces:**
- Consumes: `applyMqttTls(host, configureTls)` from Task 1.
- Produces: `public class TcpTransportFactory(configureTls: (TLSConfigBuilder.() -> Unit)? = null)` and `public class TcpTransport(configureTls: (TLSConfigBuilder.() -> Unit)? = null)`. Task 3 constructs `TcpTransportFactory { … }` and Task 4 documents it.

- [ ] **Step 1: Write the failing test**

Create `transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportFactoryTlsTest.kt`:

```kotlin
package org.meshtastic.mqtt.transport.tcp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.meshtastic.mqtt.MqttEndpoint
import org.meshtastic.mqtt.plus

/**
 * Covers the caller-supplied TLS hook on [TcpTransportFactory] (issue #102).
 *
 * The hook itself is only exercised during a real TLS handshake, so these tests pin the
 * surrounding contract: the factory still selects the right endpoints, still produces a
 * [TcpTransport], and still composes with other factories via `+`.
 */
class TcpTransportFactoryTlsTest {
    @Test
    fun factoryWithTlsHookStillSupportsOnlyTcpEndpoints() {
        val factory = TcpTransportFactory { serverName = "override.example.com" }
        assertTrue(factory.supports(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true)))
        assertFalse(factory.supports(MqttEndpoint.WebSocket("wss://broker.example.com/mqtt")))
    }

    @Test
    fun factoryWithTlsHookCreatesTcpTransport() {
        val factory = TcpTransportFactory { serverName = "override.example.com" }
        val transport = factory.create(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true))
        assertIs<TcpTransport>(transport)
        assertFalse(transport.isConnected)
    }

    @Test
    fun noArgFactoryStillWorks() {
        val factory = TcpTransportFactory()
        assertTrue(factory.supports(MqttEndpoint.Tcp("broker.example.com", 1883, tls = false)))
        assertIs<TcpTransport>(factory.create(MqttEndpoint.Tcp("broker.example.com", 1883, tls = false)))
    }

    @Test
    fun factoryWithTlsHookComposesWithPlus() {
        val combined = TcpTransportFactory { serverName = "override.example.com" } + TcpTransportFactory()
        assertTrue(combined.supports(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true)))
        assertIs<TcpTransport>(combined.create(MqttEndpoint.Tcp("broker.example.com", 8883, tls = true)))
    }
}
```

Note on the `+` test: it combines two TCP factories rather than pulling in
`WebSocketTransportFactory`, because `:transport-tcp` must not depend on `:transport-ws`. That
still exercises `plus`, which is what needs covering here.

Both endpoint signatures used above are verified against
`core/src/commonMain/kotlin/org/meshtastic/mqtt/MqttTransport.kt:81-111` —
`MqttEndpoint.Tcp(host, port = 1883, tls = false)` and
`MqttEndpoint.WebSocket(url, protocols = listOf("mqtt"))`. `plus` is a top-level operator
extension declared at `core/src/commonMain/kotlin/org/meshtastic/mqtt/MqttTransportFactory.kt:51`,
which is why it needs its own `import org.meshtastic.mqtt.plus`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :transport-tcp:jvmTest --tests "org.meshtastic.mqtt.transport.tcp.TcpTransportFactoryTlsTest"
```

Expected: compilation failure — `Too many arguments for public constructor TcpTransportFactory()`,
because the factory does not accept a lambda yet.

- [ ] **Step 3: Add the constructor parameters**

In `transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt`,
add the import:

```kotlin
import io.ktor.network.tls.TLSConfigBuilder
```

Change the `TcpTransport` declaration (currently line 52) from `public class TcpTransport : MqttTransport {` to:

```kotlin
public class TcpTransport(
    private val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransport {
```

Extend its KDoc (currently lines 45-51) with a `@param`:

```kotlin
 * @param configureTls optional hook applied to ktor's [TLSConfigBuilder] during the TLS
 *   handshake, after the SNI server name is set and before platform trust is configured.
 *   Use it to trust a private or self-signed CA for this connection only. Ignored for
 *   non-TLS endpoints.
```

Pass it through at the `tls` call site — the line written in Task 1 Step 4 becomes:

```kotlin
                            rawSocket.tls(tlsContext) { applyMqttTls(endpoint.host, configureTls) }
```

Then replace the whole `TcpTransportFactory` declaration (currently lines 265-275) with:

```kotlin
/**
 * [MqttTransportFactory] that builds a [TcpTransport] for [MqttEndpoint.Tcp] endpoints.
 *
 * Add `org.meshtastic:mqtt-client-transport-tcp` and pass an instance to
 * [org.meshtastic.mqtt.MqttConfig.Builder.transportFactory].
 *
 * To trust a broker whose certificate chain is not in the platform CA store — a private or
 * self-signed CA — supply [configureTls]. The scope is this MQTT connection only, unlike
 * Android's app-wide `network_security_config.xml` trust anchors:
 *
 * ```kotlin
 * transportFactory = TcpTransportFactory { trustManager = myTrustManager } + WebSocketTransportFactory()
 * ```
 *
 * @param configureTls optional hook applied to ktor's [TLSConfigBuilder] for every transport
 *   this factory creates. It runs after the SNI server name is set and before platform trust
 *   is configured, so on Android a trust manager installed here is still wrapped in the
 *   hostname-aware delegate rather than replacing it.
 */
public class TcpTransportFactory(
    private val configureTls: (TLSConfigBuilder.() -> Unit)? = null,
) : MqttTransportFactory {
    override fun supports(endpoint: MqttEndpoint): Boolean = endpoint is MqttEndpoint.Tcp

    override fun create(endpoint: MqttEndpoint): MqttTransport = TcpTransport(configureTls)
}
```

- [ ] **Step 4: Promote the ktor TLS dependency**

In `transport-tcp/build.gradle.kts`, change the `commonMain.dependencies` block (lines 44-48) to:

```kotlin
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.ktor.network)
            // api, not implementation: TLSConfigBuilder appears in TcpTransportFactory's
            // public constructor signature, so consumers need it on their compile classpath.
            api(libs.ktor.network.tls)
        }
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :transport-tcp:jvmTest --tests "org.meshtastic.mqtt.transport.tcp.TcpTransportFactoryTlsTest"
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Confirm `apiCheck` fails, then regenerate the baselines**

```bash
./gradlew :transport-tcp:apiCheck
```

Expected: FAIL — the dumps do not yet list the new constructors. Seeing this failure first
confirms the ABI change is real and being tracked. Then:

```bash
./gradlew :transport-tcp:apiDump
git diff transport-tcp/api/
```

Inspect the diff and confirm all three of the following, which together prove the
binary-compatibility claim in the spec:

1. `transport-tcp/api/jvm/transport-tcp.api` gains
   `public fun <init> (Lkotlin/jvm/functions/Function1;)V` and a synthetic
   `<init> (Lkotlin/jvm/functions/Function1;ILkotlin/jvm/internal/DefaultConstructorMarker;)V`
   for both `TcpTransport` and `TcpTransportFactory`.
2. The existing `public fun <init> ()V` lines are **still present** for both classes. This is the
   zero-arg constructor Kotlin emits for an all-defaults constructor — its survival is what keeps
   already-compiled `TcpTransportFactory()` callers linking. If either disappeared, stop: the
   change is binary-breaking and needs revisiting.
3. `transport-tcp/api/transport-tcp.klib.api` gains
   `constructor <init>(kotlin.Function1<io.ktor.network.tls.TLSConfigBuilder, kotlin.Unit>? = ...)`
   for both classes.

Note the klib dump header lists only `[iosArm64, iosSimulatorArm64, linuxArm64, linuxX64, macosArm64, mingwX64]`. On a Linux host, BCV skips the Apple targets and trusts the committed dump. If the diff drops the Apple entries from the header or the declarations, do not commit that — regenerate on a macOS host, per the comment in `transport-tcp/build.gradle.kts:52-62`.

- [ ] **Step 7: Verify the full module and re-check the API**

```bash
./gradlew spotlessApply
./gradlew :transport-tcp:allTests :transport-tcp:detekt :transport-tcp:apiCheck
```

Expected: all green, `apiCheck` now passing against the regenerated dumps.

- [ ] **Step 8: Commit**

```bash
git add transport-tcp/src/commonMain/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransport.kt \
        transport-tcp/build.gradle.kts \
        transport-tcp/api/ \
        transport-tcp/src/commonTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportFactoryTlsTest.kt
git commit -m "feat(transport): add TLS trust hook to TcpTransportFactory"
```

---

### Task 3: JVM proof that the hook reaches ktor's builder

The `commonTest` suite cannot touch `trustManager` — it exists only on the JVM/Android actuals of `TLSConfigBuilder`. This task proves in `jvmTest` that a trust manager installed by the caller's lambda is genuinely present on the builder ktor will use, rather than on a discarded copy.

**Files:**
- Test: `transport-tcp/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTrustManagerTest.kt` (create)

**Interfaces:**
- Consumes: `applyMqttTls` (Task 1) and the public hook (Task 2).
- Produces: nothing consumed by later tasks.

`:transport-tcp` has no `src/jvmTest` directory yet — creating the file creates the source set. The `mqtt.kmp.library` convention plugin already declares the `jvm` target, so no build-file change is required and `kotlin.test` is already available to it.

- [ ] **Step 1: Write the failing test**

Create `transport-tcp/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTrustManagerTest.kt`:

```kotlin
package org.meshtastic.mqtt.transport.tcp

import io.ktor.network.tls.TLSConfigBuilder
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * JVM-only checks that the caller's TLS hook mutates the same [TLSConfigBuilder] ktor
 * consumes, using `trustManager` — a property that exists only on the JVM and Android
 * actuals of [TLSConfigBuilder], and so cannot be asserted from `commonTest`.
 *
 * On the JVM, `configurePlatformTrust` is a no-op, so a hook-installed trust manager should
 * survive `applyMqttTls` untouched. On Android it is deliberately *not* preserved by
 * identity — it gets wrapped in a hostname-aware delegate. That wrapping needs the
 * `X509TrustManagerExtensions` framework class and is therefore out of unit-test scope.
 */
class TcpTransportTrustManagerTest {
    private object FakeTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun hookInstalledTrustManagerSurvivesApplyMqttTls() {
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("broker.example.com") { trustManager = FakeTrustManager }
        assertSame(FakeTrustManager, builder.trustManager)
    }

    @Test
    fun hookInstalledTrustManagerSurvivesForIpLiteralHost() {
        // SNI is suppressed for IP literals, but the hook must still be applied — the
        // earlier SNI/trust coupling bug (Meshtastic-Android #5894) was exactly this
        // class of mistake.
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("192.168.1.50") { trustManager = FakeTrustManager }
        assertSame(FakeTrustManager, builder.trustManager)
    }

    @Test
    fun hookWriteToTrustManagerTakesEffect() {
        // The lambda runs against the live builder and its write survives the rest of
        // applyMqttTls. This does not assert the hook/platform-trust ordering — on the JVM
        // configurePlatformTrust is a no-op, so that ordering is not observable here.
        var ranWithBuilder = false
        val builder = TLSConfigBuilder()
        builder.applyMqttTls("broker.example.com") {
            ranWithBuilder = true
            trustManager = FakeTrustManager
        }
        assertNotNull(builder.trustManager)
        assertSame(FakeTrustManager, builder.trustManager)
        kotlin.test.assertTrue(ranWithBuilder)
    }

    @Test
    fun factoryDoesNotInvokeHookAtCreateTime() {
        // The hook belongs to the handshake, not to transport construction.
        var invocations = 0
        val factory = TcpTransportFactory { invocations++ }
        // create() must not run the hook — that happens during the handshake.
        factory.create(org.meshtastic.mqtt.MqttEndpoint.Tcp("broker.example.com", 8883, tls = true))
        kotlin.test.assertEquals(0, invocations)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :transport-tcp:jvmTest --tests "org.meshtastic.mqtt.transport.tcp.TcpTransportTrustManagerTest"
```

Expected: FAIL. If Tasks 1 and 2 are complete these should actually pass on the first run — that
is acceptable here, because this task is a regression guard over already-built behaviour rather
than a driver of new code. What matters is that you run them and see the result. If instead you
get a compile error about `builder.trustManager` being unresolved, the file has landed in the
wrong source set — confirm the path is `src/jvmTest`, not `src/commonTest`.

- [ ] **Step 3: Verify the whole module and lint the new source set**

```bash
./gradlew spotlessApply
./gradlew :transport-tcp:allTests :transport-tcp:detekt
```

Expected: all green. `detekt` now covers `jvmTest` too; if it flags the fully-qualified
`org.meshtastic.mqtt.MqttEndpoint.Tcp` call or the `kotlin.test.` prefixes, hoist those to imports.

- [ ] **Step 4: Commit**

```bash
git add transport-tcp/src/jvmTest/kotlin/org/meshtastic/mqtt/transport/tcp/TcpTransportTrustManagerTest.kt
git commit -m "test(transport): cover TLS hook trust manager on JVM"
```

---

### Task 4: Documentation

Document the hook where a consumer hitting the issue #102 problem will actually look.

**Files:**
- Modify: `transport-tcp/Module.md`
- Modify: `README.md` (insert before line 335, `## Android / KMP Integration`)

**Interfaces:**
- Consumes: the public API from Task 2.
- Produces: nothing.

`AGENTS.md` needs no change — its public-surface list already covers transport modules generically ("Transport modules add `TcpTransport`/`TcpTransportFactory`…").

- [ ] **Step 1: Extend the Dokka module doc**

In `transport-tcp/Module.md`, insert between the existing code fence (ends line 13) and the
"Available on JVM, Android, …" paragraph (line 15):

```markdown
### Trusting a private CA

If the broker's certificate is issued by a private or self-signed CA that is not in the platform
trust store, pass a TLS customisation lambda. It receives ktor's `TLSConfigBuilder`:

```kotlin
val client = MqttClient("sensor") {
    transportFactory = TcpTransportFactory { trustManager = myTrustManager }
}
```

The lambda is applied after the SNI server name is set and before platform trust configuration, so
on Android a trust manager installed here is still wrapped in the hostname-aware delegate rather
than replacing it. The added trust applies only to this MQTT connection — unlike Android's
app-wide `network_security_config.xml` trust anchors.
```

- [ ] **Step 2: Add the README section**

In `README.md`, insert immediately before line 335 (`## Android / KMP Integration`), after the
"Log levels from most to least verbose…" line that closes the Logging section:

```markdown
### Custom TLS trust

By default the TCP transport validates the broker certificate against the platform CA store. To
reach a broker behind a private or self-signed CA, pass a TLS customisation lambda to
`TcpTransportFactory`. It runs against ktor's `TLSConfigBuilder`:

```kotlin
import org.meshtastic.mqtt.transport.tcp.TcpTransportFactory

val client = MqttClient("my-client") {
    transportFactory = TcpTransportFactory { trustManager = myPrivateCaTrustManager }
}
client.connect(MqttEndpoint.parse("mqtts://broker.internal:8883"))
```

The hook is applied after the SNI server name is resolved and before platform trust is configured.
On Android that ordering matters: your trust manager is *wrapped* by the hostname-aware trust
manager rather than replacing it, so certificate hostname verification still happens.

This scopes the extra trust to the MQTT connection alone. It replaces the app-wide workaround of
adding `<certificates src="user"/>` to `network_security_config.xml`, which would affect every
HTTPS connection the app makes.

The hook composes with transport selection as usual:

```kotlin
transportFactory = TcpTransportFactory { trustManager = myPrivateCaTrustManager } +
    WebSocketTransportFactory()
```

`TLSConfigBuilder` comes from `io.ktor:ktor-network-tls`, exposed transitively by
`mqtt-client-transport-tcp` — no extra dependency needed. The WebSocket transport has no equivalent
hook yet.
```

- [ ] **Step 3: Verify the docs build**

```bash
./gradlew :transport-tcp:dokkaGeneratePublicationHtml
```

Expected: BUILD SUCCESSFUL. Nested code fences inside a Markdown block are the usual failure
mode here — if Dokka warns about unparsed content in `Module.md`, check the fence nesting.

- [ ] **Step 4: Commit**

```bash
git add transport-tcp/Module.md README.md
git commit -m "docs(transport): document the TLS trust hook"
```

---

### Task 5: Full baseline verification

**Files:** none modified (unless a gate fails).

- [ ] **Step 1: Run the complete gate**

```bash
./gradlew spotlessCheck detekt allTests apiCheck koverVerify
```

Expected: BUILD SUCCESSFUL. Notes on the two gates most likely to complain:

- `koverVerify` enforces ≥80% coverage. The new code is one small function plus two constructor
  parameters, all directly covered, so coverage should rise rather than fall. If it fails, read
  `./gradlew koverHtmlReport` output before adding any test.
- `apiCheck` must pass against the dumps committed in Task 2. If it fails here but passed then,
  something after Task 2 changed the ABI — re-run `apiDump` and inspect the diff rather than
  accepting it blindly.

- [ ] **Step 2: Confirm the default path is genuinely unchanged**

Read the final `TcpTransport.connect` TLS block and confirm that with `configureTls == null` the
sequence is exactly `serverName = sniServerName(host)` then `configurePlatformTrust(host)` — the
same two operations, in the same order, as before this change. This is the no-regression claim for
every existing consumer; verify it by reading, not by assuming.

- [ ] **Step 3: Report**

State the gate output verbatim. If any gate failed, say so with its output rather than describing
the work as complete.

---

## Notes for the implementer

- **Why the ordering is the crux.** Issue #102 asks for the hook to run *after* `configurePlatformTrust`, while also asking that a caller trust manager compose with Android's hostname checking. Those are mutually exclusive. `PlatformTls.android.kt:44-51` reads `trustManager` off the builder and wraps whatever it finds, so composition requires the caller to have written first. The spec resolves this in favour of composition. Do not "fix" the order to match the issue text.
- **`TLSConfigBuilder` is instantiable in `commonTest`.** It is an `expect` class with a public no-arg constructor and a common `serverName` property — verified against the ktor 3.5.1 klib. Only `trustManager` is JVM/Android-only.
- **Do not add a `host` parameter to the lambda**, a second post-platform-trust seam, a WebSocket equivalent, or any `MqttConfig`-level plumbing. All four are explicitly out of scope in the spec.
- **PR wording:** title `feat(transport): add TLS trust hook to TcpTransportFactory`, body closing with `Fixes #102`, and a note that `ktor-network-tls` is now an `api` dependency of `mqtt-client-transport-tcp` — that is the one thing a consumer might notice on upgrade.
