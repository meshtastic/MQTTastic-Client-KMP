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

import kotlinx.coroutines.test.runTest
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.Connect
import org.meshtastic.mqtt.packet.PingReq
import org.meshtastic.mqtt.packet.PingResp
import org.meshtastic.mqtt.packet.ReasonCode
import org.meshtastic.mqtt.packet.encode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeTransportTest {
    private val transport = FakeTransport()
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    @Test
    fun connectSetsIsConnected() =
        runTest {
            assertFalse(transport.isConnected)
            transport.connect(endpoint)
            assertTrue(transport.isConnected)
        }

    @Test
    fun closeSetsIsConnectedFalse() =
        runTest {
            transport.connect(endpoint)
            transport.close()
            assertFalse(transport.isConnected)
        }

    @Test
    fun sendStoresPacketsInSentPackets() =
        runTest {
            transport.connect(endpoint)
            val data1 = byteArrayOf(1, 2, 3)
            val data2 = byteArrayOf(4, 5, 6)
            transport.send(data1)
            transport.send(data2)

            assertEquals(2, transport.sentPackets.size)
            assertContentEquals(data1, transport.sentPackets[0])
            assertContentEquals(data2, transport.sentPackets[1])
        }

    @Test
    fun receiveReturnsEnqueuedBytesInOrder() =
        runTest {
            transport.connect(endpoint)
            val first = byteArrayOf(10, 20)
            val second = byteArrayOf(30, 40)
            transport.enqueueReceive(first)
            transport.enqueueReceive(second)

            assertContentEquals(first, transport.receive())
            assertContentEquals(second, transport.receive())
        }

    @Test
    fun enqueuePacketAndDecodeSentPacketsRoundTrip() =
        runTest {
            transport.connect(endpoint)

            // Enqueue a CONNACK for the client to receive
            val connAck = ConnAck(sessionPresent = false, reasonCode = ReasonCode.SUCCESS)
            transport.enqueuePacket(connAck)
            val receivedBytes = transport.receive()

            // The received bytes should decode back to the same packet
            val decoded =
                org.meshtastic.mqtt.packet
                    .decodePacket(receivedBytes)
            assertIs<ConnAck>(decoded)
            assertEquals(connAck, decoded)

            // Send a CONNECT from the client side, then verify via decodeSentPackets
            val connect = Connect(clientId = "test-client")
            transport.send(connect.encode())

            val sent = transport.decodeSentPackets()
            assertEquals(1, sent.size)
            assertIs<Connect>(sent[0])
            assertEquals("test-client", (sent[0] as Connect).clientId)
        }

    @Test
    fun sendThrowsWhenNotConnected() =
        runTest {
            assertFailsWith<IllegalStateException> {
                transport.send(byteArrayOf(1))
            }
        }

    @Test
    fun receiveThrowsWhenNotConnected() =
        runTest {
            assertFailsWith<IllegalStateException> {
                transport.receive()
            }
        }

    @Test
    fun connectErrorCausesConnectToThrow() =
        runTest {
            transport.connectError = RuntimeException("connection refused")
            val ex =
                assertFailsWith<RuntimeException> {
                    transport.connect(endpoint)
                }
            assertEquals("connection refused", ex.message)
            assertFalse(transport.isConnected)
        }

    @Test
    fun sendErrorCausesSendToThrow() =
        runTest {
            transport.connect(endpoint)
            transport.sendError = RuntimeException("write failed")
            assertFailsWith<RuntimeException> {
                transport.send(byteArrayOf(1))
            }
        }

    @Test
    fun defensiveCopyMutatingOriginalDoesNotAffectStored() =
        runTest {
            transport.connect(endpoint)

            // send: mutating original after send should not affect stored copy
            val sent = byteArrayOf(1, 2, 3)
            transport.send(sent)
            sent[0] = 99
            assertEquals(1, transport.sentPackets[0][0])

            // enqueueReceive: mutating original after enqueue should not affect received copy
            val enqueued = byteArrayOf(10, 20)
            transport.enqueueReceive(enqueued)
            enqueued[0] = 99
            val received = transport.receive()
            assertEquals(10, received[0])
        }

    @Test
    fun lastSentPacketReturnsLastPacket() =
        runTest {
            transport.connect(endpoint)
            transport.send(PingReq.encode())
            transport.send(PingResp.encode())

            assertIs<PingResp>(transport.lastSentPacket())
        }

    @Test
    fun clearSentEmptiesSentHistory() =
        runTest {
            transport.connect(endpoint)
            transport.send(byteArrayOf(1))
            transport.clearSent()
            assertTrue(transport.sentPackets.isEmpty())
        }

    @Test
    fun resetRestoresInitialState() =
        runTest {
            transport.connect(endpoint)
            transport.send(byteArrayOf(1))
            transport.sendError = RuntimeException("boom")
            transport.receiveError = RuntimeException("boom")
            transport.connectError = RuntimeException("boom")

            transport.reset()

            assertFalse(transport.isConnected)
            assertTrue(transport.sentPackets.isEmpty())

            // Should be able to connect and use again
            transport.connect(endpoint)
            assertTrue(transport.isConnected)
            transport.send(byteArrayOf(2))
            assertEquals(1, transport.sentPackets.size)
        }
}
