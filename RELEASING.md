# Releasing

MQTTastic publishes `org.meshtastic:mqtt-client-core`, `mqtt-client-transport-tcp`,
`mqtt-client-transport-ws` and `mqtt-client-bom` to Maven Central with the vanniktech
maven-publish plugin, from `.github/workflows/release.yml`.

## Version

The `vX.Y.Z` tag is the version. `settings.gradle.kts` runs
`git describe --tags --match 'v*' --exclude 'v*-*'`: on an exact tag the version is
`X.Y.Z`, between tags it is the next patch with `-SNAPSHOT`. `-PVERSION_NAME=...`
overrides it. There is no version file, and the release workflow creates the tag.

## Secrets

`SIGNING_KEY_ID`, `SIGNING_KEY` and `SIGNING_PASSWORD` (the in-memory GPG key), and
`OSSRH_USERNAME` and `OSSRH_PASSWORD` (Central Portal credentials), passed as the
vanniktech `ORG_GRADLE_PROJECT_*` properties.

## Cutting a release

1. Pick `X.Y.Z` (SemVer; before 1.0 a minor may break).
2. On a branch, run `scripts/changelog.sh cut X.Y.Z`. That moves `## [Unreleased]`
   under a dated `## [X.Y.Z]` heading and updates the compare links, touching nothing
   else. It refuses an empty Unreleased.
3. If the public API changed, `./gradlew apiDump` and commit `api/`.
4. Commit `chore(release): X.Y.Z`, open the PR and merge it.
5. `gh workflow run release.yml --repo meshtastic/MQTTastic-Client-KMP -f version=X.Y.Z`.
   Add `-f dry_run=true` to run every gate without tagging or publishing; a dry run may
   start from any branch. Pushing a `vX.Y.Z` tag on `main` runs the same workflow.

## What the workflow checks, in order

1. The commit is on `main` (skipped for a dry run).
2. `X.Y.Z` is a plain `MAJOR.MINOR.PATCH`, and any existing `vX.Y.Z` tag points at this
   commit.
3. `scripts/changelog.sh notes X.Y.Z` finds a non-empty section. It becomes the GitHub
   Release body verbatim.
4. Every check the `main` ruleset requires passed on this commit
   (`scripts/release-checks.sh green-ci`). That is where the Apple and Windows tests
   ran; this Linux runner cannot run them.
5. It tags `vX.Y.Z` locally and confirms `git describe` resolves the commit to it.
6. `spotlessCheck detektAll jvmTest apiCheck`, then `publishToMavenLocal` with signing.
7. No staged POM or Gradle module depends on a `-SNAPSHOT`
   (`scripts/release-checks.sh no-snapshots`). Central rejects that only after upload.
8. If `X.Y.Z` is already on `repo1.maven.org` the publish is skipped, so a re-run is
   safe.

Then it attests every staged artifact, pushes the annotated `vX.Y.Z` tag if origin lacks
it, runs `publishAndReleaseToMavenCentral`, and creates or updates the GitHub Release.
Two jobs follow it: one builds the sample apps (`.apk`, `.deb`, `.dmg`, `.msi`, wasmJs
web zip) on a per-OS matrix, and one generates CycloneDX and SPDX SBOMs. Both attach
their output to the Release and attest it.

## After releasing

`repo1.maven.org` lags the Central Portal by 10 to 30 minutes. Downstream bumps wait
until `https://repo1.maven.org/maven2/org/meshtastic/mqtt-client-core/X.Y.Z/` resolves.
