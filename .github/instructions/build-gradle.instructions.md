---
applyTo: "**/*.gradle.kts,gradle/**/*"
---

# Build & Gradle Rules

- Use Kotlin DSL (`build.gradle.kts`) exclusively.
- All dependency versions live in `gradle/libs.versions.toml` — never hardcode versions in build scripts.
- Prefer lazy Gradle configuration (`configureEach`, `withPlugin`, provider APIs).
- Avoid `afterEvaluate` unless there is no viable lazy alternative.
- **Default hierarchy template:** Call `applyDefaultHierarchyTemplate()` to let Kotlin auto-create `nativeMain`, `appleMain`, `iosMain`, `macosMain`, `linuxMain`, `mingwMain`, etc. Do NOT manually recreate source sets the template already provides.
- **Custom source sets:** `nonWebMain` is a custom intermediate source set — it must be created manually with `dependsOn()` wiring after calling `applyDefaultHierarchyTemplate()`.
- **Android target:** Use the `androidLibrary {}` block with the Android Gradle Library Plugin. Configure `namespace`, `compileSdk`, and `minSdk` inside this block.
- Supported project targets: jvm, android, iosArm64, iosSimulatorArm64, macosArm64, macosX64, linuxX64, linuxArm64, mingwX64, wasmJs.
- Always use `allTests` (not bare `test`) as the KMP test lifecycle task.
- Publishing: group = `org.meshtastic`, artifact = `mqtt-client`. Use `maven-publish` plugin. The root `kotlinMultiplatform` publication and per-target publications are auto-created.
