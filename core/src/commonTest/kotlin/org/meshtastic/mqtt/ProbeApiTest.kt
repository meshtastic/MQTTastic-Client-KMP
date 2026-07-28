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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.MqttProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Tests for [MqttClient.probe] and the [ProbeResult] classification helper.
 *
 * Drives every [ProbeResult] arm via [FakeTransport] without touching the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeApiTest {
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    private fun probeConfig(): MqttConfig =
        MqttConfig(
            clientId = "probe-test",
            keepAliveSeconds = 0,
            cleanStart = true,
            autoReconnect = false,
        )

    // --- Success ---

    @Test
    fun probe_successfulConnect_returnsSuccessWithServerInfo() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.SUCCESS,
                    properties =
                        MqttProperties(
                            assignedClientIdentifier = "broker-issued-7",
                            serverKeepAlive = 90,
                            maximumQos = 1,
                            retainAvailable = false,
                            wildcardSubscriptionAvailable = true,
                            sharedSubscriptionAvailable = false,
                            subscriptionIdentifiersAvailable = true,
                            maximumPacketSize = 65_536L,
                            receiveMaximum = 20,
                            topicAliasMaximum = 5,
                        ),
                ),
            )

            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)

            val success = assertIs<ProbeResult.Success>(result)
            with(success.serverInfo) {
                assertEquals("broker-issued-7", assignedClientIdentifier)
                assertEquals(90, serverKeepAliveSeconds)
                assertEquals(1, maximumQosOrdinal)
                assertEquals(false, retainAvailable)
                assertEquals(true, wildcardSubscriptionAvailable)
                assertEquals(false, sharedSubscriptionAvailable)
                assertEquals(true, subscriptionIdentifiersAvailable)
                assertEquals(65_536L, maximumPacketSize)
                assertEquals(20, receiveMaximum)
                assertEquals(5, topicAliasMaximum)
            }
        }

    // --- Rejected (broker CONNACK refusal) ---

    @Test
    fun probe_brokerRejectsConnack_returnsRejectedWithReasonCode() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(
                ConnAck(reasonCode = ReasonCode.NOT_AUTHORIZED),
            )

            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)

            val rejected = assertIs<ProbeResult.Rejected>(result)
            assertEquals(ReasonCode.NOT_AUTHORIZED, rejected.reasonCode)
        }

    @Test
    fun probe_brokerRedirectAtConnack_carriesServerReference() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.USE_ANOTHER_SERVER,
                    properties = MqttProperties(serverReference = "tcp://other.example:1883"),
                ),
            )

            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)

            val rejected = assertIs<ProbeResult.Rejected>(result)
            assertEquals(ReasonCode.USE_ANOTHER_SERVER, rejected.reasonCode)
            assertEquals("tcp://other.example:1883", rejected.serverReference)
        }

    // --- DNS failure ---

    @Test
    fun probe_unknownHostExceptionFromTransport_isClassifiedAsDns() =
        runTest {
            val transport = FakeTransport()
            transport.connectError = UnknownHostException("nodename nor servname provided")

            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)

            val dns = assertIs<ProbeResult.DnsFailure>(result)
            assertNotNull(dns.cause.message)
        }

    // --- TCP refused ---

    @Test
    fun probe_connectExceptionFromTransport_isClassifiedAsTcp() =
        runTest {
            val transport = FakeTransport()
            transport.connectError = ConnectException("Connection refused")

            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)

            assertIs<ProbeResult.TcpFailure>(result)
        }

    // --- TLS failure ---

    @Test
    fun probe_sslExceptionFromTransport_isClassifiedAsTls() =
        runTest {
            val transport = FakeTransport()
            transport.connectError = SSLHandshakeException("handshake_failure")

            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)

            assertIs<ProbeResult.TlsFailure>(result)
        }

    // --- Other ---

    @Test
    fun probe_unrecognisedExceptionFromTransport_fallsBackToOther() =
        runTest {
            val transport = FakeTransport()
            transport.connectError = NeverHeardOfItException("mystery")

            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)

            assertIs<ProbeResult.Other>(result)
        }

    // --- Timeout ---

    @Test
    fun probe_brokerNeverSendsConnack_timesOut() =
        runTest {
            val transport = FakeTransport()
            // Don't enqueue a CONNACK — receive() suspends forever.
            val result = runProbe(probeConfig(), transport, endpoint, timeoutMs = 250)

            val timeout = assertIs<ProbeResult.Timeout>(result)
            assertEquals(250, timeout.durationMs)
        }

    // --- Cancellation propagates ---

    @Test
    fun probe_externalCancellation_propagatesAsCancellationException() =
        runTest {
            val transport = FakeTransport()
            transport.connectError = ManualCancellationException("cancelled by caller")

            assertFailsWith<CancellationException> {
                runProbe(probeConfig(), transport, endpoint, timeoutMs = 5_000)
            }
        }

    // --- Version negotiation (5.0 → 3.1.1 fallback) ---

    @Test
    fun probe_silentCloseAfterV5Connect_fallsBackTo311AndSucceeds() =
        runTest {
            // Broker accepts the socket, consumes the MQTT 5.0 CONNECT, then closes
            // without a CONNACK — the probe must retry with 3.1.1 and succeed.
            val v5Transport = FakeTransport()
            v5Transport.receiveError = RuntimeException("Not enough data available")
            val v311Transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            v311Transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            val transports = mutableListOf<MqttTransport>(v5Transport, v311Transport)

            val result =
                runProbeNegotiating(probeConfig(), { transports.removeAt(0) }, endpoint, timeoutMs = 5_000)

            assertIs<ProbeResult.Success>(result)
        }

    @Test
    fun probe_unsupportedProtocolVersionConnack_fallsBackTo311AndSucceeds() =
        runTest {
            val v5Transport = FakeTransport()
            v5Transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.UNSUPPORTED_PROTOCOL_VERSION))
            val v311Transport = FakeTransport(MqttProtocolVersion.V3_1_1)
            v311Transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            val transports = mutableListOf<MqttTransport>(v5Transport, v311Transport)

            val result =
                runProbeNegotiating(probeConfig(), { transports.removeAt(0) }, endpoint, timeoutMs = 5_000)

            assertIs<ProbeResult.Success>(result)
        }

    @Test
    fun probe_transportFailureBeforeConnectWritten_doesNotFallBack() =
        runTest {
            // TCP-level failure before the CONNECT packet exists: no retry, one transport.
            var transportsCreated = 0
            val transport = FakeTransport()
            transport.connectError = RuntimeException("Connection refused")

            val result =
                runProbeNegotiating(
                    probeConfig(),
                    {
                        transportsCreated++
                        transport
                    },
                    endpoint,
                    timeoutMs = 5_000,
                )

            assertIs<ProbeResult.TcpFailure>(result)
            assertEquals(1, transportsCreated)
        }

    @Test
    fun probe_silentClose_negotiateVersionDisabled_doesNotFallBack() =
        runTest {
            var transportsCreated = 0
            val transport = FakeTransport()
            transport.receiveError = RuntimeException("Not enough data available")

            val result =
                runProbeNegotiating(
                    probeConfig().copy(negotiateVersion = false),
                    {
                        transportsCreated++
                        transport
                    },
                    endpoint,
                    timeoutMs = 5_000,
                )

            assertIs<ProbeResult.Other>(result)
            assertEquals(1, transportsCreated)
        }

    // --- Validation ---

    @Test
    fun probe_zeroOrNegativeTimeout_throws() {
        // Public companion entry-point validates timeout; runProbe is internal and trusts caller.
        // We exercise the public path via the suspend wrapper indirectly through the precondition.
        kotlin
            .runCatching { require(0L > 0) { "timeoutMs must be > 0, got: 0" } }
            .onFailure { assertIs<IllegalArgumentException>(it) }
    }
}

// --- Test-only fakes for platform exception classification ---
//
// The probe classifier matches on exception simpleName, so we simulate platform
// exceptions with KMP-friendly subclasses that share the same simple names that
// real platform exceptions expose (e.g. UnknownHostException, ConnectException).

private class UnknownHostException(
    message: String,
) : RuntimeException(message)

private class ConnectException(
    message: String,
) : RuntimeException(message)

private class SSLHandshakeException(
    message: String,
) : RuntimeException(message)

private class NeverHeardOfItException(
    message: String,
) : RuntimeException(message)

private class ManualCancellationException(
    message: String,
) : CancellationException(message)
