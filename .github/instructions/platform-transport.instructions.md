---
applyTo: "**/nonWebMain/**/*.kt,**/wasmJsMain/**/*.kt,**/jvmMain/**/*.kt,**/androidMain/**/*.kt,**/iosMain/**/*.kt,**/macosMain/**/*.kt,**/linuxMain/**/*.kt,**/mingwMain/**/*.kt"
---

# Platform Transport Rules

- Platform source sets contain ONLY `MqttTransport` implementations and minimal platform primitives required to realize the transport boundary. No protocol logic.
- If you find protocol logic here, move it to `commonMain`.
- **`nonWebMain` is a custom intermediate source set** — it is NOT part of Kotlin's default hierarchy template. It must be created manually in `build.gradle.kts` with explicit `dependsOn()` wiring after calling `applyDefaultHierarchyTemplate()`.
- `MqttTransport` is an internal interface in `commonMain`. Concrete implementations live in `nonWebMain` (TcpTransport + WebSocketTransport) and `wasmJsMain` (WebSocketTransport). Transport selection is done via the `createPlatformTransport(endpoint)` expect/actual factory — it routes by endpoint type.
- **TcpTransport** (nonWebMain): Uses `io.ktor:ktor-network` + `io.ktor:ktor-network-tls`.
  - `receive()` must parse the fixed header byte, decode variable-length remaining length, then read exactly that many bytes. Handle partial reads.
  - This is the trickiest code in the project — test TCP framing edge cases thoroughly.
- **WebSocketTransport** (nonWebMain + wasmJsMain): Uses `io.ktor:ktor-client-websockets` with platform-specific engines (CIO on JVM/Android/Apple/Linux, WinHttp on Windows, Js on browser).
  - One binary WebSocket frame = one MQTT packet. The WS layer handles framing.
  - `receive()` reads one frame and returns the complete MQTT packet bytes.
  - The nonWebMain and wasmJsMain each have their own `WebSocketTransport` using `HttpClient()` auto-detect for the engine.
- `MqttTransport.connect()` takes an `MqttEndpoint` sealed class — `MqttEndpoint.Tcp(host, port, tls)` for TCP or `MqttEndpoint.WebSocket(url, protocols)` for WebSocket.
