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
package org.meshtastic.mqtt.packet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe allocator for MQTT packet identifiers (1..65535).
 *
 * Packet IDs are monotonically increasing and wrap from 65535 back to 1.
 * IDs that are currently in flight are skipped during allocation.
 * All operations are guarded by a [Mutex] for coroutine safety.
 */
internal class PacketIdAllocator {
    private val mutex = Mutex()
    private var nextId: Int = 1
    private val inFlight = mutableSetOf<Int>()

    /** Allocate the next available packet identifier (1..65535). */
    suspend fun allocate(): Int =
        mutex.withLock {
            check(inFlight.size < MAX_PACKET_ID) { "All $MAX_PACKET_ID packet IDs are in flight" }

            // Scan forward from nextId, skipping IDs already in flight
            var id = nextId
            while (id in inFlight) {
                id = wrapId(id + 1)
            }
            inFlight.add(id)
            nextId = wrapId(id + 1)
            id
        }

    /** Release a packet identifier back to the pool. No-op if not in flight. */
    suspend fun release(id: Int) {
        mutex.withLock {
            inFlight.remove(id)
        }
    }

    /** Check if a packet ID is currently in flight. */
    suspend fun isInFlight(id: Int): Boolean = mutex.withLock { id in inFlight }

    /** Number of currently in-flight packet IDs. */
    suspend fun inFlightCount(): Int = mutex.withLock { inFlight.size }

    /** Reset the allocator, clearing all in-flight IDs and resetting the counter. */
    suspend fun reset() {
        mutex.withLock {
            inFlight.clear()
            nextId = 1
        }
    }

    /** Wrap a raw value into the valid 1..65535 range. */
    private fun wrapId(raw: Int): Int = ((raw - 1) % MAX_PACKET_ID) + 1

    internal companion object {
        const val MAX_PACKET_ID = 65_535
    }
}
