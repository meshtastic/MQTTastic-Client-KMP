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
import kotlin.test.assertFailsWith

class TopicValidatorTest {
    // --- Topic Name validation (§4.7.1 - for PUBLISH) ---

    @Test
    fun validTopicNames() {
        // Simple topics
        TopicValidator.validateTopicName("sport/tennis")
        TopicValidator.validateTopicName("a")
        TopicValidator.validateTopicName("/")
        TopicValidator.validateTopicName("sport/tennis/player1")
        TopicValidator.validateTopicName("/leading/slash")
        TopicValidator.validateTopicName("trailing/slash/")
        TopicValidator.validateTopicName("sport/tennis/player1/ranking")
    }

    @Test
    fun emptyTopicNameRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicName("")
        }
    }

    @Test
    fun topicNameWithNullCharRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicName("sport\u0000tennis")
        }
    }

    @Test
    fun topicNameWithHashRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicName("sport/#")
        }
    }

    @Test
    fun topicNameWithPlusRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicName("sport/+/player")
        }
    }

    // --- Topic Filter validation (§4.7.1 - for SUBSCRIBE) ---

    @Test
    fun validTopicFilters() {
        TopicValidator.validateTopicFilter("sport/tennis")
        TopicValidator.validateTopicFilter("#")
        TopicValidator.validateTopicFilter("sport/#")
        TopicValidator.validateTopicFilter("+")
        TopicValidator.validateTopicFilter("+/tennis/#")
        TopicValidator.validateTopicFilter("sport/+/player")
        TopicValidator.validateTopicFilter("+/+")
        TopicValidator.validateTopicFilter("+/tennis/+")
        TopicValidator.validateTopicFilter("/")
        TopicValidator.validateTopicFilter("sport/tennis/player1")
        // Shared subscriptions use $share prefix
        TopicValidator.validateTopicFilter("\$share/group/topic")
    }

    @Test
    fun emptyTopicFilterRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicFilter("")
        }
    }

    @Test
    fun topicFilterWithNullCharRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicFilter("sport\u0000tennis")
        }
    }

    @Test
    fun hashNotLastCharRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicFilter("sport/#/ranking")
        }
    }

    @Test
    fun hashNotPrecededBySlashRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicFilter("sport#")
        }
    }

    @Test
    fun plusNotPrecededBySlashRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicFilter("sport+/tennis")
        }
    }

    @Test
    fun plusNotFollowedBySlashRejected() {
        assertFailsWith<IllegalArgumentException> {
            TopicValidator.validateTopicFilter("sport/+tennis")
        }
    }

    // --- Builder DSL ---

    @Test
    fun mqttConfigBuilderWorks() {
        val config =
            MqttConfig.build {
                clientId = "test-client"
                keepAliveSeconds = 30
                cleanStart = false
                autoReconnect = false
            }
        kotlin.test.assertEquals("test-client", config.clientId)
        kotlin.test.assertEquals(30, config.keepAliveSeconds)
        kotlin.test.assertEquals(false, config.cleanStart)
        kotlin.test.assertEquals(false, config.autoReconnect)
    }

    @Test
    fun mqttConfigBuilderDefaults() {
        val config = MqttConfig.build {}
        kotlin.test.assertEquals("", config.clientId)
        kotlin.test.assertEquals(60, config.keepAliveSeconds)
        kotlin.test.assertEquals(true, config.cleanStart)
        kotlin.test.assertEquals(true, config.autoReconnect)
        kotlin.test.assertNull(config.logger)
        kotlin.test.assertEquals(MqttLogLevel.NONE, config.logLevel)
    }

    @Test
    fun mqttConfigBuilderValidation() {
        assertFailsWith<IllegalArgumentException> {
            MqttConfig.build {
                keepAliveSeconds = -1
            }
        }
    }
}
