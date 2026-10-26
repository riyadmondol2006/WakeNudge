#!/usr/bin/env bash
# Lays out the release: copies the verified build outputs into one folder under their public names, writes
# SHA256SUMS.txt, and writes the release notes.
#
#   WakeNudge-<version>.apk   the signed APK
#   WakeNudge.apk             the same APK under a fixed name (the "latest download" link)
#   mapping-<version>.txt.gz  R8 mapping (gzipped: the raw file is ~77 MB), to de-obfuscate crash reports
#   SHA256SUMS.txt
#
# Usage: scripts/stage-release.sh --version-name 1.0.57 --cert-sha256 AA:BB:.. --apk F --mapping F --out DIR \
#            --notes-file F
# Environment: GITHUB_REPOSITORY (links in the notes), GITHUB_SHA (defaults to HEAD).
# The notes list the commits since the previous v* tag. When .github/release.yml exists the publish step also asks
# GitHub for its categorised notes, which already end in a "Full Changelog" link, so this script then leaves that out.
set -euo pipefail

version_name="" cert_sha256="" apk="" mapping="" out="" notes_file=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version-name) version_name=${2:?}; shift 2 ;;
    --cert-sha256) cert_sha256=${2:?}; shift 2 ;;
    --apk) apk=${2:?}; shift 2 ;;
    --mapping) mapping=${2:?}; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    --notes-file) notes_file=${2:?}; shift 2 ;;
    *) echo "usage: $0 --version-name V --cert-sha256 FP --apk F --mapping F --out DIR --notes-file F" >&2; exit 2 ;;
  esac
done
for required in version_name cert_sha256 apk mapping out notes_file; do
  [[ -n ${!required} ]] || { echo "stage-release: --${required//_/-} is required" >&2; exit 2; }
done
for f in "$apk" "$mapping"; do [[ -f $f ]] || { echo "stage-release: $f does not exist" >&2; exit 1; }; done

cd "$(dirname "${BASH_SOURCE[0]}")/.."
repo=${GITHUB_REPOSITORY:-riyadmondol2006/WakeNudge}
sha=${GITHUB_SHA:-$(git rev-parse HEAD)}
tag=v$version_name

rm -rf "$out"
mkdir -p "$out"
cp "$apk" "$out/WakeNudge-$version_name.apk"
# The same file under a name that never changes, so that
#   https://github.com/$repo/releases/latest/download/WakeNudge.apk
# always downloads the newest APK (the website's Download button points there).
cp "$apk" "$out/WakeNudge.apk"
gzip -9 -n -c "$mapping" > "$out/mapping-$version_name.txt.gz"
(cd "$out" && files=(*) && sha256sum -- "${files[@]}" > SHA256SUMS.txt)

# --- notes -------------------------------------------------------------------------------------------------------
# The newest earlier release tag. Starting from the parent keeps a re-run of this very commit from finding its own tag.
previous_tag=$(git describe --tags --abbrev=0 --match 'v[0-9]*' "$sha^" 2> /dev/null || true)
range=$sha
[[ -z $previous_tag ]] || range=$previous_tag..$sha
mapfile -t commits < <(git log --no-merges --pretty='format:- %s (%h)' "$range")
max_lines=60

{
  echo "## Install"
  echo
  echo "- **Android phone:** download \`WakeNudge-$version_name.apk\`, open it and allow installing from your browser or file manager when Android asks. An installed WakeNudge offers each new release itself (About, Updates)."
  echo "- **Always the newest APK:** \`WakeNudge.apk\` is the very same file under a fixed name, so [this link](https://github.com/$repo/releases/latest/download/WakeNudge.apk) always downloads the latest release."
  echo
  echo "## What changed"
  echo
  if [[ ${#commits[@]} -eq 0 ]]; then
    echo "No source changes since ${previous_tag:-the start of the project}."
  else
    [[ -z $previous_tag ]] && echo "First release." && echo
    printf '%s\n' "${commits[@]:0:max_lines}"
    [[ ${#commits[@]} -le max_lines ]] || echo "- ...and $(( ${#commits[@]} - max_lines )) more commits"
  fi
  if [[ ! -f .github/release.yml && -n $previous_tag ]]; then
    echo
    echo "Full changelog: https://github.com/$repo/compare/$previous_tag...$tag"
  fi
  echo
  echo "## Verify your download"
  echo
  echo "Signing certificate SHA-256:"
  echo
  echo "\`$cert_sha256\`"
  echo
  echo "Check a download with \`sha256sum --check SHA256SUMS.txt --ignore-missing\`, and the APK signature with \`apksigner verify --print-certs WakeNudge-$version_name.apk\`."
  echo
  echo "\`mapping-$version_name.txt.gz\` (gzip) de-obfuscates crash reports and is not needed to use the app."
} > "$notes_file"

echo "Staged $(find "$out" -type f | wc -l) files in $out and wrote $notes_file (since ${previous_tag:-the beginning})"
