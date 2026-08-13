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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.mqtt.packet.ConnAck
import org.meshtastic.mqtt.packet.Disconnect
import org.meshtastic.mqtt.packet.PingReq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Teardown must never touch the transport while a write is in flight.
 *
 * The transport's byte channel is single-writer. Closing the socket underneath a coroutine that
 * is inside `writeFully`/`flush` mutates one kotlinx.io segment list from two coroutines and
 * corrupts it — `Segment.compact` "Check failed." or a NullPointerException in ktor's TLS
 * `writeRecord` (KTOR-7729). These tests hold a write open with [FakeTransport.sendGate] and
 * assert every teardown path waits for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TeardownRaceTest {
    private val endpoint = MqttEndpoint.Tcp("localhost", 1883)

    private fun config(keepAliveSeconds: Int = 0) = MqttConfig(clientId = "teardown-test", keepAliveSeconds = keepAliveSeconds)

    private suspend fun connected(
        transport: FakeTransport,
        connection: MqttConnection,
    ) {
        transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))
        connection.connect(endpoint)
    }

    @Test
    fun disconnectWaitsForInFlightPublish() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, config(), this)
            connected(transport, connection)

            val gate = CompletableDeferred<Unit>()
            transport.sendGate = gate
            val publisher = launch { connection.publish(MqttMessage("a/b", byteArrayOf(1))) }
            runCurrent()
            assertEquals(1, transport.sendsInFlight, "publish should be parked inside send()")

            // runCurrent, not advanceUntilIdle: advancing virtual time would trip the
            // quiesce timeout, which is what the next test covers.
            val disconnector = launch { connection.disconnect() }
            runCurrent()
            assertEquals(0, transport.closeCount, "teardown must not close under an in-flight write")

            // Let the write finish; teardown then proceeds.
            transport.sendGate = null
            gate.complete(Unit)
            advanceUntilIdle()
            publisher.join()
            disconnector.join()

            assertEquals(0, transport.closesDuringSend)
            assertEquals(1, transport.closeCount)
            assertFalse(transport.isConnected)
            assertIs<Disconnect>(transport.lastSentPacket())
        }

    @Test
    fun fatalErrorTeardownWaitsForInFlightPublish() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, config(), this)
            connected(transport, connection)

            val gate = CompletableDeferred<Unit>()
            transport.sendGate = gate
            val publisher = launch { connection.publish(MqttMessage("a/b", byteArrayOf(1))) }
            runCurrent()

            // Break the read loop — this is the reconnect-churn path that crashed in production.
            transport.enqueueReceive(byteArrayOf(0))
            runCurrent()
            assertEquals(0, transport.closeCount, "fatal-error teardown must not close under a write")

            transport.sendGate = null
            gate.complete(Unit)
            advanceUntilIdle()
            publisher.join()

            assertEquals(0, transport.closesDuringSend)
            assertTrue(transport.closeCount >= 1, "transport must still be closed after teardown")
            assertFalse(transport.isConnected)
            assertIs<ConnectionState.Disconnected>(connection.connectionState.value)
        }

    @Test
    fun teardownClosesEvenIfWriterNeverCompletes() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, config(), this)
            connected(transport, connection)

            // A peer that stopped reading blocks writeFully until the TCP timeout. Teardown may
            // not wait that long — closing the socket is what unblocks the writer.
            val gate = CompletableDeferred<Unit>()
            transport.sendGate = gate
            val publisher = launch { connection.publish(MqttMessage("a/b", byteArrayOf(1))) }
            runCurrent()

            val disconnector = launch { connection.disconnect() }
            advanceTimeBy(MqttConnection.WRITER_QUIESCE_TIMEOUT_MS + 1)
            advanceUntilIdle()

            assertEquals(1, transport.closeCount, "teardown must give up waiting and close")
            assertFalse(transport.isConnected)
            // No DISCONNECT: writing one is precisely the unsafe act being avoided.
            assertTrue(transport.decodeSentPackets().none { it is Disconnect })

            gate.complete(Unit)
            transport.sendGate = null
            advanceUntilIdle()
            publisher.cancel()
            disconnector.join()
        }

    @Test
    fun teardownClosesEvenIfDisconnectWriteBlocks() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, config(), this)
            connected(transport, connection)

            // Nothing in flight, so teardown takes the lock immediately and writes its DISCONNECT
            // — into a socket whose buffer the dead peer never drains. The close that frees it
            // must not sit behind that write.
            transport.sendGate = CompletableDeferred()
            val disconnector = launch { connection.disconnect() }
            advanceTimeBy(MqttConnection.WRITER_QUIESCE_TIMEOUT_MS + 1)
            advanceUntilIdle()

            assertEquals(1, transport.closeCount, "close must not wait on a blocked DISCONNECT")
            assertFalse(transport.isConnected)
            disconnector.join()
        }

    @Test
    fun keepAliveCannotWriteAfterTeardown() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, config(keepAliveSeconds = 2), this)
            connected(transport, connection)
            // runCurrent, not advanceUntilIdle: the keepalive loop never runs dry, so advancing
            // to idle would drive it through a PINGRESP timeout and tear the connection down
            // before the disconnect under test.
            runCurrent()

            connection.disconnect()
            transport.clearSent()

            // The keepalive job is cancelled and joined under the send lock, so no PINGREQ can
            // reach a transport that is already closed.
            advanceTimeBy(10_000)
            runCurrent()

            assertTrue(transport.decodeSentPackets().none { it is PingReq })
            assertEquals(0, transport.closesDuringSend)
        }

    @Test
    fun brokerDisconnectTeardownWaitsForInFlightPublish() =
        runTest {
            val transport = FakeTransport()
            val connection = MqttConnection(transport, config(), this)
            connected(transport, connection)

            val gate = CompletableDeferred<Unit>()
            transport.sendGate = gate
            val publisher = launch { connection.publish(MqttMessage("a/b", byteArrayOf(1))) }
            runCurrent()

            transport.enqueuePacket(Disconnect(reasonCode = ReasonCode.SERVER_SHUTTING_DOWN))
            runCurrent()
            assertEquals(0, transport.closeCount)

            transport.sendGate = null
            gate.complete(Unit)
            advanceUntilIdle()
            publisher.join()

            assertEquals(0, transport.closesDuringSend)
            assertFalse(transport.isConnected)
        }
}
