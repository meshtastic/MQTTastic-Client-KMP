#!/usr/bin/env bash
# Release gates shared by the release workflow and a maintainer's shell.
#
#   scripts/release-checks.sh green-ci <sha> [<check name>...]
#       Wait for every named check run on <sha> and fail unless each one passed. With no names,
#       the checks the `main` ruleset requires are used. Needs GH_TOKEN and GITHUB_REPOSITORY.
#   scripts/release-checks.sh no-snapshots <version>
#       Fail if any POM or Gradle module staged in ~/.m2 for <version> depends on a -SNAPSHOT.
#       Maven Central rejects such a release only after the tag is already out.
set -euo pipefail

die() {
  echo "::error::$*" >&2
  exit 1
}

usage() {
  sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

green_ci() {
  local sha="$1"
  shift
  local repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"
  # No mapfile: macOS runners ship Bash 3.2.
  local -a checks=()
  local name
  if [ $# -gt 0 ]; then
    checks=("$@")
  else
    while IFS= read -r name; do
      [ -n "$name" ] && checks+=("$name")
    done < <(gh api "repos/$repo/rules/branches/main" \
      --jq '.[] | select(.type == "required_status_checks") | .parameters.required_status_checks[].context')
  fi
  [ ${#checks[@]} -gt 0 ] || die "no required checks on main and none named; refusing to release unchecked"

  local deadline=$((SECONDS + ${GREEN_CI_TIMEOUT:-2700}))
  local check conclusion
  for check in "${checks[@]}"; do
    while :; do
      # check_name filters server-side; the unfiltered list is paginated and can hide the run.
      if ! conclusion="$(gh api -X GET "repos/$repo/commits/$sha/check-runs" \
        -f check_name="$check" --jq '[.check_runs[].conclusion] | if length == 0 then "" else (map(. // "pending") | first) end')"; then
        echo "::warning::check-runs query failed for '$check'; retrying"
        conclusion=""
      fi
      case "$conclusion" in
        success | skipped | neutral)
          echo "ok   $check ($conclusion)"
          break
          ;;
        "" | pending) ;;
        *) die "'$check' concluded '$conclusion' on $sha" ;;
      esac
      [ $SECONDS -lt $deadline ] || die "timed out waiting for '$check' on $sha"
      echo "wait $check"
      sleep 30
    done
  done
}

no_snapshots() {
  local version="$1"
  local root="${MAVEN_LOCAL:-$HOME/.m2/repository}/org/meshtastic"
  local -a staged=()
  local path
  while IFS= read -r path; do
    staged+=("$path")
  done < <(find "$root" -path "*/$version/*" \( -name '*.pom' -o -name '*.module' \) 2>/dev/null)
  [ ${#staged[@]} -gt 0 ] || die "nothing staged for $version under $root; run publishToMavenLocal first"
  local hits
  hits="$(grep -H -- '-SNAPSHOT' "${staged[@]}" || true)"
  [ -z "$hits" ] || die "$version depends on a -SNAPSHOT:"$'\n'"$hits"
  echo "ok   ${#staged[@]} staged POM/module files for $version, no -SNAPSHOT dependency"
}

[ $# -ge 2 ] || usage
cmd="$1"
shift
case "$cmd" in
  green-ci) green_ci "$@" ;;
  no-snapshots) no_snapshots "${1#v}" ;;
  *) usage ;;
esac
