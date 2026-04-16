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
 * Log levels for the MQTTastic library, ordered from most verbose to least verbose.
 *
 * Configure via [MqttConfig.logLevel] to control which messages are delivered
 * to your [MqttLogger] implementation. Messages below the configured level are
 * discarded with zero overhead (no string construction).
 */
public enum class MqttLogLevel {
    /** Wire-level detail: packet bytes, Variable Byte Integer encoding, raw frames. */
    TRACE,

    /** Protocol flow: packet sent/received, state transitions, QoS handshake steps. */
    DEBUG,

    /** Lifecycle events: connected, disconnected, subscribed, unsubscribed. */
    INFO,

    /** Recoverable issues: reconnecting, retry attempts, PINGRESP timeout. */
    WARN,

    /** Failures: connection lost, decode errors, broker rejections. */
    ERROR,

    /** Disable all logging. No messages are delivered regardless of logger configuration. */
    NONE,
}

/**
 * Logging interface for MQTTastic library consumers.
 *
 * Implement this interface to receive log output from the MQTT client.
 * Configure it via [MqttConfig.logger] along with [MqttConfig.logLevel].
 *
 * ## Example
 * ```kotlin
 * val config = MqttConfig(
 *     clientId = "my-client",
 *     logger = MqttLogger.println(),
 *     logLevel = MqttLogLevel.DEBUG,
 * )
 * ```
 *
 * @see MqttLogLevel
 */
public interface MqttLogger {
    /**
     * Called when the library produces a log message at or above the configured level.
     *
     * @param level The severity level of this log message.
     * @param tag A short identifier for the source component (e.g. "MqttClient", "MqttConnection").
     * @param message The log message. Only constructed when the level passes filtering.
     * @param throwable Optional exception associated with this log entry.
     */
    public fun log(
        level: MqttLogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    public companion object {
        /**
         * Creates a simple println-based logger for quick debugging.
         *
         * Output format: `[level] tag: message` with optional throwable stack trace.
         */
        public fun println(): MqttLogger =
            object : MqttLogger {
                override fun log(
                    level: MqttLogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    val line = "[$level] $tag: $message"
                    kotlin.io.println(line)
                    throwable?.let { kotlin.io.println(it.stackTraceToString()) }
                }
            }

        /**
         * Creates an explicitly silent logger that discards all messages.
         *
         * Functionally equivalent to passing `null` as the logger, but conveys
         * intent more clearly in configuration code.
         */
        public fun noop(): MqttLogger =
            object : MqttLogger {
                override fun log(
                    level: MqttLogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    // Intentionally empty
                }
            }
    }
}

/**
 * Internal logging helper used throughout the library.
 *
 * Wraps a nullable [MqttLogger] with level filtering and lazy message construction.
 * When the logger is null or the message level is below [minLevel], the message
 * lambda is never invoked — achieving zero overhead when logging is disabled.
 */
internal class MqttLoggerInternal(
    private val logger: MqttLogger?,
    private val minLevel: MqttLogLevel,
) {
    /** Returns `true` if a message at [level] would be delivered. */
    fun isEnabled(level: MqttLogLevel): Boolean = logger != null && minLevel != MqttLogLevel.NONE && level >= minLevel

    inline fun trace(
        tag: String,
        message: () -> String,
    ) {
        if (isEnabled(MqttLogLevel.TRACE)) {
            logger?.log(MqttLogLevel.TRACE, tag, message())
        }
    }

    inline fun debug(
        tag: String,
        message: () -> String,
    ) {
        if (isEnabled(MqttLogLevel.DEBUG)) {
            logger?.log(MqttLogLevel.DEBUG, tag, message())
        }
    }

    inline fun info(
        tag: String,
        message: () -> String,
    ) {
        if (isEnabled(MqttLogLevel.INFO)) {
            logger?.log(MqttLogLevel.INFO, tag, message())
        }
    }

    inline fun warn(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (isEnabled(MqttLogLevel.WARN)) {
            logger?.log(MqttLogLevel.WARN, tag, message(), throwable)
        }
    }

    inline fun error(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ) {
        if (isEnabled(MqttLogLevel.ERROR)) {
            logger?.log(MqttLogLevel.ERROR, tag, message(), throwable)
        }
    }

    internal companion object {
        /** Shared no-op instance for when logging is disabled. */
        val NOOP = MqttLoggerInternal(null, MqttLogLevel.NONE)
    }
}
