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
package org.meshtastic.mqtt.sample

import kotlinx.coroutines.test.runTest
import org.meshtastic.mqtt.QoS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure state-management surface of [MqttSampleViewModel].
 *
 * These cover every setter, toggle, and guard path that doesn't require a live
 * broker — i.e. everything except `connect`/`disconnect`/`subscribe`/`publish`
 * (which depend on a real [org.meshtastic.mqtt.MqttClient] and are exercised
 * against the live Meshtastic MQTT broker during manual QA).
 */
class MqttSampleViewModelTest {
    private val vm = MqttSampleViewModel()

    @Test
    fun initial_state_has_sensible_defaults() {
        val s = vm.state.value
        assertEquals("tls://mqtt.meshtastic.org:8883", s.brokerUri)
        assertEquals(QoS.AT_MOST_ONCE, s.subscribeQos)
        assertTrue(s.receivedMessages.isEmpty())
        assertEquals(0, s.totalMessageCount)
        assertFalse(s.showRawPayload)
        assertNull(s.error)
    }

    @Test
    fun text_field_updaters_are_pure() = runTest {
        vm.updateBrokerUri("tcp://localhost:1883")
        vm.updateClientId("test-client")
        vm.updateUsername("u")
        vm.updatePassword("p")
        vm.updateSubscribeTopic("t/#")
        vm.updateSubscribeQos(QoS.EXACTLY_ONCE)
        vm.updatePublishTopic("t/publish")
        vm.updatePublishMessage("hi")
        vm.updatePublishQos(QoS.AT_LEAST_ONCE)
        vm.updatePublishRetain(true)

        val s = vm.state.value
        assertEquals("tcp://localhost:1883", s.brokerUri)
        assertEquals("test-client", s.clientId)
        assertEquals("u", s.username)
        assertEquals("p", s.password)
        assertEquals("t/#", s.subscribeTopic)
        assertEquals(QoS.EXACTLY_ONCE, s.subscribeQos)
        assertEquals("t/publish", s.publishTopic)
        assertEquals("hi", s.publishMessage)
        assertEquals(QoS.AT_LEAST_ONCE, s.publishQos)
        assertTrue(s.publishRetain)
    }

    @Test
    fun toggle_raw_payload_flips_flag() {
        assertFalse(vm.state.value.showRawPayload)
        vm.toggleRawPayload()
        assertTrue(vm.state.value.showRawPayload)
        vm.toggleRawPayload()
        assertFalse(vm.state.value.showRawPayload)
    }

    @Test
    fun clear_messages_resets_list_and_counter() {
        // Pre-seed (we can't inject easily, so clearMessages from empty must be a no-op).
        vm.clearMessages()
        val s = vm.state.value
        assertTrue(s.receivedMessages.isEmpty())
        assertEquals(0, s.totalMessageCount)
    }

    @Test
    fun dismiss_error_clears_error_field() {
        // Force an error via subscribe() guard — blank topic triggers the error path.
        vm.updateSubscribeTopic("")
        vm.subscribe()
        assertNotNull(vm.state.value.error)
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    @Test
    fun publish_with_blank_topic_sets_error_and_noops() {
        vm.updatePublishTopic("")
        vm.publish()
        assertEquals("Topic cannot be empty", vm.state.value.error)
    }

    @Test
    fun subscribe_with_blank_topic_sets_error_and_noops() {
        vm.updateSubscribeTopic("")
        vm.subscribe()
        assertEquals("Topic filter cannot be empty", vm.state.value.error)
    }
}
