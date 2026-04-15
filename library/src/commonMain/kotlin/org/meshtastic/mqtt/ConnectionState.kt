package org.meshtastic.mqtt

/**
 * Observable connection states for [MqttClient].
 */
public enum class ConnectionState {
    /** Connection attempt in progress. */
    CONNECTING,

    /** Successfully connected to the broker. */
    CONNECTED,

    /** Disconnected from the broker. */
    DISCONNECTED,

    /** Reconnection attempt in progress after a connection loss. */
    RECONNECTING,
}
