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

# Shared awk: read the file into lines[1..count] and set footer to the first line of the
# trailing block of "[x]: url" link definitions (count + 1 when there is none). A link
# definition inside a section is prose, not footer.
# shellcheck disable=SC2016 # an awk program, expanded by awk
read_file='
  function starts(s, p) { return substr(s, 1, length(p)) == p }
  function islink(s) { return s ~ /^\[[^]]+\]: / }
  function isblank(s) { return s ~ /^[[:space:]]*$/ }
  { lines[++count] = $0 }
  function find_footer(   i) {
    footer = count + 1
    for (i = count; i >= 1 && (isblank(lines[i]) || islink(lines[i])); i--)
      if (islink(lines[i])) footer = i
  }
'

# Body of the section headed "## [<version>]", up to the next "## " heading or the link footer,
# with leading and trailing blank lines dropped.
section() {
  awk -v want="$1" "$read_file"'
    END {
      find_footer()
      h = "## [" want "]"
      for (i = 1; i < footer; i++) {
        if (inside && starts(lines[i], "## ")) break
        if (inside) body[++n] = lines[i]
        else if (lines[i] == h || starts(lines[i], h " ")) { inside = 1; found = 1 }
      }
      if (!found) exit 3
      first = 1; while (first <= n && isblank(body[first])) first++
      last = n;  while (last >= first && isblank(body[last])) last--
      for (i = first; i <= last; i++) print body[i]
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
    awk -v v="$version" -v d="$date" "$read_file"'
      END {
        find_footer()
        for (i = 1; i < footer; i++) {
          # The newest released version, for the compare link.
          if (prev == "" && starts(lines[i], "## [") && lines[i] != "## [Unreleased]") {
            prev = substr(lines[i], 5); sub(/\].*/, "", prev)
          }
        }
        for (i = footer; i <= count && base == ""; i++) {
          # Repository base from an existing GitHub link in the footer.
          if (lines[i] ~ /^\[[^]]+\]: https:\/\/github\.com\//) {
            base = lines[i]; sub(/^\[[^]]+\]: /, "", base)
            split(base, p, "/"); base = p[1] "//" p[3] "/" p[4] "/" p[5]
          }
        }
        if (prev == "") link = base "/releases/tag/v" v
        else link = base "/compare/v" prev "...v" v
        added = 0
        for (i = 1; i <= count; i++) {
          line = lines[i]
          if (i < footer && line == "## [Unreleased]") {
            print line; print ""; print "## [" v "] - " d
            continue
          }
          if (i >= footer && base != "" && starts(line, "[Unreleased]: ")) {
            print "[Unreleased]: " base "/compare/v" v "...HEAD"
            print "[" v "]: " link
            added = 1
            continue
          }
          if (i >= footer && base != "" && !added && islink(line)) {
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
