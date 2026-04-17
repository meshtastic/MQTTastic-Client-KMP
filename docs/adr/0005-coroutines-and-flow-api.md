# 5. Coroutines-first public API (`suspend` + `Flow`)

## Status

Accepted.

## Context

Existing JVM MQTT libraries (Paho, HiveMQ, Vertx MQTT) expose callback-based
APIs — `onMessage`, `onConnectionLost`, listener registration, manual thread
management. They predate structured concurrency and bridge awkwardly into
Kotlin. Typical consumer code ends up wrapping every call in
`suspendCancellableCoroutine` and every listener in `callbackFlow`.

We are building for a Kotlin-first audience across nine platforms, including
browsers where there is no thread model at all.

## Decision

Every effectful operation on `MqttClient` is a `suspend` function; every
observable value is a `Flow` (cold) or `StateFlow` / `SharedFlow` (hot).

- `connect`, `disconnect`, `publish`, `subscribe`, `unsubscribe`, `auth` are
  all `suspend fun`.
- `connectionState: StateFlow<ConnectionState>` — readable snapshot + stream
  of changes.
- `messages: SharedFlow<MqttMessage>` — broker-to-client messages, with
  convenience extensions `messagesForTopic(topic)` and `messagesMatching(filter)`.
- Cancellation is honoured everywhere: cancelling the calling coroutine
  aborts in-flight ops and releases packet IDs.

No listeners, no callbacks, no manual threading.

## Consequences

**Positive**
- The public API is minimal and composable.
  `client.messages.filter { … }.map { … }.collect { … }` is a one-liner.
- Structured concurrency: when a parent scope is cancelled, everything the
  client is doing on its behalf is cancelled too.
- No bridging code in consumer applications.

**Negative**
- Consumers unfamiliar with coroutines face a learning curve. Mitigation:
  the sample app in `sample/` shows idiomatic patterns, and `docs/` will
  add a `migrating-from-paho.md` guide.
- Java interop is awkward without the `kotlinx-coroutines-jdk8` helpers,
  which we deliberately do not pull into `commonMain`. Java consumers must
  use the JVM artifact directly and supply those helpers themselves.
