# Contributing to MQTTastic Client KMP

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
./gradlew spotlessApply detektAll allTests apiCheck koverVerify
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

## Changelog

[`CHANGELOG.md`](CHANGELOG.md) is hand-written in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form, and nothing generates an entry from a commit. `scripts/changelog.sh` cuts and reads sections without re-rendering the file, so prose under any heading survives a release.

Add an entry under `## [Unreleased]` for anything a consumer would notice — a new or changed public API, a behaviour change (including which Ktor engine a transport selects, or how a connection reacts to a broker), a fix to something they could have hit, a security property. Refactors, test-only changes, CI work and sample-only changes need none; `:sample` publishes nothing.

**A change that moves any `api/*.api` or `api/*.klib.api` dump always needs an entry**, and `core`, `transport-tcp` and `transport-ws` each carry a JVM dump and a klib dump. The entry goes under `### Breaking` if a consumer has to change code rather than just recompile. Add only the `###` headings your entries need, in the order `Breaking`, `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`. `Breaking` comes first because, with committed ABI dumps, the first thing a consumer needs to know is whether recompiling is enough.

The changelog is also what the GitHub Release page says. `release.yml` puts `scripts/changelog.sh notes X.Y.Z` in the release body, and GitHub's own `generate_release_notes` is off, so a release is described once.

## Releasing

See [RELEASING.md](RELEASING.md).

## License

By contributing, you agree that your contributions will be licensed under the [GNU General Public License v3.0](LICENSE).
