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
import org.meshtastic.mqtt.packet.PacketIdAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketIdAllocatorTest {
    @Test
    fun firstAllocationReturnsOne() =
        runTest {
            val allocator = PacketIdAllocator()
            assertEquals(1, allocator.allocate())
        }

    @Test
    fun sequentialAllocationsReturnIncrementingIds() =
        runTest {
            val allocator = PacketIdAllocator()
            assertEquals(1, allocator.allocate())
            assertEquals(2, allocator.allocate())
            assertEquals(3, allocator.allocate())
        }

    @Test
    fun releaseAndReallocateIsMonotonic() =
        runTest {
            val allocator = PacketIdAllocator()
            assertEquals(1, allocator.allocate())
            allocator.release(1)
            // Next allocation continues forward, does not reuse 1
            assertEquals(2, allocator.allocate())
        }

    @Test
    fun wrapsFromMaxBackToOne() =
        runTest {
            val allocator = PacketIdAllocator()
            // Allocate all IDs, then release them all
            for (i in 1..PacketIdAllocator.MAX_PACKET_ID) {
                allocator.allocate()
            }
            for (i in 1..PacketIdAllocator.MAX_PACKET_ID) {
                allocator.release(i)
            }
            // Next allocation wraps back to 1
            assertEquals(1, allocator.allocate())
        }

    @Test
    fun inFlightTrackingAllocatedVsReleased() =
        runTest {
            val allocator = PacketIdAllocator()
            val id = allocator.allocate()
            assertTrue(allocator.isInFlight(id))
            allocator.release(id)
            assertFalse(allocator.isInFlight(id))
        }

    @Test
    fun skipsInFlightIdsMonotonically() =
        runTest {
            val allocator = PacketIdAllocator()
            assertEquals(1, allocator.allocate())
            assertEquals(2, allocator.allocate())
            assertEquals(3, allocator.allocate())
            allocator.release(2)
            // Next allocation is 4, not 2 (monotonic forward scan)
            assertEquals(4, allocator.allocate())
        }

    @Test
    fun resetClearsAllState() =
        runTest {
            val allocator = PacketIdAllocator()
            allocator.allocate()
            allocator.allocate()
            allocator.reset()
            assertEquals(0, allocator.inFlightCount())
            assertEquals(1, allocator.allocate())
        }

    @Test
    fun inFlightCountTracksCorrectly() =
        runTest {
            val allocator = PacketIdAllocator()
            assertEquals(0, allocator.inFlightCount())
            allocator.allocate()
            assertEquals(1, allocator.inFlightCount())
            allocator.allocate()
            assertEquals(2, allocator.inFlightCount())
            allocator.release(1)
            assertEquals(1, allocator.inFlightCount())
            allocator.release(2)
            assertEquals(0, allocator.inFlightCount())
        }

    @Test
    fun exhaustAllIdsThrowsIllegalStateException() =
        runTest {
            val allocator = PacketIdAllocator()
            for (i in 1..PacketIdAllocator.MAX_PACKET_ID) {
                allocator.allocate()
            }
            assertFailsWith<IllegalStateException> {
                allocator.allocate()
            }
        }

    @Test
    fun releaseIsIdempotent() =
        runTest {
            val allocator = PacketIdAllocator()
            // Releasing a non-in-flight ID does not throw
            allocator.release(42)
            allocator.allocate()
            allocator.release(1)
            allocator.release(1) // second release is a no-op
            assertEquals(0, allocator.inFlightCount())
        }
}
