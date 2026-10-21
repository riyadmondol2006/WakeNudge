#!/usr/bin/env bash
# Checks the signed release artifacts before anything is published. A stable release must be exactly what it claims to
# be, so every one of these must hold or the script exits non-zero:
#
#   * the APK (github flavour) is signed, by a real key rather than the debug certificate, and is not debuggable
#   * the AAB (play flavour) is signed, with the very same certificate as the APK
#   * both carry the expected versionName and versionCode and the release application id
#   * the APK declares INTERNET (update checks) and the AAB declares no network permission at all
#   * when the keystore is given, the certificate is the one in that keystore (so the build really used the CI key)
#
# Usage: scripts/verify-release.sh --apk FILE --aab FILE --version-name 1.0.57 --version-code 57
# Environment:
#   WAKENUDGE_KEYSTORE_PATH, WAKENUDGE_KEYSTORE_PASSWORD, WAKENUDGE_KEY_ALIAS   optional, enables the keystore check
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK with build-tools (apksigner, aapt2); falls back to sdk.dir in local.properties
#   BUNDLETOOL_JAR   path to bundletool-all.jar; by default the pinned release below is downloaded and checksummed
# Prints the signing certificate as `cert_sha256=AA:BB:...` (and appends it to $GITHUB_OUTPUT when that is set).
set -euo pipefail

# bundletool reads the AAB's protobuf manifest, which no SDK tool can. Pinned and checksummed so a release never runs
# a tool nobody looked at. To bump: take the jar and its digest from https://github.com/google/bundletool/releases.
bundletool_version=1.18.3
bundletool_sha256=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29

expected_package=com.riyadm.wakenudge
network_permissions=(android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.ACCESS_WIFI_STATE)

apk="" aab="" version_name="" version_code=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) apk=${2:?}; shift 2 ;;
    --aab) aab=${2:?}; shift 2 ;;
    --version-name) version_name=${2:?}; shift 2 ;;
    --version-code) version_code=${2:?}; shift 2 ;;
    *) echo "usage: $0 --apk FILE --aab FILE --version-name X.Y.Z --version-code N" >&2; exit 2 ;;
  esac
done

problems=()
fatal() { echo "verify-release: $*" >&2; exit 1; }
problem() { problems+=("$*"); echo "  FAIL: $*" >&2; }
pass() { echo "  ok: $*"; }
heading() { echo; echo "== $*"; }

[[ -n $apk && -n $aab && -n $version_name && -n $version_code ]] || fatal "--apk, --aab, --version-name and --version-code are all required"
for f in "$apk" "$aab"; do
  [[ -f $f ]] || fatal "$f does not exist"
  [[ $f != *unsigned* ]] || fatal "$f is an unsigned build output; the release signing key was not applied"
done

if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/java ]]; then java_bin=$JAVA_HOME/bin/java; else java_bin=java; fi
# Tool output is parsed, so keep it in English whatever the machine's locale is.
jopts=(-J-Duser.language=en -J-Duser.country=US)

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk && -f local.properties ]]; then sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1); fi
[[ -d $sdk/build-tools ]] || fatal "no Android SDK with build-tools found (set ANDROID_HOME)"
# The newest build-tools is the one AGP just used (or installed) for this build.
build_tools=$sdk/build-tools/$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort -V | tail -n 1)
apksigner=$build_tools/apksigner
aapt2=$build_tools/aapt2
[[ -x $apksigner && -x $aapt2 ]] || fatal "apksigner or aapt2 is missing from $build_tools"

bundletool=${BUNDLETOOL_JAR:-}
if [[ -z $bundletool ]]; then
  bundletool=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/bundletool-all-$bundletool_version.jar
  if [[ ! -f $bundletool ]]; then
    curl -fsSL --retry 3 -o "$bundletool" "https://github.com/google/bundletool/releases/download/$bundletool_version/bundletool-all-$bundletool_version.jar"
  fi
  echo "$bundletool_sha256  $bundletool" | sha256sum --check --quiet - || fatal "bundletool $bundletool_version does not match its pinned checksum"
fi
[[ -f $bundletool ]] || fatal "BUNDLETOOL_JAR=$bundletool does not exist"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

normalize_lines() { tr -d ': ' | tr '[:upper:]' '[:lower:]'; } # fingerprints -> bare lowercase hex, one per line
colonize() { sed 's/../&:/g; s/:$//' | tr '[:lower:]' '[:upper:]'; }

# --- APK signature -----------------------------------------------------------------------------------------------
heading "APK signature ($(basename "$apk"))"
"$apksigner" verify --verbose --print-certs "$apk" > "$work/apksigner.txt" 2>&1 || { cat "$work/apksigner.txt" >&2; fatal "apksigner rejected the APK"; }
grep -Eq '^Verified using v[23](\.[0-9])? scheme .*: true' "$work/apksigner.txt" || problem "the APK is not signed with signature scheme v2 or v3"
# apksigner words the signer lines differently across build-tools versions ("Signer #1 certificate ..." or
# "V2 Signer: certificate ..."), and repeats them per scheme, so collect every certificate it names.
apk_certs=$(sed -n 's/^.*[Ss]igner[^:]*[: ] *certificate SHA-256 digest: //p' "$work/apksigner.txt" | normalize_lines | sort -u)
apk_dns=$(sed -n 's/^.*[Ss]igner[^:]*[: ] *certificate DN: //p' "$work/apksigner.txt" | sort -u)
[[ $apk_certs =~ ^[0-9a-f]{64}$ ]] || { cat "$work/apksigner.txt" >&2; fatal "the APK must have exactly one signing certificate (apksigner reported: ${apk_certs:-none})"; }
apk_cert=$apk_certs
if [[ $apk_dns == *"Android Debug"* ]]; then problem "the APK is signed with the Android debug certificate"; else pass "signed by a non-debug key ($apk_dns)"; fi
pass "apksigner verified the APK, certificate SHA-256 $(colonize <<< "$apk_cert")"

