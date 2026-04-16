# Contributing to MQTTtastic Client KMP

Thank you for your interest in contributing! This guide will help you get started.

## Prerequisites

- **JDK 21** — required to build all targets
- **Xcode** (macOS only) — required for iOS/macOS targets
- **Chrome** — required for wasmJs browser tests

## Building

See the **Build & Test Commands** section in [AGENTS.md](AGENTS.md) for the full command reference.

**Quick start:**

```bash
./gradlew build              # Full build (compile + test + check) for all targets
./gradlew allTests           # Run all KMP tests (always use this, not bare `test`)
./gradlew jvmTest            # JVM tests only
```

> **Important:** Always use `allTests` instead of `test` — it's the KMP lifecycle task that covers all source sets.

**Before submitting a PR, ensure all checks pass:**

```bash
./gradlew spotlessApply detekt allTests apiCheck koverVerify
```

## Code Style

This project uses [ktlint](https://pinterest.github.io/ktlint/) (via [Spotless](https://github.com/diffplug/spotless)) and [detekt](https://detekt.dev/) for formatting and static analysis, plus [BCV](https://github.com/Kotlin/binary-compatibility-validator) for API compatibility tracking and [Kover](https://github.com/Kotlin/kotlinx-kover) for code coverage.

## Architecture & Commit Conventions

See [AGENTS.md](AGENTS.md) for the full architecture rules, commit format, and scope definitions.

## Pull Requests

1. **Fork** the repository and create a feature branch from `main`
2. **Keep commits focused** — one logical change per commit
3. **Write tests** — every new packet type, property, or protocol feature needs encode/decode round-trip tests
4. **Update documentation** — if your change affects the public API or architecture, update `AGENTS.md`
5. **Ensure CI passes** — formatting, linting, and all tests must be green

## Reporting Issues

When filing an issue, please include:
- Library version and Kotlin version
- Target platform(s) affected
- Minimal reproduction steps or code snippet
- Expected vs actual behavior

## Releasing

The version is tracked in **one place only**: `VERSION_NAME` in `gradle.properties`. The library coordinate (`GROUP:POM_ARTIFACT_ID:VERSION_NAME`) and the sample Android app's `versionCode`/`versionName` are both derived from it — keep them in sync by editing this single property.

To cut a release:

1. Bump `VERSION_NAME` in `gradle.properties` (drop the `-SNAPSHOT` suffix if any).
2. Move the `## [Unreleased]` entries in `CHANGELOG.md` under a new `## [x.y.z] - YYYY-MM-DD` heading.
3. Commit with `chore(release): x.y.z` and tag: `git tag vx.y.z && git push --follow-tags`.
4. The `Release` workflow will publish to Maven Central, create a GitHub Release, and attach sample artifacts (`.apk`, `.deb`, `.dmg`, `.msi`, wasmJs web zip) built across a per-OS matrix.

## License

By contributing, you agree that your contributions will be licensed under the [GNU General Public License v3.0](LICENSE).
