#!/usr/bin/env bash
# Cut and read CHANGELOG.md sections without re-rendering the file.
#
#   scripts/changelog.sh cut <version> [<date>]   move [Unreleased] under a new "## [<version>] - <date>" heading
#   scripts/changelog.sh notes <version>          print that section's body verbatim (the release notes)
#
# Every other line of the file is left byte-for-byte as it was. CHANGELOG defaults to the
# CHANGELOG.md beside this script's parent directory.
set -euo pipefail

die() {
  echo "changelog.sh: $*" >&2
  exit 1
}

usage() {
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

file="${CHANGELOG:-$(cd "$(dirname "$0")/.." && pwd)/CHANGELOG.md}"
[ -f "$file" ] || die "no changelog at $file"

[ $# -ge 2 ] || usage
cmd="$1"
version="${2#v}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || die "'$2' is not a SemVer version"

# Body of the section headed "## [<version>]", up to the next "## " heading or the link footer,
# with leading and trailing blank lines dropped.
section() {
  awk -v want="$1" '
    function starts(s, p) { return substr(s, 1, length(p)) == p }
    starts($0, "## ") {
      if (inside) exit
      h = "## [" want "]"
      if ($0 == h || starts($0, h " ")) { inside = 1; found = 1; next }
    }
    inside && /^\[[^]]+\]: / { exit }
    inside { lines[++n] = $0 }
    END {
      if (!found) exit 3
      first = 1; while (first <= n && lines[first] ~ /^[[:space:]]*$/) first++
      last = n;  while (last >= first && lines[last] ~ /^[[:space:]]*$/) last--
      for (i = first; i <= last; i++) print lines[i]
    }
  ' "$file"
}

case "$cmd" in
  notes)
    body="$(section "$version")" || die "no '## [$version]' section in $file"
    [ -n "$body" ] || die "the '## [$version]' section in $file is empty"
    printf '%s\n' "$body"
    ;;

  cut)
    date="${3:-$(date -u +%Y-%m-%d)}"
    grep -qxF '## [Unreleased]' "$file" || die "no '## [Unreleased]' heading in $file"
    if section "$version" >/dev/null 2>&1; then
      die "'## [$version]' already exists in $file"
    fi
    # A section holding only "### Added"-style headings has nothing to release.
    unreleased="$(section Unreleased)"
    grep -v -e '^[[:space:]]*$' -e '^#' <<<"$unreleased" >/dev/null ||
      die "[Unreleased] in $file has no entries to release"

    tmp="$(mktemp "${file}.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    awk -v v="$version" -v d="$date" '
      function starts(s, p) { return substr(s, 1, length(p)) == p }
      # The newest released version, for the compare link.
      starts($0, "## [") && $0 != "## [Unreleased]" && prev == "" {
        prev = substr($0, 5); sub(/\].*/, "", prev)
      }
      # Repository base from any existing GitHub link in the footer.
      /^\[[^]]+\]: https:\/\/github\.com\// && base == "" {
        base = $0; sub(/^\[[^]]+\]: /, "", base)
        n = split(base, p, "/"); base = p[1] "//" p[3] "/" p[4] "/" p[5]
      }
      { lines[++count] = $0 }
      END {
        if (prev == "") link = base "/releases/tag/v" v
        else link = base "/compare/v" prev "...v" v
        added = 0
        for (i = 1; i <= count; i++) {
          line = lines[i]
          if (line == "## [Unreleased]") {
            print line; print ""; print "## [" v "] - " d
            continue
          }
          if (base != "" && starts(line, "[Unreleased]: ")) {
            print "[Unreleased]: " base "/compare/v" v "...HEAD"
            print "[" v "]: " link
            added = 1
            continue
          }
          if (base != "" && !added && line ~ /^\[[^]]+\]: /) {
            print "[" v "]: " link
            added = 1
          }
          print line
        }
      }
    ' "$file" >"$tmp"
    mv "$tmp" "$file"
    trap - EXIT
    echo "cut [Unreleased] into [$version] - $date in $file"
    ;;

  *)
    usage
    ;;
esac
