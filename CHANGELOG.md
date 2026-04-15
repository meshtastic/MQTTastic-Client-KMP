# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Project scaffold with Kotlin Multiplatform build targeting JVM, Android, iOS, macOS, Linux, Windows, and wasmJs
- `MqttMessage` data class with `ByteString` payload and MQTT 5.0 `PublishProperties`
- `MqttEndpoint` sealed interface for TCP and WebSocket connection endpoints
- `QoS` enum with all three MQTT quality of service levels
- `ConnectionState` enum for observable client state
- `PacketType` enum covering all 15 MQTT 5.0 packet types with fixed-header flag validation (§2.1.3)
- Variable Byte Integer encoder/decoder per MQTT 5.0 §1.5.5
- Input validation on all public types (port range, topic alias, subscription identifiers, etc.)
- Comprehensive test suite: VBI round-trips, packet type coverage, message equality, validation bounds
