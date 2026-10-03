---
applyTo: "**/*.gradle.kts,gradle/**/*"
---

# Build & Gradle Rules

- Use Kotlin DSL (`build.gradle.kts`) exclusively.
- All dependency versions live in `gradle/libs.versions.toml` — never hardcode versions in build scripts.
- Prefer lazy Gradle configuration (`configureEach`, `withPlugin`, provider APIs).
- Avoid `afterEvaluate` unless there is no viable lazy alternative.
- **Default hierarchy template:** Call `applyDefaultHierarchyTemplate()` to let Kotlin auto-create `nativeMain`, `appleMain`, `iosMain`, `macosMain`, `linuxMain`, `mingwMain`, etc. Do NOT manually recreate source sets the template already provides.
- **Custom source sets:** `:transport-ws`'s `cioMain` is the one custom intermediate source set. It is created manually with `dependsOn()` wiring after calling `applyDefaultHierarchyTemplate()`.
- **Android target:** Use the `android {}` block of the Android Gradle KMP Library Plugin (`com.android.kotlin.multiplatform.library`). Configure `namespace`, `compileSdk`, and `minSdk` inside this block.
- Supported project targets: jvm, android, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, linuxArm64, mingwX64, wasmJs (`:transport-tcp` omits wasmJs).
- Always use `allTests` (not bare `test`) as the KMP test lifecycle task.
- Publishing: group = `org.meshtastic`, one artifact per module named `mqtt-client-<module>` by the `mqtt.publishing` convention plugin, published with the vanniktech `com.vanniktech.maven.publish` plugin. The root `kotlinMultiplatform` publication and per-target publications are auto-created.
