# Releasing

Every push to `main` becomes a release. There is no release branch, no tag to push and no button to press: the workflow
`.github/workflows/ci.yml` ("CI and Release") builds the commit, checks it, signs it and publishes it as a stable
[GitHub Release](https://github.com/riyadmondol2006/WakeNudge/releases/latest) holding the signed APK.

## What happens on a push to `main`

1. **`verify`** runs the unit tests, lint and the network-permission guard of both build types, and assembles the
   debug build. Pull requests and manual runs stop here.
2. **`release`** (only after `verify` passes, only for pushes to `main`) then:
   1. works out the version with `scripts/compute-version.sh --strict`;
   2. decodes the signing key from the repository secrets and **stops if any secret is missing**; a stable release is
      never unsigned or debug-signed. It also signs a throwaway jar to prove the key, alias and passwords work, before
      the long build starts;
   3. builds `assembleRelease` (the APK) with that version;
   4. runs `scripts/verify-release.sh` on the results (see below);
   5. stages the files and release notes (`scripts/stage-release.sh`) and keeps a copy as a workflow artifact for 14 days;
   6. publishes (`scripts/publish-release.sh`): tag `v<version>` on the commit, a draft release with all assets, then the
      draft is published, **not a prerelease** and marked **Latest**. Publishing last means nobody ever sees a release
      without its files;
   7. deletes the keystore from the runner.

Add `[skip release]` to the message of the commit you push (the newest one in the push) to skip step 2, for example for
a pure docs change.

Runs for `main` go one at a time and a started release is never cancelled. If you push again while one is running, only
the newest waiting run goes next: its release covers the commits of any run that was dropped in between.

### Release assets

| File | What it is |
|---|---|
| `WakeNudge.apk` | The very same file as the versioned APK, under a name that never changes. `https://github.com/riyadmondol2006/WakeNudge/releases/latest/download/WakeNudge.apk` therefore always downloads the newest APK; the website's Download button uses it. |
| `WakeNudge-<version>.apk` | The app. Install it on the phone. Holds `INTERNET` (and nothing else network-related) so the app can look for newer releases. |
| `mapping-<version>.txt.gz` | R8 mapping (gzipped, the raw file is about 77 MB), for de-obfuscating crash reports. |
| `SHA256SUMS.txt` | Checksums of everything above. |

The release notes list the commits since the previous `v*` tag, the signing certificate SHA-256 and short install
instructions. If you add `.github/release.yml`, GitHub's own categorised notes are appended as well.

### What `verify-release.sh` proves before anything is published

- the APK is signed (`apksigner verify --print-certs`), with signature scheme v2 or v3, by one signer that is not the
  Android debug certificate, and is not debuggable;
- that certificate is the one in the keystore from the secrets, so the build really used the CI key;
- the APK (`aapt2 dump badging`) carries the computed `versionName` and `versionCode` and the application id
  `com.riyadm.wakenudge`;
- the APK declares `INTERNET` and neither `ACCESS_NETWORK_STATE` nor `ACCESS_WIFI_STATE`.

`aapt2` and `apksigner` come from the newest `build-tools` in the SDK; nothing else is downloaded.

## Versions

`versionName = MAJOR.MINOR.N` and `versionCode = N`, for example `1.0.57` and `57`.

- `MAJOR.MINOR` is **yours**: it lives in `version.properties`. Raise `minor` (or `major`) in the commit that starts a
  feature release or a breaking change. Nothing else needs touching.
- `N` is **CI's**: the number of commits reachable from `HEAD` (`git rev-list --count HEAD`). It only ever grows on
  `main`, so every release installs over the one before (Android refuses a lower `versionCode`), and it stays far below
  the 2,100,000,000 ceiling app stores use. It also makes every version traceable to a commit.
- Debug builds get the same numbers with a `-debug` suffix.

Where the numbers come from, highest priority first:

1. Gradle properties `-Pwakenudge.versionCode=…` / `-Pwakenudge.versionName=…`;
2. environment variables `WAKENUDGE_VERSION_CODE` / `WAKENUDGE_VERSION_NAME` (CI uses these, so the build and the release
   cannot disagree);
3. git, as above;
4. with no git at all (a source tarball): code `1` and name `MAJOR.MINOR.0`.

`scripts/compute-version.sh` applies the same rules for the workflow; `./gradlew -q :app:printVersion` shows what Gradle
will use. The release job passes `--strict`, which refuses to guess: it fails without git or on a shallow clone (too few
commits would make the `versionCode` go backwards), which is why that job checks out with `fetch-depth: 0`.

Two things to avoid, because they break monotonic numbering: force-pushing a rewritten `main` (the commit count can drop
below an already published `versionCode`), and squashing history after the fact. Merge commits are fine.

## Secrets

The release job needs four repository secrets (Settings, Secrets and variables, Actions):

| Secret | Value |
|---|---|
| `WAKENUDGE_KEYSTORE_BASE64` | the keystore file, base64 encoded on one line |
| `WAKENUDGE_KEYSTORE_PASSWORD` | the keystore password |
| `WAKENUDGE_KEY_ALIAS` | the key alias inside the keystore |
| `WAKENUDGE_KEY_PASSWORD` | the password of that key |

Create a key once, somewhere safe (not in the repository; `*.jks`, `*.keystore` and `keystore.properties` are
git-ignored), valid for decades:

```bash
keytool -genkeypair -v -keystore wakenudge.jks -storetype PKCS12 -alias wakenudge \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then store it as secrets. With the GitHub CLI (the values never touch your shell history or the screen):

```bash
base64 -w0 wakenudge.jks | gh secret set WAKENUDGE_KEYSTORE_BASE64
gh secret set WAKENUDGE_KEYSTORE_PASSWORD
gh secret set WAKENUDGE_KEY_ALIAS
gh secret set WAKENUDGE_KEY_PASSWORD
```

or paste the output of `base64 -w0 wakenudge.jks` into the web form. Keep an offline copy of the keystore and its
passwords (a password manager). **The APK's signing key can never be replaced without breaking updates for people who
installed it**: Android refuses to update an app signed with a different key.

Only the `release` job reads the secrets, and it only runs for pushes to `main` in this repository (not in forks, not for
pull requests). It hands them to the individual steps that need them, passes them as environment variables (never on a
command line), relies on GitHub masking them in logs, and deletes the decoded keystore at the end of the job.

### Rotating

- **Changing a password only**: `keytool -storepasswd -keystore wakenudge.jks` and
  `keytool -keypasswd -alias wakenudge -keystore wakenudge.jks`, then update the matching secrets and re-run the job.
- **Replacing the key** (suspected leak): upload the new keystore as `WAKENUDGE_KEYSTORE_BASE64` with its new alias and
  passwords. Be aware that people who installed the APK cannot update in place to a build signed with the new key; they
  have to export a backup, uninstall and install again.
- A missing or wrong secret never produces a lesser release; the job stops at "Restore and check the signing key" with
  an error that names what is missing.

## Re-running and recovering

- **Re-run a failed job:** Actions, the run, Re-run failed jobs. The release job picks the same commit and the same
  version again. It is safe to repeat: if the release is already published nothing happens, an unfinished draft is
  replaced, and an existing tag on the same commit is reused.
- **Release again without changing code:** push an empty commit (`git commit --allow-empty -m "Rebuild"`); it gets the
  next version number. The "Run workflow" button only runs `verify` and the emulator tests, because releases come from
  pushes to `main`.
- **Replace a published release** (a broken asset, say): delete the release and its tag on GitHub, then re-run the job.
  Prefer shipping a new version instead if anybody may already have downloaded the old one.
- **Where the files are if publishing failed:** the run's artifacts hold `wakenudge-release-<version>` for 14 days, with
  the same files the release would have had.

### If the release job fails

| Step that failed | Meaning and fix |
|---|---|
| Work out the version | No full git history or an unreadable `version.properties`. The checkout must use `fetch-depth: 0`; `version.properties` needs numeric `major=` and `minor=`. |
| Restore and check the signing key | A secret is missing or empty, the base64 is not valid, or the alias/passwords do not open the keystore. Re-create the secrets (see above). |
| Build the signed APK | An ordinary build failure or a network-permission guard failure; read the Gradle log. `verify` normally catches these first. |
| Verify the signed APK | The message names the failed check (wrong version, debug certificate, a key other than the one in the secrets, an unexpected permission, and so on). Nothing was published. Fix the cause and push again. |
| Publish the GitHub Release | `tag ... already points at ...` means that version number was used by another commit, which only happens if history was rewritten: push a new commit to get a new number. Anything else is usually a transient GitHub error; re-run the job. |

## Running the pieces locally

```bash
scripts/compute-version.sh                # version_name=1.0.57 / version_code=57
./gradlew -q :app:printVersion            # what Gradle will use

# a signed release build, like CI does it (keystore.properties works too, see the README)
export WAKENUDGE_KEYSTORE_PATH=$PWD/wakenudge.jks WAKENUDGE_KEYSTORE_PASSWORD=... WAKENUDGE_KEY_ALIAS=... WAKENUDGE_KEY_PASSWORD=...
export WAKENUDGE_REQUIRE_SIGNING=true      # fail instead of building an unsigned release
./gradlew :app:assembleRelease

scripts/verify-release.sh \
  --apk app/build/outputs/apk/release/app-release.apk \
  --version-name 1.0.57 --version-code 57
```

## Dependency updates

`.github/dependabot.yml` opens at most a few grouped pull requests a week: minor and patch Gradle updates together, the
Kotlin/KSP/Hilt toolchain together, major updates on their own, and one for GitHub Actions. Because merging to `main`
releases, treat them like any other change: merge when CI is green.

## Files

| File | Role |
|---|---|
| `.github/workflows/ci.yml` | the pipeline |
| `.github/dependabot.yml` | weekly dependency updates |
| `version.properties` | `major` and `minor` |
| `app/build.gradle.kts` | version and signing configuration (`printVersion`, `WAKENUDGE_REQUIRE_SIGNING`) |
| `scripts/compute-version.sh` | the version, for the workflow |
| `scripts/verify-release.sh` | checks the signed APK |
| `scripts/stage-release.sh` | names the assets, writes `SHA256SUMS.txt` and the release notes |
| `scripts/publish-release.sh` | tag, draft, upload, publish |
