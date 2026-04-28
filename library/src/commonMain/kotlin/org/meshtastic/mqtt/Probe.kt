/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.mqtt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.meshtastic.mqtt.packet.MqttProperties

/**
 * Default timeout for [MqttClient.probe], in milliseconds.
 *
 * Five seconds is long enough to cover a slow TLS handshake on a flaky network but
 * short enough that a wedged broker doesn't block UI affordances like a "Test
 * Connection" button.
 */
public const val DEFAULT_PROBE_TIMEOUT_MS: Long = 5_000

/**
 * Run a one-shot connectivity probe against [endpoint] without disturbing any live client.
 *
 * Spins up a transient [MqttConnection], performs the CONNECT/CONNACK handshake,
 * tears it back down, and reports the outcome as a [ProbeResult]. This API never
 * throws for connectivity errors — see [ProbeResult] for the exhaustive outcome
 * hierarchy. Cancellation of the calling coroutine is propagated normally.
 *
 * The probe uses `clientId = ""` (broker-assigned) and `cleanStart = true` by
 * default; override via [configure] if your broker enforces specific credentials
 * or client identifiers. `autoReconnect` is forced to `false` regardless of the
 * configure block — probes are always single-shot.
 *
 * ## Example
 * ```kotlin
 * val result = MqttClient.probe(
 *     endpoint = MqttEndpoint.Tcp("broker.example.com", 8883, tls = true),
 *     timeoutMs = 3_000,
 * ) {
 *     username = "diag"
 *     password = ByteString("hunter2".encodeToByteArray())
 * }
 * ```
 *
 * @param endpoint The broker endpoint to probe.
 * @param timeoutMs Total wall-clock budget for the probe, in milliseconds.
 *   Default: [DEFAULT_PROBE_TIMEOUT_MS].
 * @param configure Optional configuration block applied to the transient client
 *   (`clientId`, `username`, `password`, …). `autoReconnect` is always overridden
 *   to `false` after the block runs.
 * @return A [ProbeResult] describing the outcome.
 */
public suspend fun MqttClient.Companion.probe(
    endpoint: MqttEndpoint,
    timeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    configure: MqttConfig.Builder.() -> Unit = {},
): ProbeResult {
    require(timeoutMs > 0) { "timeoutMs must be > 0, got: $timeoutMs" }
    val config = buildProbeConfig(configure)
    val transport = createPlatformTransport(endpoint)
    return runProbe(config, transport, endpoint, timeoutMs)
}

/**
 * Build the transient config used by [probe]. Forces `autoReconnect = false` and
 * defaults `keepAliveSeconds = 0` so the probe never starts a keepalive loop, but
 * lets the caller override clientId, credentials, will, etc.
 */
private fun buildProbeConfig(configure: MqttConfig.Builder.() -> Unit): MqttConfig =
    MqttConfig
        .Builder()
        .apply {
            clientId = ""
            keepAliveSeconds = 0
            cleanStart = true
            configure()
            autoReconnect = false
        }.build()

/**
 * Test-injectable variant that takes a pre-built transport. Public-internal so
 * `commonTest` can drive each [ProbeResult] arm via [FakeTransport].
 */
internal suspend fun runProbe(
    config: MqttConfig,
    transport: MqttTransport,
    endpoint: MqttEndpoint,
    timeoutMs: Long,
): ProbeResult {
    val probeScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MqttProbe"))
    val connection = MqttConnection(transport, config, probeScope)
    return try {
        val connAck = withTimeout(timeoutMs) { connection.connect(endpoint) }
        val serverInfo =
            if (config.protocolVersion.supportsProperties) {
                connAck.properties.toProbeServerInfo()
            } else {
                ProbeServerInfo() // Empty info for MQTT 3.1.1
            }
        ProbeResult.Success(serverInfo = serverInfo)
    } catch (e: TimeoutCancellationException) {
        ProbeResult.Timeout(durationMs = timeoutMs)
    } catch (e: CancellationException) {
        // External cancellation — propagate to preserve structured concurrency.
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable,
    ) {
        classifyProbeFailure(e, fallbackTimeoutMs = timeoutMs)
    } finally {
        // Tear down on a non-cancellable context so probe cleanup is reliable
        // even if the caller's coroutine is being cancelled.
        withContext(NonCancellable) {
            runCatching { connection.disconnect() }
            runCatching { transport.close() }
            probeScope.cancel()
        }
    }
}

