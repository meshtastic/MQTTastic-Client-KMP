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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.meshtastic.mqtt.packet.ConnAck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MqttLoggerTest {
    /** Helper to create an [MqttLogger] from a lambda since [MqttLogger] is not a fun interface. */
    private fun testLogger(handler: (MqttLogLevel, String, String, Throwable?) -> Unit): MqttLogger =
        object : MqttLogger {
            override fun log(
                level: MqttLogLevel,
                tag: String,
                message: String,
                throwable: Throwable?,
            ) = handler(level, tag, message, throwable)
        }
    // --- MqttLogLevel ordering ---

    @Test
    fun logLevelOrderingIsCorrect() {
        val levels = MqttLogLevel.entries
        assertEquals(
            listOf(
                MqttLogLevel.TRACE,
                MqttLogLevel.DEBUG,
                MqttLogLevel.INFO,
                MqttLogLevel.WARN,
                MqttLogLevel.ERROR,
                MqttLogLevel.NONE,
            ),
            levels,
        )
    }

    @Test
    fun logLevelComparisonWorksViaOrdinal() {
        assertTrue(MqttLogLevel.TRACE < MqttLogLevel.DEBUG)
        assertTrue(MqttLogLevel.DEBUG < MqttLogLevel.INFO)
        assertTrue(MqttLogLevel.INFO < MqttLogLevel.WARN)
        assertTrue(MqttLogLevel.WARN < MqttLogLevel.ERROR)
        assertTrue(MqttLogLevel.ERROR < MqttLogLevel.NONE)
    }

    // --- MqttLoggerInternal level filtering ---

    @Test
    fun loggerDeliversMessagesAtOrAboveMinLevel() {
        val messages = mutableListOf<Pair<MqttLogLevel, String>>()
        val logger = testLogger { level, _, message, _ -> messages.add(level to message) }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.INFO)

        internal.trace("T") { "trace-msg" }
        internal.debug("T") { "debug-msg" }
        internal.info("T") { "info-msg" }
        internal.warn("T") { "warn-msg" }
        internal.error("T") { "error-msg" }

        assertEquals(3, messages.size)
        assertEquals(MqttLogLevel.INFO to "info-msg", messages[0])
        assertEquals(MqttLogLevel.WARN to "warn-msg", messages[1])
        assertEquals(MqttLogLevel.ERROR to "error-msg", messages[2])
    }

    @Test
    fun loggerAtTraceDeliversAllMessages() {
        val messages = mutableListOf<MqttLogLevel>()
        val logger = testLogger { level, _, _, _ -> messages.add(level) }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.TRACE)

        internal.trace("T") { "a" }
        internal.debug("T") { "b" }
        internal.info("T") { "c" }
        internal.warn("T") { "d" }
        internal.error("T") { "e" }

        assertEquals(5, messages.size)
        assertEquals(
            listOf(
                MqttLogLevel.TRACE,
                MqttLogLevel.DEBUG,
                MqttLogLevel.INFO,
                MqttLogLevel.WARN,
                MqttLogLevel.ERROR,
            ),
            messages,
        )
    }

    @Test
    fun loggerAtNoneDeliversNothing() {
        var called = false
        val logger = testLogger { _, _, _, _ -> called = true }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.NONE)

        internal.trace("T") { "a" }
        internal.debug("T") { "b" }
        internal.info("T") { "c" }
        internal.warn("T") { "d" }
        internal.error("T") { "e" }

        assertFalse(called)
    }

    // --- Null logger produces zero overhead ---

    @Test
    fun nullLoggerNeverConstructsMessages() {
        var lambdaCalled = false
        val internal = MqttLoggerInternal(null, MqttLogLevel.TRACE)

        internal.info("T") {
            lambdaCalled = true
            "should-not-be-constructed"
        }

        assertFalse(lambdaCalled)
    }

    @Test
    fun noneLogLevelNeverConstructsMessages() {
        var lambdaCalled = false
        val logger = testLogger { _, _, _, _ -> }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.NONE)

        internal.info("T") {
            lambdaCalled = true
            "should-not-be-constructed"
        }

        assertFalse(lambdaCalled)
    }

    @Test
    fun filteredLevelNeverConstructsMessages() {
        var lambdaCalled = false
        val logger = testLogger { _, _, _, _ -> }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.ERROR)

        internal.debug("T") {
            lambdaCalled = true
            "should-not-be-constructed"
        }

        assertFalse(lambdaCalled)
    }

    // --- Throwable propagation ---

    @Test
    fun throwablePropagatedInWarn() {
        var receivedThrowable: Throwable? = null
        val logger = testLogger { _, _, _, throwable -> receivedThrowable = throwable }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.TRACE)

        val exception = RuntimeException("test error")
        internal.warn("T", throwable = exception) { "warning" }

        assertNotNull(receivedThrowable)
        assertEquals("test error", receivedThrowable?.message)
    }

    @Test
    fun throwablePropagatedInError() {
        var receivedThrowable: Throwable? = null
        val logger = testLogger { _, _, _, throwable -> receivedThrowable = throwable }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.TRACE)

        val exception = IllegalStateException("fatal")
        internal.error("T", throwable = exception) { "error" }

        assertNotNull(receivedThrowable)
        assertEquals("fatal", receivedThrowable?.message)
    }

    @Test
    fun throwableIsNullWhenNotProvided() {
        var receivedThrowable: Throwable? = RuntimeException("sentinel")
        val logger = testLogger { _, _, _, throwable -> receivedThrowable = throwable }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.TRACE)

        internal.warn("T") { "no throwable" }

        assertNull(receivedThrowable)
    }

    // --- Tag propagation ---

    @Test
    fun tagIsPropagatedCorrectly() {
        var receivedTag: String? = null
        val logger = testLogger { _, tag, _, _ -> receivedTag = tag }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.TRACE)

        internal.info("MqttClient") { "test" }
        assertEquals("MqttClient", receivedTag)

        internal.debug("MqttConnection") { "test" }
        assertEquals("MqttConnection", receivedTag)
    }

    // --- isEnabled ---

    @Test
    fun isEnabledReflectsConfiguration() {
        val logger = testLogger { _, _, _, _ -> }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.WARN)

        assertFalse(internal.isEnabled(MqttLogLevel.TRACE))
        assertFalse(internal.isEnabled(MqttLogLevel.DEBUG))
        assertFalse(internal.isEnabled(MqttLogLevel.INFO))
        assertTrue(internal.isEnabled(MqttLogLevel.WARN))
        assertTrue(internal.isEnabled(MqttLogLevel.ERROR))
    }

    @Test
    fun isEnabledReturnsFalseForNullLogger() {
        val internal = MqttLoggerInternal(null, MqttLogLevel.TRACE)
        assertFalse(internal.isEnabled(MqttLogLevel.ERROR))
    }

    @Test
    fun isEnabledReturnsFalseForNoneLevel() {
        val logger = testLogger { _, _, _, _ -> }
        val internal = MqttLoggerInternal(logger, MqttLogLevel.NONE)
        assertFalse(internal.isEnabled(MqttLogLevel.ERROR))
    }

    // --- Default implementations ---

    @Test
    fun noopLoggerDoesNothing() {
        val noop = MqttLogger.noop()
        // Should not throw
        noop.log(MqttLogLevel.ERROR, "T", "test", RuntimeException("err"))
    }

    @Test
    fun printlnLoggerDoesNotThrow() {
        val println = MqttLogger.println()
        // Should not throw
        println.log(MqttLogLevel.INFO, "Test", "Hello world")
        println.log(MqttLogLevel.ERROR, "Test", "With error", RuntimeException("err"))
    }

    // --- MqttConfig integration ---

    @Test
    fun mqttConfigDefaultsToNoLogging() {
        val config = MqttConfig()
        assertNull(config.logger)
        assertEquals(MqttLogLevel.NONE, config.logLevel)
    }

    @Test
    fun mqttConfigAcceptsLoggerAndLevel() {
        val logger = MqttLogger.noop()
        val config = MqttConfig(logger = logger, logLevel = MqttLogLevel.DEBUG)
        assertNotNull(config.logger)
        assertEquals(MqttLogLevel.DEBUG, config.logLevel)
    }

    // --- Integration: MqttClient logs connect/disconnect ---

    @Test
    fun clientConnectDisconnectProducesLogMessages() =
        runTest {
            val messages = mutableListOf<Triple<MqttLogLevel, String, String>>()
            val logger = testLogger { level, tag, message, _ -> messages.add(Triple(level, tag, message)) }
            val config =
                MqttConfig(
                    clientId = "test",
                    keepAliveSeconds = 0,
                    cleanStart = true,
                    autoReconnect = false,
                    logger = logger,
                    logLevel = MqttLogLevel.DEBUG,
                )
            val transport = FakeTransport()
            transport.enqueuePacket(ConnAck(reasonCode = ReasonCode.SUCCESS))

            val client = MqttClient(config, transport, this)
            client.connect(MqttEndpoint.Tcp("localhost", 1883))
            advanceUntilIdle()

            // Verify connect-related log messages
            assertTrue(messages.any { it.first == MqttLogLevel.INFO && it.third.contains("Connecting") })
            assertTrue(messages.any { it.first == MqttLogLevel.INFO && it.third.contains("Connected") })

            messages.clear()
            client.disconnect()
            advanceUntilIdle()

            // Verify disconnect-related log messages
            assertTrue(messages.any { it.first == MqttLogLevel.INFO && it.third.contains("Disconnect") })

            client.close()
        }

    // --- NOOP companion ---

    @Test
    fun noopInternalLoggerNeverCallsLogger() {
        val noop = MqttLoggerInternal.NOOP
        var called = false
        // NOOP has null logger, so even if we call log methods nothing happens
        noop.info("T") {
            called = true
            "msg"
        }
        assertFalse(called)
        assertFalse(noop.isEnabled(MqttLogLevel.ERROR))
    }
}
