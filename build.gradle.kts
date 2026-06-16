import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.bcv) apply false
    // Applied at the root so docs and coverage can aggregate across the published modules.
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
}

// ---------------------------------------------------------------------------
// Version: derived from the latest git tag (e.g. v0.3.6 → 0.3.6).
// Between tags: next patch + "-SNAPSHOT" (e.g. v0.3.6-4-gabcdef → 0.3.7-SNAPSHOT).
// Overridable via -PVERSION_NAME=... (used by CI snapshot publishing).
// ---------------------------------------------------------------------------
val gitVersion: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v*")
    isIgnoreExitValue = true
}.standardOutput.asText.map { raw ->
    val desc = raw.trim()
    if (desc.isBlank()) {
        return@map providers.gradleProperty("VERSION_NAME").getOrElse("0.0.0-SNAPSHOT")
    }
    val stripped = desc.removePrefix("v")
    if ('-' in stripped) {
        // Not on an exact tag — compute next-patch SNAPSHOT
        val base = stripped.substringBefore('-')
        val parts = base.split('.').map(String::toInt)
        "${parts[0]}.${parts[1]}.${parts[2] + 1}-SNAPSHOT"
    } else {
        stripped
    }
}

val resolvedVersion: String =
    providers.gradleProperty("VERSION_NAME")
        .orElse(gitVersion)
        .get()

allprojects {
    version = resolvedVersion
}

// ---------------------------------------------------------------------------
// Aggregate API docs (Dokka) and coverage (Kover) across the published library
// modules. The ≥80% coverage gate is enforced on the merged report so that
// thinly-unit-tested transport modules don't each need to clear the bar alone.
// ---------------------------------------------------------------------------
val publishedLibraryModules = listOf(":core", ":transport-tcp", ":transport-ws")

dependencies {
    publishedLibraryModules.forEach {
        dokka(project(it))
        kover(project(it))
    }
}

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Security: pin the transitive npm `ws` dependency to a patched version.
// The wasmJs target's JS dev/test toolchain (webpack-dev-server / karma) pulls
// in `ws`, which Kotlin otherwise resolves to a version vulnerable to
// uninitialized-memory disclosure (GHSA-58qx-3vcg-4xpx; fixed in 8.20.1).
// The wasmJs target uses its own Yarn store (kotlin-js-store/wasm/yarn.lock),
// so the override targets the Wasm Yarn plugin/extension — not the JS one.
// After changing this pin, regenerate the lockfile with:
//   ./gradlew kotlinWasmUpgradeYarnLock
// ---------------------------------------------------------------------------
plugins.withType<WasmYarnPlugin> {
    the<WasmYarnRootExtension>().resolution("ws", "8.20.1")
}
