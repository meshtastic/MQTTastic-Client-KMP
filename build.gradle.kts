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
    // Manages CHANGELOG.md, which stays hand-written: it parses and renders the
    // file and never generates an entry from a commit. `patchChangelog` is the
    // release step; `getChangelog` is what release.yml reads for the Release body.
    alias(libs.plugins.changelog)
}

// ---------------------------------------------------------------------------
// CHANGELOG.md. The version this resolves to matters more here than elsewhere,
// because this repo has no VERSION file: `project.version` comes from git
// describe (see settings.gradle.kts) and reads `<next-patch>-SNAPSHOT` between
// tags. Stripping the suffix gives the version a patch release would carry — the
// common case — and `-PVERSION_NAME=x.y.z` overrides it for a minor or major,
// exactly as it overrides the published coordinate. Read eagerly: beforeProject
// has already assigned `version` by the time this script evaluates, and a lazy
// provider closing over `project` is not configuration-cache safe.
// ---------------------------------------------------------------------------
changelog {
    version = providers.gradleProperty("VERSION_NAME")
        .getOrElse(project.version.toString().removeSuffix("-SNAPSHOT"))
    repositoryUrl = "https://github.com/meshtastic/MQTTastic-Client-KMP"
    // Breaking leads: the library carries committed ABI dumps, so what a consumer
    // needs first is whether recompiling is enough. Keep a Changelog's own set has
    // no word for it.
    groups = listOf("Breaking", "Added", "Changed", "Deprecated", "Removed", "Fixed", "Security")
    // `patchChangelog` rewrites everything between the title and the first section
    // from this value, so anything that must survive a release lives here.
    introduction =
        """
        All notable changes to this project will be documented in this file.

        The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
        """.trimIndent()
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
// in `ws`, which Kotlin otherwise resolves to a vulnerable version:
//   - uninitialized-memory disclosure (GHSA-58qx-3vcg-4xpx; fixed in 8.20.1)
//   - memory-exhaustion DoS via tiny fragments   (fixed in 8.21.0)
// The wasmJs target uses its own Yarn store (kotlin-js-store/wasm/yarn.lock),
// so the override targets the Wasm Yarn plugin/extension — not the JS one.
// After changing this pin, regenerate the lockfile with:
//   ./gradlew kotlinWasmUpgradeYarnLock
// ---------------------------------------------------------------------------
plugins.withType<WasmYarnPlugin> {
    the<WasmYarnRootExtension>().resolution("ws", "8.21.0")
}
