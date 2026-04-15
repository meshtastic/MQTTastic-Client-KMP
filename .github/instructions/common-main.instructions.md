---
applyTo: "**/commonMain/**/*.kt"
---

# KMP commonMain Rules

- NEVER import `java.*`, `android.*`, `platform.*`, or any platform-specific API in `commonMain`.
- Use `kotlinx.coroutines.sync.Mutex` instead of `java.util.concurrent.locks.*`.
- Use `Mutex`-guarded counters instead of `java.util.concurrent.atomic.*` (atomicfu is blocked by Android plugin incompatibility).
- Use `kotlinx.io.bytestring.ByteString` for immutable byte sequences — no raw `ByteArray` in public API surfaces.
- Use Ktor's `ByteReadChannel`/`ByteWriteChannel` for I/O — no `java.io.*` or `java.nio.*`.
- ALL protocol logic lives here: packet types, encoder, decoder, client, connection, properties, reason codes.
- Platform transport implementations belong in `nonWebMain` or `wasmJsMain`, not here.
- Check `gradle/libs.versions.toml` before adding any dependency.
- Every public type must have KDoc. Internal types should have a brief doc comment explaining purpose.
