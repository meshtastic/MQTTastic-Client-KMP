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
 * MQTT 5.0 topic name and topic filter validation per §4.7.
 *
 * - Topic names (for PUBLISH) must not contain wildcards (`#`, `+`).
 * - Topic filters (for SUBSCRIBE/UNSUBSCRIBE) may contain wildcards with specific placement rules.
 * - Neither may be empty or contain the null character (U+0000).
 * - Maximum length is 65,535 bytes when UTF-8 encoded.
 */
internal object TopicValidator {
    /** Maximum topic/filter length in bytes (UTF-8 encoded), per §1.5.4. */
    private const val MAX_TOPIC_LENGTH_BYTES = 65_535

    /**
     * Validate a topic name for publishing (§4.7.1).
     *
     * Topic names MUST NOT contain wildcard characters (`#` or `+`).
     *
     * @throws IllegalArgumentException if the topic name is invalid.
     */
    fun validateTopicName(topic: String) {
        require(topic.isNotEmpty()) { "Topic name must not be empty (§4.7)" }
        require('\u0000' !in topic) { "Topic name must not contain null character U+0000 (§4.7)" }
        require(topic.encodeToByteArray().size <= MAX_TOPIC_LENGTH_BYTES) {
            "Topic name exceeds maximum length of $MAX_TOPIC_LENGTH_BYTES bytes (§1.5.4)"
        }
        require('#' !in topic) { "Topic name must not contain wildcard '#' (§4.7.1)" }
        require('+' !in topic) { "Topic name must not contain wildcard '+' (§4.7.1)" }
    }

    /**
     * Validate a topic filter for subscribing/unsubscribing (§4.7.1).
     *
     * Supports shared subscription syntax `$share/{ShareName}/{filter}` per §4.8.2.
     * The ShareName must not be empty or contain `/`, `+`, or `#`.
     * The filter portion follows standard wildcard rules.
     *
     * Wildcard rules:
     * - `#` (multi-level) must be the last character, and if not the only character,
     *   must be preceded by `/` (e.g., `sport/#` or `#`).
     * - `+` (single-level) must occupy an entire level — preceded by `/` or at start,
     *   followed by `/` or at end (e.g., `+/tennis/#` or `sport/+/player`).
     *
     * @throws IllegalArgumentException if the topic filter is invalid.
     */
    fun validateTopicFilter(filter: String) {
        require(filter.isNotEmpty()) { "Topic filter must not be empty (§4.7)" }
        require('\u0000' !in filter) { "Topic filter must not contain null character U+0000 (§4.7)" }
        require(filter.encodeToByteArray().size <= MAX_TOPIC_LENGTH_BYTES) {
            "Topic filter exceeds maximum length of $MAX_TOPIC_LENGTH_BYTES bytes (§1.5.4)"
        }

        // Handle shared subscription prefix: $share/{ShareName}/{filter} (§4.8.2)
        val actualFilter =
            if (filter.startsWith("\$share/")) {
                val afterPrefix = filter.substring(7) // Skip "$share/"
                val slashIdx = afterPrefix.indexOf('/')
                require(slashIdx > 0) {
                    "Shared subscription must have format '\$share/{ShareName}/{filter}' (§4.8.2)"
                }
                val shareName = afterPrefix.substring(0, slashIdx)
                require('+' !in shareName && '#' !in shareName) {
                    "ShareName must not contain wildcard characters '+' or '#' (§4.8.2)"
                }
                val remaining = afterPrefix.substring(slashIdx + 1)
                require(remaining.isNotEmpty()) {
                    "Shared subscription filter must not be empty after '\$share/$shareName/' (§4.8.2)"
                }
                remaining
            } else {
                filter
            }

        validateWildcards(actualFilter)
    }

    /**
     * Check if a topic filter uses the shared subscription syntax `$share/{ShareName}/{filter}`.
     */
    fun isSharedSubscription(filter: String): Boolean = filter.startsWith("\$share/")

    /** Validate wildcard placement in the filter portion of a topic. */
    private fun validateWildcards(filter: String) {
        val hashIdx = filter.indexOf('#')
        if (hashIdx >= 0) {
            require(hashIdx == filter.length - 1) {
                "Multi-level wildcard '#' must be the last character in topic filter (§4.7.1.2)"
            }
            if (filter.length > 1) {
                require(filter[hashIdx - 1] == '/') {
                    "Multi-level wildcard '#' must be preceded by '/' (§4.7.1.2)"
                }
            }
        }

        var i = 0
        while (i < filter.length) {
            if (filter[i] == '+') {
                if (i > 0) {
                    require(filter[i - 1] == '/') {
                        "Single-level wildcard '+' must be preceded by '/' or be at start (§4.7.1.3)"
                    }
                }
                if (i < filter.length - 1) {
                    require(filter[i + 1] == '/') {
                        "Single-level wildcard '+' must be followed by '/' or be at end (§4.7.1.3)"
                    }
                }
            }
            i++
        }
    }
}
