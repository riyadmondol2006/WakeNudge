# Releasing

Every push to `main` becomes a release. There is no release branch, no tag to push and no button to press: the workflow
`.github/workflows/ci.yml` ("CI and Release") builds the commit, checks it, signs it and publishes it as a stable
[GitHub Release](https://github.com/riyadmondol2006/WakeNudge/releases/latest) holding a signed APK and a signed AAB.

## What happens on a push to `main`

1. **`verify`** runs the unit tests of both flavours, lint for both, the network-permission guard for all four variants
   and assembles both debug builds. Pull requests and manual runs stop here.
2. **`release`** (only after `verify` passes, only for pushes to `main`) then:
   1. works out the version with `scripts/compute-version.sh --strict`;
   2. decodes the signing key from the repository secrets and **stops if any secret is missing**; a stable release is
      never unsigned or debug-signed. It also signs a throwaway jar to prove the key, alias and passwords work, before
      the long build starts;
   3. builds `assembleGithubRelease` (the APK) and `bundlePlayRelease` (the AAB) with that version;
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
| `WakeNudge-<version>.apk` | `github` flavour. Sideload it. Holds `INTERNET` (and nothing else network-related) so the app can look for newer releases. |
| `WakeNudge-<version>.aab` | `play` flavour. This is what you upload to Google Play. No network permission, ever. |
| `mapping-<version>.txt.gz` | R8 mapping of the play build (gzipped, the raw file is about 77 MB), for de-obfuscating Play crash reports. |
| `mapping-<version>-github.txt.gz` | R8 mapping of the github build; only attached when it differs from the play one. |
| `SHA256SUMS.txt` | Checksums of everything above. |

The release notes list the commits since the previous `v*` tag, the signing certificate SHA-256 and short install
instructions. If you add `.github/release.yml`, GitHub's own categorised notes are appended as well.

### What `verify-release.sh` proves before anything is published

- the APK is signed (`apksigner verify --print-certs`), with signature scheme v2 or v3, by one signer that is not the
  Android debug certificate, and is not debuggable;
- the AAB is signed (`jarsigner -verify`) by exactly one certificate, and it is the **same certificate** as the APK's;
- that certificate is the one in the keystore from the secrets, so the build really used the CI key;
- the APK (`aapt2 dump badging`) and the AAB (`bundletool dump manifest`) both carry the computed `versionName` and
  `versionCode` and the application id `com.riyadm.wakenudge`;
- the APK declares `INTERNET` and neither `ACCESS_NETWORK_STATE` nor `ACCESS_WIFI_STATE`;
- the AAB's base manifest mentions no network permission at all.

`aapt2` and `apksigner` come from the newest `build-tools` in the SDK. **bundletool** is the one extra tool: the script
downloads the pinned release (`bundletool-all-1.18.3.jar` from
[github.com/google/bundletool/releases](https://github.com/google/bundletool/releases)) into the runner's temp folder
and refuses to use it unless its SHA-256 matches the value written in the script. To move to a newer bundletool, change
`bundletool_version` and `bundletool_sha256` at the top of `scripts/verify-release.sh`. Locally, point `BUNDLETOOL_JAR` at
a jar you already have.

## Versions

`versionName = MAJOR.MINOR.N` and `versionCode = N`, for example `1.0.57` and `57`.

- `MAJOR.MINOR` is **yours**: it lives in `version.properties`. Raise `minor` (or `major`) in the commit that starts a
  feature release or a breaking change. Nothing else needs touching.
- `N` is **CI's**: the number of commits reachable from `HEAD` (`git rev-list --count HEAD`). It only ever grows on
  `main`, which is exactly what Google Play requires of a `versionCode`, and it stays far below Play's limit of
  2,100,000,000. It also makes every version traceable to a commit.
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
git-ignored). Play requires a validity beyond 22 October 2033:

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
- **Replacing the key** (suspected leak): follow the Play upload key reset below first, then upload the new keystore as
  `WAKENUDGE_KEYSTORE_BASE64` with its new alias and passwords. Be aware that people who installed the GitHub APK cannot
  update in place to a build signed with the new key; they have to export a backup, uninstall and install again. Play
  users are not affected, because Google signs what they receive.
- A missing or wrong secret never produces a lesser release; the job stops at "Restore and check the signing key" with
  an error that names what is missing.

## Putting the bundle on Google Play

The workflow does not talk to Google Play. After a release, download `WakeNudge-<version>.aab` from the release page and
upload it yourself: Play Console, the app, Release (Production or a testing track), Create new release, upload the file,
add release notes, review and roll out.

- **First upload:** Play will ask you to enrol in **Play App Signing**. Accept it and let Google generate the app
  signing key. From then on the key in the repository secrets is only the **upload key**: it proves to Play that the
  bundle comes from you, and Google re-signs what users download with its own key. That is also why the Play build and the
  GitHub APK cannot be installed over one another.
- **If the upload key is lost or leaked**, Play can reset it (Play Console, App integrity, App signing,
  request an upload key reset). You then create a new keystore and replace the secrets as above.
- **Version codes** must be higher than anything uploaded before and can never be reused. Because every push to `main`
  gets a higher number, the bundle from the newest release is always acceptable; you can skip any releases you do not
  want on Play.
- **Mapping file:** AGP embeds the R8 mapping in the bundle, so Play can de-obfuscate crashes without further steps.
  `mapping-<version>.txt.gz` is the same file (gzipped), kept for your own use.

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
| Build the signed APK and AAB | An ordinary build failure or a network-permission guard failure; read the Gradle log. `verify` normally catches these first. |
| Verify the signed artifacts | The message names the failed check (wrong version, debug certificate, APK and AAB signed by different keys, an unexpected permission, and so on). Nothing was published. Fix the cause and push again. |
| Publish the GitHub Release | `tag ... already points at ...` means that version number was used by another commit, which only happens if history was rewritten: push a new commit to get a new number. Anything else is usually a transient GitHub error; re-run the job. |

## Running the pieces locally

```bash
scripts/compute-version.sh                # version_name=1.0.57 / version_code=57
./gradlew -q :app:printVersion            # what Gradle will use

# a signed release build, like CI does it (keystore.properties works too, see the README)
export WAKENUDGE_KEYSTORE_PATH=$PWD/wakenudge.jks WAKENUDGE_KEYSTORE_PASSWORD=... WAKENUDGE_KEY_ALIAS=... WAKENUDGE_KEY_PASSWORD=...
export WAKENUDGE_REQUIRE_SIGNING=true      # fail instead of building an unsigned release
./gradlew :app:assembleGithubRelease :app:bundlePlayRelease

scripts/verify-release.sh \
  --apk app/build/outputs/apk/github/release/app-github-release.apk \
  --aab app/build/outputs/bundle/playRelease/app-play-release.aab \
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
| `scripts/verify-release.sh` | checks the signed APK and AAB |
| `scripts/stage-release.sh` | names the assets, writes `SHA256SUMS.txt` and the release notes |
| `scripts/publish-release.sh` | tag, draft, upload, publish |
