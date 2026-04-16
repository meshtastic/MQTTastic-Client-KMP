# MQTTtastic Sample App

A Compose Multiplatform demo app showcasing the **MQTTtastic** MQTT 5.0 client library. Runs on Android, iOS, Desktop (JVM), and Web (wasmJs).

## Screenshots

> Screenshots coming soon.

## Prerequisites

- **JDK 17+**
- **An MQTT 5.0 broker** (e.g., [Mosquitto 2.x](https://mosquitto.org/))

### Quick Broker Setup

```bash
docker run -d --name mosquitto -p 1883:1883 -p 9001:9001 \
  eclipse-mosquitto:2 mosquitto -c /mosquitto-no-auth.conf
```

- **Port 1883** — TCP (Desktop, Android, iOS)
- **Port 9001** — WebSocket (browser / wasmJs target)

## Running the Sample

### Desktop (JVM)

```bash
./gradlew :sample:run
```

### Android

```bash
./gradlew :sample:installDebug
```

Or open the project in Android Studio and run the `sample` module.

### Web (wasmJs)

```bash
./gradlew :sample:wasmJsBrowserDevelopmentRun
```

> **Note:** The browser target connects via WebSocket. Change the broker URI to `ws://localhost:9001` in the app.

### iOS

Open `iosApp/iosApp.xcodeproj` in Xcode, or use:

```bash
./gradlew :sample:iosSimulatorArm64Test
```

> **Note:** Full iOS app requires an Xcode project wrapper. See the [Kotlin Multiplatform docs](https://kotlinlang.org/docs/multiplatform-mobile-create-first-app.html) for setup guidance.

## What It Demonstrates

| API | Description |
|-----|-------------|
| `MqttClient("id") { ... }` | Factory DSL with config builder |
| `will { ... }` | Nested DSL for Last Will and Testament |
| `defaultQos` / `defaultRetain` | Client-level publish defaults |
| `MqttLogger.println()` | Built-in console logging |
| `MqttEndpoint.parse(uri)` | URI-based endpoint parsing |
| `client.connect(endpoint)` / `client.close()` | Lifecycle management |
| `client.subscribe(topicFilter, qos)` | Topic subscriptions |
| `client.publish(topic, payload)` | Convenience publish |
| `client.connectionState` | Reactive `StateFlow` for connection status |
| `client.messages` | Reactive `SharedFlow` for incoming messages |
| `messagesForTopic(topic)` | Filtered message flow |
| `PublishProperties` | MQTT 5.0 publish properties (content type, user properties, etc.) |

## Project Structure

```
sample/
├── build.gradle.kts                  # KMP + Compose Multiplatform build config
├── README.md
└── src/
    ├── commonMain/kotlin/.../sample/
    │   ├── App.kt                    # Shared Compose UI
    │   └── MqttSampleViewModel.kt    # State management
    ├── androidMain/
    │   ├── kotlin/.../MainActivity.kt
    │   ├── AndroidManifest.xml
    │   └── res/values/themes.xml
    ├── desktopMain/kotlin/Main.kt    # Desktop entry point
    ├── wasmJsMain/
    │   ├── kotlin/Main.kt            # Browser entry point
    │   └── resources/index.html
    └── iosMain/kotlin/
        └── MainViewController.kt     # iOS entry point
```

## Broker Configuration Notes

- **Desktop / Android / iOS** — Use TCP: `tcp://localhost:1883` or `tcp://broker.example.com:1883`
- **Web (wasmJs)** — Must use WebSocket: `ws://localhost:9001` (browsers cannot open raw TCP sockets)
- **TLS** — Use `ssl://` for TCP+TLS or `wss://` for WebSocket+TLS
