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

        val hashIdx = filter.indexOf('#')
        if (hashIdx >= 0) {
            // '#' must be the last character
            require(hashIdx == filter.length - 1) {
                "Multi-level wildcard '#' must be the last character in topic filter (§4.7.1.2)"
            }
            // If not the only character, must be preceded by '/'
            if (filter.length > 1) {
                require(filter[hashIdx - 1] == '/') {
                    "Multi-level wildcard '#' must be preceded by '/' (§4.7.1.2)"
                }
            }
        }

        // Validate '+' wildcard placement
        var i = 0
        while (i < filter.length) {
            if (filter[i] == '+') {
                // Must be at start or preceded by '/'
                if (i > 0) {
                    require(filter[i - 1] == '/') {
                        "Single-level wildcard '+' must be preceded by '/' or be at start (§4.7.1.3)"
                    }
                }
                // Must be at end or followed by '/'
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
