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
 * DSL marker for MQTTtastic builder scopes.
 *
 * Prevents accidental use of outer-scope DSL functions from within nested
 * builder lambdas, following the same pattern as Ktor's `@KtorDsl`.
 *
 * Applied to [MqttConfig.Builder] and [WillConfig.Builder] to enforce
 * correct scoping in nested configuration blocks.
 */
@DslMarker
@Target(AnnotationTarget.CLASS)
public annotation class MqttDsl
