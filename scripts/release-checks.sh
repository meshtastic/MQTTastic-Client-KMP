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
  # checks[i] is a required context and apps[i] the app the ruleset pins it to (empty: any).
  # No mapfile: macOS runners ship Bash 3.2.
  local -a checks=() apps=()
  local name app
  if [ $# -gt 0 ]; then
    for name in "$@"; do
      checks+=("$name")
      apps+=("")
    done
  else
    while IFS=$'\t' read -r name app; do
      [ -n "$name" ] || continue
      checks+=("$name")
      apps+=("$app")
    done < <(gh api "repos/$repo/rules/branches/main" \
      --jq '.[] | select(.type == "required_status_checks") | .parameters.required_status_checks[] | "\(.context)\t\(.integration_id // "")"')
  fi
  [ ${#checks[@]} -gt 0 ] || die "no required checks on main and none named; refusing to release unchecked"

  local deadline=$((SECONDS + ${GREEN_CI_TIMEOUT:-2700}))
  local i check conclusion
  local -a filter
  for ((i = 0; i < ${#checks[@]}; i++)); do
    check="${checks[$i]}"
    app="${apps[$i]}"
    filter=(-f check_name="$check")
    # Only the app the ruleset names may satisfy the check, as for the ruleset itself.
    [ -z "$app" ] || filter+=(-f app_id="$app")
    while :; do
      # check_name filters server-side; the unfiltered list is paginated and can hide the run.
      # A re-run adds a run under the same name, so the newest one decides.
      if ! conclusion="$(gh api -X GET "repos/$repo/commits/$sha/check-runs" "${filter[@]}" \
        --jq '.check_runs | sort_by(.started_at) | last | if . == null then "" else (.conclusion // "pending") end')"; then
        echo "::warning::check-runs query failed for '$check'; retrying"
        conclusion="pending"
      fi
      # A context with no pinned app may be a commit status rather than a check run.
      if [ -z "$conclusion" ] && [ -z "$app" ] && ! conclusion="$(gh api "repos/$repo/commits/$sha/status" |
        jq -r --arg c "$check" '[.statuses[] | select(.context == $c) | .state] | first // ""')"; then
        echo "::warning::status query failed for '$check'; retrying"
        conclusion="pending"
      fi
      # skipped and neutral pass, as they do for the ruleset itself.
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
