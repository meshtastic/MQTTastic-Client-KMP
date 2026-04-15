# MQTTtastic Client KMP — Consumer ProGuard/R8 Rules
# These rules are automatically included when the library is consumed via AAR.

# Keep all public API types (classes, interfaces, enums)
-keep class org.meshtastic.mqtt.MqttClient { *; }
-keep class org.meshtastic.mqtt.MqttConfig { *; }
-keep class org.meshtastic.mqtt.MqttConfig$Builder { *; }
-keep class org.meshtastic.mqtt.MqttMessage { *; }
-keep class org.meshtastic.mqtt.PublishProperties { *; }
-keep class org.meshtastic.mqtt.WillConfig { *; }
-keep class org.meshtastic.mqtt.MqttConnectionException { *; }

# Sealed interface hierarchies
-keep class org.meshtastic.mqtt.MqttEndpoint { *; }
-keep class org.meshtastic.mqtt.MqttEndpoint$Tcp { *; }
-keep class org.meshtastic.mqtt.MqttEndpoint$WebSocket { *; }

# Enums
-keepclassmembers enum org.meshtastic.mqtt.QoS { *; }
-keepclassmembers enum org.meshtastic.mqtt.ConnectionState { *; }
-keepclassmembers enum org.meshtastic.mqtt.MqttLogLevel { *; }

# Logger interface (consumers implement this)
-keep interface org.meshtastic.mqtt.MqttLogger { *; }

# Reason codes and packet properties (public re-exports)
-keepclassmembers enum org.meshtastic.mqtt.packet.ReasonCode { *; }
-keepclassmembers enum org.meshtastic.mqtt.packet.RetainHandling { *; }

# Keep Kotlin metadata for proper interop
-keep class kotlin.Metadata { *; }
