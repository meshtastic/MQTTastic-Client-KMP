---
applyTo: "**/nonWebMain/**/*.kt,**/wasmJsMain/**/*.kt,**/jvmMain/**/*.kt,**/androidMain/**/*.kt,**/iosMain/**/*.kt,**/macosMain/**/*.kt,**/linuxMain/**/*.kt,**/mingwMain/**/*.kt"
---

# Platform Transport Rules

- Platform source sets contain ONLY `MqttTransport` implementations and minimal platform primitives required to realize the transport boundary. No protocol logic.
- If you find protocol logic here, move it to `commonMain`.
- **`nonWebMain` is a custom intermediate source set** — it is NOT part of Kotlin's default hierarchy template. It must be created manually in `build.gradle.kts` with explicit `dependsOn()` wiring after calling `applyDefaultHierarchyTemplate()`.
- `actual` declarations for the TCP transport live in `nonWebMain`, shared across all non-browser targets. Do NOT duplicate them into individual platform source sets (jvmMain, androidMain, nativeMain, etc.).
- **TcpTransport** (nonWebMain): Uses `io.ktor:ktor-network` + `io.ktor:ktor-network-tls`.
  - `receive()` must parse the fixed header byte, decode variable-length remaining length, then read exactly that many bytes. Handle partial reads.
  - This is the trickiest code in the project — test TCP framing edge cases thoroughly.
- **WebSocketTransport** (wasmJsMain): Uses `io.ktor:ktor-client-websockets`.
  - One binary WebSocket frame = one MQTT packet. The WS layer handles framing.
  - `receive()` reads one frame and returns the complete MQTT packet bytes.
- Prefer interfaces + DI over `expect`/`actual` where possible. Use `expect`/`actual` only for the transport primitive.
