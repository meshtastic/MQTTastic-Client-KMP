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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.MqttProperties
import org.meshtastic.mqtt.packet.PingResp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * `connect()` must distinguish a broker that refused the CONNECT from a network that failed
 * before the broker ever answered.
 *
 * Both used to surface as [MqttException.ConnectionRejected], which made a TCP timeout or a TLS
 * chain failure indistinguishable from bad credentials — so a consumer with a retry loop either
 * hammered a broker that would never accept it, or gave up permanently on a transient network
 * blip. Only a genuine CONNACK carrying an error reason code is a rejection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectFailureClassificationTest {
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    private fun config(
        negotiateVersion: Boolean = false,
        autoReconnect: Boolean = false,
        cleanStart: Boolean = true,
        maxReconnectAttempts: Int = 0,
    ): MqttConfig =
        MqttConfig(
            clientId = "test-client",
            keepAliveSeconds = 0,
            cleanStart = cleanStart,
            negotiateVersion = negotiateVersion,
            autoReconnect = autoReconnect,
            maxReconnectAttempts = maxReconnectAttempts,
            reconnectBaseDelayMs = 100,
            reconnectMaxDelayMs = 500,
        )

    // --- Transport failures: retryable, never a rejection ---

    @Test
    fun tcpConnectRefusedSurfacesAsConnectionFailed() =
        runTest {
            val transport = FakeTransport()
            val refused = RuntimeException("Connection refused")
            transport.connectError = refused
            val client = MqttClient(config(), transport, this)

            val error =
                assertFailsWith<MqttException.ConnectionFailed> {
                    client.connect(endpoint)
                }
            // The original platform exception must survive for diagnostics.
            assertSame(refused, error.cause)

            client.close()
        }

    @Test
    fun tlsHandshakeFailureSurfacesAsConnectionFailed() =
        runTest {
            val transport = FakeTransport()
            transport.connectError =
                RuntimeException("Trust anchor for certification path not found")
            val client = MqttClient(config(), transport, this)

            assertFailsWith<MqttException.ConnectionFailed> {
                client.connect(endpoint)
            }

            client.close()
        }

    @Test
    fun socketClosedWhileAwaitingConnAckSurfacesAsConnectionFailed() =
        runTest {
            // CONNECT was written, then the socket died before any CONNACK arrived.
            val transport = FakeTransport()
            transport.receiveError = RuntimeException("Not enough data available")
            val client = MqttClient(config(), transport, this)

            assertFailsWith<MqttException.ConnectionFailed> {
                client.connect(endpoint)
            }

            client.close()
        }

    @Test
    fun connAckTimeoutSurfacesAsConnectionFailed() =
        runTest {
            // Broker accepts the socket and then says nothing at all.
            val transport = FakeTransport()
            val client = MqttClient(config(), transport, this)

            val error =
                assertFailsWith<MqttException.ConnectionFailed> {
                    client.connect(endpoint)
                }
            assertEquals(ReasonCode.UNSPECIFIED_ERROR, error.reasonCode)

            client.close()
        }

    @Test
    fun failedTransportIsClassifiedByOriginNotReasonCode() =
        runTest {
            // A transport failure has no broker reason code, so it synthesises UNSPECIFIED_ERROR.
            // That code must not be what decides the exception type.
            val transport = FakeTransport()
            transport.connectError = RuntimeException("Network is unreachable")
            val client = MqttClient(config(), transport, this)

            val error =
                assertFailsWith<MqttException.ConnectionFailed> {
                    client.connect(endpoint)
                }
            assertEquals(ReasonCode.UNSPECIFIED_ERROR, error.reasonCode)

            client.close()
        }

    // --- Broker refusals: still rejections ---

    @Test
    fun connAckErrorReasonCodeSurfacesAsConnectionRejected() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.BAD_USER_NAME_OR_PASSWORD))
            val client = MqttClient(config(), transport, this)

            val error =
                assertFailsWith<MqttException.ConnectionRejected> {
                    client.connect(endpoint)
                }
            assertEquals(ReasonCode.BAD_USER_NAME_OR_PASSWORD, error.reasonCode)

            client.close()
        }

    @Test
    fun brokerRefusalWithProtocolErrorReasonCodeIsStillARejection() =
        runTest {
            // PROTOCOL_ERROR is a legitimate CONNACK reason code. Because the broker answered,
            // this is a refusal — not the library detecting a spec violation.
            val transport = FakeTransport()
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.PROTOCOL_ERROR))
            val client = MqttClient(config(), transport, this)

            val error =
                assertFailsWith<MqttException.ConnectionRejected> {
                    client.connect(endpoint)
                }
            assertEquals(ReasonCode.PROTOCOL_ERROR, error.reasonCode)

            client.close()
        }

    @Test
    fun rejectionPreservesServerReference() =
        runTest {
            // A non-redirect reason code, so the client reports the refusal instead of chasing
            // the reference — the point here is that the mapping carries the field through.
            val transport = FakeTransport()
            transport.enqueuePacket(
                ConnAck(
                    reasonCode = ReasonCode.NOT_AUTHORIZED,
                    properties = MqttProperties(serverReference = "other.example.com:1883"),
                ),
            )
            val client = MqttClient(config(), transport, this)

            val error =
                assertFailsWith<MqttException.ConnectionRejected> {
                    client.connect(endpoint)
                }
            assertEquals("other.example.com:1883", error.serverReference)

            client.close()
        }

    @Test
    fun unsupportedProtocolVersionRemainsARejectionWhenNegotiationIsOff() =
        runTest {
            // Version negotiation failure is a broker verdict, so it stays a rejection.
            val transport = FakeTransport()
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.UNSUPPORTED_PROTOCOL_VERSION))
            val client = MqttClient(config(negotiateVersion = false), transport, this)

            assertFailsWith<MqttException.ConnectionRejected> {
                client.connect(endpoint)
            }

            client.close()
        }

    // --- Library-detected spec violations ---

    @Test
    fun sessionPresentWithCleanStartSurfacesAsProtocolError() =
        runTest {
            // §3.2.2.1.1 — the broker answered, but illegally. Not the broker refusing us.
            val transport = FakeTransport()
            transport.enqueuePacket(
                ConnAck(reasonCode = ReasonCode.SUCCESS, sessionPresent = true),
            )
            val client = MqttClient(config(cleanStart = true), transport, this)

            assertFailsWith<MqttException.ProtocolError> {
                client.connect(endpoint)
            }

            client.close()
        }

    @Test
    fun unexpectedPacketDuringHandshakeSurfacesAsProtocolError() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(PingResp)
            val client = MqttClient(config(), transport, this)

            assertFailsWith<MqttException.ProtocolError> {
                client.connect(endpoint)
            }

            client.close()
        }

    @Test
    fun handshakeProtocolViolationReleasesTheSocket() =
        runTest {
            // The throw for an unexpected handshake packet originates inside awaitConnAck, whose
            // rethrow path used to skip transport cleanup and leak the connection.
            val transport = FakeTransport()
            transport.enqueuePacket(PingResp)
            val client = MqttClient(config(), transport, this)

            assertFailsWith<MqttException.ProtocolError> {
                client.connect(endpoint)
            }
            assertFalse(transport.isConnected, "transport must be closed after a failed handshake")

            client.close()
        }

    // --- Reported connection state matches the thrown exception ---

    @Test
    fun disconnectedStateCarriesConnectionFailedForTransportFailures() =
        runTest {
            val transport = FakeTransport()
            transport.connectError = RuntimeException("Connection refused")
            val client = MqttClient(config(), transport, this)

            assertFailsWith<MqttException.ConnectionFailed> {
                client.connect(endpoint)
            }
            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertIs<MqttException.ConnectionFailed>(state.reason)

            client.close()
        }

    @Test
    fun disconnectedStateCarriesConnectionRejectedForBrokerRefusals() =
        runTest {
            val transport = FakeTransport()
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.NOT_AUTHORIZED))
            val client = MqttClient(config(), transport, this)

            assertFailsWith<MqttException.ConnectionRejected> {
                client.connect(endpoint)
            }
            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertIs<MqttException.ConnectionRejected>(state.reason)

            client.close()
        }

    // --- Retry policy: the reason the distinction exists ---

    @Test
    fun autoReconnectRetriesTransportFailuresInsteadOfGivingUp() =
        runTest {
            // A ConnectionRejected ends the reconnect loop after a single attempt. A transport
            // failure must not: it has to burn through the whole attempt budget and then report
            // ConnectionFailed as the final reason.
            val live = FakeTransport()
            live.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
            var transportsCreated = 0
            val client =
                MqttClient(config(autoReconnect = true, maxReconnectAttempts = 3), this) {
                    transportsCreated++
                    if (transportsCreated == 1) {
                        live
                    } else {
                        FakeTransport().apply { connectError = RuntimeException("Connection refused") }
                    }
                }

            client.connect(endpoint)
            advanceUntilIdle()
            assertEquals(1, transportsCreated)

            // Drop the established connection to start the reconnect loop.
            live.receiveError = RuntimeException("Connection reset")
            live.enqueuePacket(PingResp)
            advanceUntilIdle()
            advanceTimeBy(10_000)
            advanceUntilIdle()

            // 1 initial + 3 reconnect attempts — the loop did not stop at the first failure.
            assertEquals(4, transportsCreated)
            val state = client.connectionState.value
            assertIs<ConnectionState.Disconnected>(state)
            assertIs<MqttException.ConnectionFailed>(state.reason)

            client.close()
            advanceUntilIdle()
        }
}
