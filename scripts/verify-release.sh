#!/usr/bin/env bash
# Checks the signed release APK before anything is published. A stable release must be exactly what it claims to be,
# so every one of these must hold or the script exits non-zero:
#
#   * the APK is signed, by a real key rather than the debug certificate, and is not debuggable
#   * it carries the expected versionName and versionCode and the release application id
#   * it declares INTERNET (update checks) and no other network permission
#   * when the keystore is given, the certificate is the one in that keystore (so the build really used the CI key)
#
# Usage: scripts/verify-release.sh --apk FILE --version-name 1.0.57 --version-code 57
# Environment:
#   WAKENUDGE_KEYSTORE_PATH, WAKENUDGE_KEYSTORE_PASSWORD, WAKENUDGE_KEY_ALIAS   optional, enables the keystore check
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK with build-tools (apksigner, aapt2); falls back to sdk.dir in local.properties
# Prints the signing certificate as `cert_sha256=AA:BB:...` (and appends it to $GITHUB_OUTPUT when that is set).
set -euo pipefail

expected_package=com.riyadm.wakenudge
network_permissions=(android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.ACCESS_WIFI_STATE)

apk="" version_name="" version_code=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) apk=${2:?}; shift 2 ;;
    --version-name) version_name=${2:?}; shift 2 ;;
    --version-code) version_code=${2:?}; shift 2 ;;
    *) echo "usage: $0 --apk FILE --version-name X.Y.Z --version-code N" >&2; exit 2 ;;
  esac
done

problems=()
fatal() { echo "verify-release: $*" >&2; exit 1; }
problem() { problems+=("$*"); echo "  FAIL: $*" >&2; }
pass() { echo "  ok: $*"; }
heading() { echo; echo "== $*"; }

[[ -n $apk && -n $version_name && -n $version_code ]] || fatal "--apk, --version-name and --version-code are all required"
[[ -f $apk ]] || fatal "$apk does not exist"
[[ $apk != *unsigned* ]] || fatal "$apk is an unsigned build output; the release signing key was not applied"

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

# --- keystore ----------------------------------------------------------------------------------------------------
if [[ -n ${WAKENUDGE_KEYSTORE_PATH:-} && -n ${WAKENUDGE_KEYSTORE_PASSWORD:-} && -n ${WAKENUDGE_KEY_ALIAS:-} ]]; then
  heading "Signing key"
  key_cert=$(keytool "${jopts[@]}" -exportcert -alias "$WAKENUDGE_KEY_ALIAS" -keystore "$WAKENUDGE_KEYSTORE_PATH" -storepass:env WAKENUDGE_KEYSTORE_PASSWORD | sha256sum | cut -d' ' -f1)
  if [[ $key_cert == "$apk_cert" ]]; then pass "the APK is signed with the key in the keystore"; else problem "the APK was not signed with the key in the keystore"; fi
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
if grep -q "^uses-permission: name='android.permission.INTERNET'" "$work/badging.txt"; then pass "the APK declares INTERNET (update checks)"; else problem "the APK should declare INTERNET (update checks) but does not"; fi
for permission in "${network_permissions[@]:1}"; do
  if grep -q "name='$permission'" "$work/badging.txt"; then problem "the APK declares $permission, which WakeNudge never uses"; fi
done

# --- result ------------------------------------------------------------------------------------------------------
echo
if [[ ${#problems[@]} -gt 0 ]]; then
  echo "verify-release: ${#problems[@]} check(s) failed" >&2
  exit 1
fi
cert_sha256=$(colonize <<< "$apk_cert")
echo "cert_sha256=$cert_sha256"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then echo "cert_sha256=$cert_sha256" >> "$GITHUB_OUTPUT"; fi
