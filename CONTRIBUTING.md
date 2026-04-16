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

## Architecture Rules

- **All protocol logic goes in `commonMain`** — no platform-specific code in the protocol layer
- **Never import `java.*`, `android.*`, or `platform.*` in `commonMain`**
- **Platform source sets contain only transport implementations** — `TcpTransport` + `WebSocketTransport` in `nonWebMain`, `WebSocketTransport` in `wasmJsMain`
- **Use `ByteString` (from kotlinx-io) for binary data** in public API surfaces — no raw `ByteArray` exposure
- **Internal by default** — only types listed in `AGENTS.md` are `public`

## Commit Conventions

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>
```

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

**Scopes:** `packet`, `transport`, `client`, `codec`, `qos`, `props`, `build`, `ci`, `deps`

**Examples:**
```
feat(packet): implement CONNECT packet encoder
fix(codec): handle partial VBI reads correctly
test(qos): add QoS 2 state machine round-trip tests
docs: update README installation instructions
```

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

## License

By contributing, you agree that your contributions will be licensed under the [GNU General Public License v3.0](LICENSE).