# --- AAB signature -----------------------------------------------------------------------------------------------
heading "AAB signature ($(basename "$aab"))"
jarsigner_out=$(jarsigner "${jopts[@]}" -verify "$aab" 2>&1) || { echo "$jarsigner_out" >&2; fatal "jarsigner rejected the AAB"; }
grep -q '^jar verified\.' <<< "$jarsigner_out" || { echo "$jarsigner_out" >&2; fatal "jarsigner did not report the AAB as verified (is it unsigned?)"; }
pass "jarsigner verified the AAB"
aab_certs=$(keytool "${jopts[@]}" -printcert -jarfile "$aab" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | normalize_lines | sort -u)
[[ $aab_certs =~ ^[0-9a-f]{64}$ ]] || problem "the AAB must be signed by exactly one certificate (found: ${aab_certs:-none})"
if [[ $aab_certs == "$apk_cert" ]]; then pass "the AAB and the APK share one signing certificate"; else problem "the AAB certificate ($aab_certs) differs from the APK certificate ($apk_cert)"; fi

# --- keystore ----------------------------------------------------------------------------------------------------
if [[ -n ${WAKENUDGE_KEYSTORE_PATH:-} && -n ${WAKENUDGE_KEYSTORE_PASSWORD:-} && -n ${WAKENUDGE_KEY_ALIAS:-} ]]; then
  heading "Signing key"
  key_cert=$(keytool "${jopts[@]}" -exportcert -alias "$WAKENUDGE_KEY_ALIAS" -keystore "$WAKENUDGE_KEYSTORE_PATH" -storepass:env WAKENUDGE_KEYSTORE_PASSWORD | sha256sum | cut -d' ' -f1)
  if [[ $key_cert == "$apk_cert" ]]; then pass "the artifacts are signed with the key in the keystore"; else problem "the artifacts were not signed with the key in the keystore"; fi
fi

# --- APK metadata ------------------------------------------------------------------------------------------------
heading "APK metadata"
"$aapt2" dump badging "$apk" > "$work/badging.txt"
badging_package=$(sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p" "$work/badging.txt" | head -n 1)
if [[ $badging_package == "$expected_package $version_code $version_name" ]]; then
  pass "package, versionCode and versionName are $badging_package"
else
  problem "the APK reports '$badging_package' but '$expected_package $version_code $version_name' was expected"
fi
if grep -q '^application-debuggable' "$work/badging.txt"; then problem "the APK is debuggable"; else pass "the APK is not debuggable"; fi
if grep -q "^uses-permission: name='android.permission.INTERNET'" "$work/badging.txt"; then pass "the APK declares INTERNET (update checks)"; else problem "the github APK should declare INTERNET but does not"; fi
for permission in "${network_permissions[@]:1}"; do
  if grep -q "name='$permission'" "$work/badging.txt"; then problem "the APK declares $permission, which WakeNudge never uses"; fi
done

# --- AAB metadata ------------------------------------------------------------------------------------------------
heading "AAB metadata"
"$java_bin" -jar "$bundletool" dump manifest --bundle="$aab" --module=base > "$work/aab-manifest.xml"
aab_package=$(grep -o ' package="[^"]*"' "$work/aab-manifest.xml" | head -n 1 | sed 's/.*="\(.*\)"/\1/' || true)
aab_code=$(grep -o 'android:versionCode="[^"]*"' "$work/aab-manifest.xml" | head -n 1 | sed 's/.*="\(.*\)"/\1/' || true)
aab_name=$(grep -o 'android:versionName="[^"]*"' "$work/aab-manifest.xml" | head -n 1 | sed 's/.*="\(.*\)"/\1/' || true)
if [[ "$aab_package $aab_code $aab_name" == "$expected_package $version_code $version_name" ]]; then
  pass "package, versionCode and versionName are $aab_package $aab_code $aab_name"
else
  problem "the AAB reports '$aab_package $aab_code $aab_name' but '$expected_package $version_code $version_name' was expected"
fi
if grep -q 'android:debuggable="true"' "$work/aab-manifest.xml"; then problem "the AAB is debuggable"; else pass "the AAB is not debuggable"; fi
play_network=0
for permission in "${network_permissions[@]}"; do
  if grep -q "$permission" "$work/aab-manifest.xml"; then problem "the Play AAB mentions $permission; the Play build must stay completely offline"; play_network=1; fi
done
[[ $play_network -eq 0 ]] && pass "the Play AAB declares no network permission"

# --- result ------------------------------------------------------------------------------------------------------
echo
if [[ ${#problems[@]} -gt 0 ]]; then
  echo "verify-release: ${#problems[@]} check(s) failed" >&2
  exit 1
fi
cert_sha256=$(colonize <<< "$apk_cert")
echo "cert_sha256=$cert_sha256"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then echo "cert_sha256=$cert_sha256" >> "$GITHUB_OUTPUT"; fi