/**
 * Map an arbitrary throwable raised during [MqttConnection.connect] to a
 * [ProbeResult] subclass, using the cause chain and class name to keep classification
 * KMP-friendly (no `java.*` / `android.*` references).
 */
@Suppress("ReturnCount")
internal fun classifyProbeFailure(
    error: Throwable,
    fallbackTimeoutMs: Long,
): ProbeResult {
    if (error is MqttException.ConnectionRejected) {
        return ProbeResult.Rejected(
            reasonCode = error.reasonCode,
            message = error.message ?: "Broker rejected the connection",
            serverReference = error.serverReference,
        )
    }
    if (error is MqttConnectionException && error.cause == null) {
        // CONNACK refusal path: MqttConnection throws MqttConnectionException directly (no cause)
        // when the broker returns a non-SUCCESS reason code. Treat as a Rejected outcome so
        // probe consumers can surface the broker's reason code to the user.
        return ProbeResult.Rejected(
            reasonCode = error.reasonCode,
            message = error.message ?: "Broker rejected the connection",
            serverReference = error.serverReference,
        )
    }
    // MqttConnection wraps non-Mqtt failures as MqttConnectionException with the
    // original Throwable attached as cause. Unwrap before classifying.
    val rootCause = unwrapCause(error)
    if (rootCause is TimeoutCancellationException) {
        return ProbeResult.Timeout(durationMs = fallbackTimeoutMs)
    }
    val name = rootCause::class.simpleName.orEmpty()
    val message = rootCause.message.orEmpty()

    return when {
        looksLikeDnsFailure(name, message) -> ProbeResult.DnsFailure(rootCause)
        looksLikeTlsFailure(name, message) -> ProbeResult.TlsFailure(rootCause)
        looksLikeTcpFailure(name, message) -> ProbeResult.TcpFailure(rootCause)
        else -> ProbeResult.Other(rootCause)
    }
}

private fun unwrapCause(error: Throwable): Throwable {
    // Stop at the deepest non-null cause so we classify on the underlying platform
    // exception rather than the MqttConnectionException wrapper.
    var current: Throwable = error
    while (true) {
        val next = current.cause ?: return current
        if (next === current) return current
        current = next
    }
}

private fun looksLikeDnsFailure(
    name: String,
    message: String,
): Boolean =
    "UnknownHost" in name ||
        "Resolve" in name ||
        "DnsResolveException" == name ||
        "nodename nor servname" in message ||
        "Name or service not known" in message

private fun looksLikeTlsFailure(
    name: String,
    message: String,
): Boolean =
    name.startsWith("SSL") ||
        name.startsWith("TLS") ||
        "TlsException" in name ||
        "X509" in name ||
        "Certificate" in name ||
        "handshake_failure" in message

private fun looksLikeTcpFailure(
    name: String,
    message: String,
): Boolean =
    "Connect" in name ||
        "Socket" in name ||
        "NetworkUnreachable" in name ||
        "Connection refused" in message ||
        "Network is unreachable" in message ||
        "Connection reset" in message

/** Project the internal [MqttProperties] into the public [ProbeServerInfo] shape. */
internal fun MqttProperties.toProbeServerInfo(): ProbeServerInfo =
    ProbeServerInfo(
        assignedClientIdentifier = assignedClientIdentifier,
        serverKeepAliveSeconds = serverKeepAlive,
        maximumQosOrdinal = maximumQos,
        retainAvailable = retainAvailable,
        wildcardSubscriptionAvailable = wildcardSubscriptionAvailable,
        sharedSubscriptionAvailable = sharedSubscriptionAvailable,
        subscriptionIdentifiersAvailable = subscriptionIdentifiersAvailable,
        maximumPacketSize = maximumPacketSize,
        receiveMaximum = receiveMaximum,
        topicAliasMaximum = topicAliasMaximum,
        serverReference = serverReference,
        responseInformation = responseInformation,
    )
