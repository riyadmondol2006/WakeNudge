#!/usr/bin/env bash
# Lays out the release: copies the verified build outputs into one folder under their public names, writes
# SHA256SUMS.txt, and writes the release notes.
#
#   WakeNudge.apk                     the same APK under a fixed name (the "latest download" link)
#   WakeNudge-<version>.apk           github flavour (sideload)
#   WakeNudge-<version>.aab           play flavour (what is uploaded to Google Play)
#   mapping-<version>.txt.gz          R8 mapping of the play build (gzipped: the raw file is ~77 MB), to de-obfuscate Play crash reports
#   mapping-<version>-github.txt.gz   R8 mapping of the github build (only shipped when it differs from the play one)
#   SHA256SUMS.txt
#
# Usage: scripts/stage-release.sh --version-name 1.0.57 --cert-sha256 AA:BB:.. --apk F --aab F --mapping-play F \
#            [--mapping-github F] --out DIR --notes-file F
# Environment: GITHUB_REPOSITORY (links in the notes), GITHUB_SHA (defaults to HEAD).
# The notes list the commits since the previous v* tag. When .github/release.yml exists the publish step also asks
# GitHub for its categorised notes, which already end in a "Full Changelog" link, so this script then leaves that out.
set -euo pipefail

version_name="" cert_sha256="" apk="" aab="" mapping_play="" mapping_github="" out="" notes_file=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version-name) version_name=${2:?}; shift 2 ;;
    --cert-sha256) cert_sha256=${2:?}; shift 2 ;;
    --apk) apk=${2:?}; shift 2 ;;
    --aab) aab=${2:?}; shift 2 ;;
    --mapping-play) mapping_play=${2:?}; shift 2 ;;
    --mapping-github) mapping_github=${2:?}; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    --notes-file) notes_file=${2:?}; shift 2 ;;
    *) echo "usage: $0 --version-name V --cert-sha256 FP --apk F --aab F --mapping-play F [--mapping-github F] --out DIR --notes-file F" >&2; exit 2 ;;
  esac
done
for required in version_name cert_sha256 apk aab mapping_play out notes_file; do
  [[ -n ${!required} ]] || { echo "stage-release: --${required//_/-} is required" >&2; exit 2; }
done
for f in "$apk" "$aab" "$mapping_play"; do [[ -f $f ]] || { echo "stage-release: $f does not exist" >&2; exit 1; }; done

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
cp "$aab" "$out/WakeNudge-$version_name.aab"
gzip -9 -n -c "$mapping_play" > "$out/mapping-$version_name.txt.gz"
github_mapping_note=""
if [[ -n $mapping_github && -f $mapping_github ]] && ! cmp -s "$mapping_play" "$mapping_github"; then
  gzip -9 -n -c "$mapping_github" > "$out/mapping-$version_name-github.txt.gz"
  github_mapping_note=" \`mapping-$version_name-github.txt.gz\` is the equivalent for the APK."
fi
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
  echo "- **Android phone (sideload):** download \`WakeNudge-$version_name.apk\`, open it and allow installing from your browser or file manager when Android asks. This is the GitHub edition: the only WakeNudge build with the INTERNET permission, used to look for newer releases here."
  echo "- **Always the newest APK:** \`WakeNudge.apk\` is the very same file under a fixed name, so [this link](https://github.com/$repo/releases/latest/download/WakeNudge.apk) always downloads the latest release."
  echo "- **Google Play:** \`WakeNudge-$version_name.aab\` is the Android App Bundle that is uploaded to Google Play. It cannot be installed on a phone directly; it is attached so the Play build is public too."
  echo
  echo "The Play build and the APK are signed with different keys (Google re-signs Play builds), so switching from one to the other means uninstalling first. Alarms and settings live on the phone: export a backup before you switch."
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
  echo "Both files are signed with the same key. Signing certificate SHA-256:"
  echo
  echo "\`$cert_sha256\`"
  echo
  echo "Check a download with \`sha256sum --check SHA256SUMS.txt --ignore-missing\`, and the APK signature with \`apksigner verify --print-certs WakeNudge-$version_name.apk\`."
  echo
  echo "\`mapping-$version_name.txt.gz\` (gzip) de-obfuscates crash reports of the Play build and is not needed to use the app.$github_mapping_note"
} > "$notes_file"

echo "Staged $(find "$out" -type f | wc -l) files in $out and wrote $notes_file (since ${previous_tag:-the beginning})"
