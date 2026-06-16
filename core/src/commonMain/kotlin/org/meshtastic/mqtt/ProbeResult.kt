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

/**
 * Curated, public-API subset of the broker capabilities advertised in CONNACK
 * properties (§3.2.2.3), surfaced via [ProbeResult.Success]. Mirrors the internal
 * `MqttProperties` shape but exposes only fields that are actionable for a probe
 * diagnostic UI.
 *
 * @property assignedClientIdentifier Client identifier assigned by the broker when
 *   the probe sent `clientId = ""`. Useful for confirming the broker accepts
 *   anonymous identifiers.
 * @property serverKeepAliveSeconds Keepalive interval the broker overrode (§3.2.2.3.14),
 *   or `null` if the broker accepted the requested keepalive.
 * @property maximumQosOrdinal Maximum QoS the broker will deliver (0, 1, or 2).
 *   `null` means the broker did not include the property and supports up to QoS 2.
 * @property retainAvailable `true` (or `null`) when the broker honors `RETAIN=1` PUBLISH
 *   packets; `false` if retained messages are disabled.
 * @property wildcardSubscriptionAvailable `true` (or `null`) when wildcard topic
 *   filters (`+`, `#`) are supported.
 * @property sharedSubscriptionAvailable `true` (or `null`) when shared subscriptions
 *   (§4.8.2) are supported.
 * @property subscriptionIdentifiersAvailable `true` (or `null`) when subscription
 *   identifiers (§3.8.2.1.2) are supported.
 * @property maximumPacketSize Largest PUBLISH payload the broker will accept, or `null`.
 * @property receiveMaximum Maximum number of in-flight QoS 1/2 publishes the broker
 *   will accept from the client at once.
 * @property topicAliasMaximum Maximum topic alias the client may use (§3.3.2.3.4).
 * @property serverReference Optional server-reference hint included even on success
 *   for some brokers (§4.13).
 * @property responseInformation Optional response-information topic prefix (§3.1.2.11.6).
 */
public data class ProbeServerInfo(
    val assignedClientIdentifier: String? = null,
    val serverKeepAliveSeconds: Int? = null,
    val maximumQosOrdinal: Int? = null,
    val retainAvailable: Boolean? = null,
    val wildcardSubscriptionAvailable: Boolean? = null,
    val sharedSubscriptionAvailable: Boolean? = null,
    val subscriptionIdentifiersAvailable: Boolean? = null,
    val maximumPacketSize: Long? = null,
    val receiveMaximum: Int? = null,
    val topicAliasMaximum: Int? = null,
    val serverReference: String? = null,
    val responseInformation: String? = null,
)

/**
 * Outcome of a one-shot connectivity probe issued via [MqttClient.probe].
 *
 * A probe attempts a full CONNECT/CONNACK handshake against an [MqttEndpoint] using
 * a transient connection, then tears it down. Unlike [MqttClient.connect], a probe
 * **never throws** for connectivity failures — every outcome (success, broker
 * rejection, transport error, timeout) is reported as a [ProbeResult] subclass so
 * UI layers can render structured diagnostic feedback.
 *
 * Cancellation of the calling coroutine is propagated normally and will abort the
 * probe; only [kotlinx.coroutines.CancellationException] escapes.
 *
 * ## Example
 * ```kotlin
 * when (val result = MqttClient.probe(MqttEndpoint.Tcp("broker.example.com", 1883))) {
 *     is ProbeResult.Success     -> showOk("Reachable")
 *     is ProbeResult.Rejected    -> showError("Broker rejected: ${result.reasonCode}")
 *     is ProbeResult.DnsFailure  -> showError("Host not found")
 *     is ProbeResult.TcpFailure  -> showError("Connection refused")
 *     is ProbeResult.TlsFailure  -> showError("TLS handshake failed: ${result.cause.message}")
 *     is ProbeResult.Timeout     -> showError("Timed out after ${result.durationMs}ms")
 *     is ProbeResult.Other       -> showError("Connection failed: ${result.cause.message}")
 * }
 * ```
 */
public sealed class ProbeResult {
    /**
     * The broker accepted the CONNECT and returned a successful CONNACK.
     *
     * @property serverInfo Capability metadata extracted from the broker's CONNACK
     *   properties (§3.2.2.3) — useful for diagnosing capability mismatches
     *   (e.g. `maximumQos`, `retainAvailable`, `wildcardSubscriptionAvailable`).
     */
    public data class Success(
        val serverInfo: ProbeServerInfo,
    ) : ProbeResult()

    /**
     * The broker accepted the TCP/TLS connection but refused the CONNECT request via CONNACK.
     *
     * Typical reason codes:
     * - [ReasonCode.NOT_AUTHORIZED] — credentials are missing or invalid.
     * - [ReasonCode.BAD_USER_NAME_OR_PASSWORD] — credentials format issue.
     * - [ReasonCode.BAD_AUTHENTICATION_METHOD] — enhanced auth required (§4.12).
     * - [ReasonCode.CLIENT_IDENTIFIER_NOT_VALID] — client ID rejected by broker policy.
     * - [ReasonCode.UNSUPPORTED_PROTOCOL_VERSION] — broker is not MQTT 5.0.
     * - [ReasonCode.USE_ANOTHER_SERVER] / [ReasonCode.SERVER_MOVED] — redirect; see [serverReference].
     *
     * @property reasonCode The CONNACK reason code from the broker.
     * @property message Human-readable explanation taken from the underlying error.
     * @property serverReference Optional server reference for redirect handling (§4.13).
     */
    public data class Rejected(
        val reasonCode: ReasonCode,
        val message: String,
        val serverReference: String? = null,
    ) : ProbeResult()

    /**
     * Hostname resolution failed — the broker host could not be looked up via DNS.
     *
     * @property cause The underlying platform exception.
     */
    public data class DnsFailure(
        val cause: Throwable,
    ) : ProbeResult()

    /**
     * TCP-level failure — connection refused, network unreachable, or socket reset
     * before the MQTT handshake could begin.
     *
     * @property cause The underlying platform exception.
     */
    public data class TcpFailure(
        val cause: Throwable,
    ) : ProbeResult()

    /**
     * TLS handshake failed — certificate validation rejected the broker, the broker
     * does not speak TLS on this port, or the negotiated protocol/cipher is unsupported.
     *
     * @property cause The underlying platform exception.
     */
    public data class TlsFailure(
        val cause: Throwable,
    ) : ProbeResult()

    /**
     * The probe did not complete within the requested timeout. The transport may have
     * hung mid-handshake (broker accepted the TCP connection but never sent CONNACK).
     *
     * @property durationMs The probe timeout that was exceeded, in milliseconds.
     */
    public data class Timeout(
        val durationMs: Long,
    ) : ProbeResult()

    /**
     * Catch-all for failures that don't fit any other category.
     *
     * @property cause The underlying exception.
     */
    public data class Other(
        val cause: Throwable,
    ) : ProbeResult()
}
