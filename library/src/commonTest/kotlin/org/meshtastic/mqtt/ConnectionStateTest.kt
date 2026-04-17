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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Shape contract tests for [ConnectionState] sealed hierarchy. Behavioural assertions
 * (state transitions, reason population during connect/disconnect/reconnect) live in
 * MqttClientTest and MqttConnectionTest.
 */
class ConnectionStateTest {
    @Test
    fun connectedAndConnecting_areObjectSingletons() {
        assertSame(ConnectionState.Connected, ConnectionState.Connected)
        assertSame(ConnectionState.Connecting, ConnectionState.Connecting)
        assertNotEquals<ConnectionState>(ConnectionState.Connected, ConnectionState.Connecting)
    }

    @Test
    fun disconnectedIdle_isStableSingletonWithNullReason() {
        val a = ConnectionState.Disconnected.Idle
        val b = ConnectionState.Disconnected.Idle
        assertSame(a, b)
        assertNull(a.reason)
        // Default constructor matches the Idle singleton by value
        assertEquals(ConnectionState.Disconnected.Idle, ConnectionState.Disconnected())
    }

    @Test
    fun disconnectedWithReason_carriesPublicException() {
        val reason =
            MqttException.ConnectionLost(
                reasonCode = ReasonCode.SERVER_SHUTTING_DOWN,
                message = "bye",
            )
        val state = ConnectionState.Disconnected(reason = reason)
        assertNotNull(state.reason)
        assertEquals(ReasonCode.SERVER_SHUTTING_DOWN, state.reason?.reasonCode)
        // Two Disconnected instances with the same reason are value-equal
        assertEquals(ConnectionState.Disconnected(reason = reason), state)
        // But not equal to Idle once a reason is present
        assertNotEquals<ConnectionState>(ConnectionState.Disconnected.Idle, state)
    }

    @Test
    fun reconnecting_isDataClassWithAttemptAndLastError() {
        val r1 = ConnectionState.Reconnecting(attempt = 1)
        val r2 = ConnectionState.Reconnecting(attempt = 1)
        assertEquals(r1, r2)
        assertNull(r1.lastError)

        val err = MqttException.ConnectionLost(ReasonCode.UNSPECIFIED_ERROR, "boom")
        val withErr = ConnectionState.Reconnecting(attempt = 3, lastError = err)
        assertEquals(3, withErr.attempt)
        assertEquals(err, withErr.lastError)
        assertNotEquals(r1, withErr)
    }

    @Test
    fun sealedHierarchy_isExhaustiveOnIs() {
        val cases: List<ConnectionState> =
            listOf(
                ConnectionState.Connecting,
                ConnectionState.Connected,
                ConnectionState.Reconnecting(attempt = 0),
                ConnectionState.Disconnected.Idle,
            )
        for (state in cases) {
            // If a new subclass is added to the sealed hierarchy, exhaustiveness will force
            // a compile error in callers that pattern-match — this test just confirms each
            // current arm is reachable at runtime.
            val label =
                when (state) {
                    is ConnectionState.Connecting -> "connecting"
                    is ConnectionState.Connected -> "connected"
                    is ConnectionState.Reconnecting -> "reconnecting"
                    is ConnectionState.Disconnected -> "disconnected"
                }
            assertTrue(label.isNotEmpty())
        }
    }
}
