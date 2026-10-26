# Security policy

WakeNudge is an alarm clock. Two kinds of problem are treated as security issues here: an alarm that can be made to fail
**silently** by something other than the owner, and **exposure of the owner's data**.

## Reporting a vulnerability

Please report privately, not in a public issue:

1. Go to the repository's **Security** tab and choose **Report a vulnerability**
   ([direct link](https://github.com/riyadmondol2006/WakeNudge/security/advisories/new)).
2. Describe what you found, the Android version and WakeNudge version, and how to reproduce it.

You will get a reply within about a week. WakeNudge is maintained by one person, so this is a best-effort promise
rather than a service level. Once a fix is released, the advisory is published and you are credited if you wish.

## In scope

- Another app, a crafted intent, a crafted backup file or a crafted NFC tag or QR code that can delete, disable, silence
  or reschedule alarms, or stop an alarm from ringing, without the user's action.
- Any way to read WakeNudge's alarms, history, settings, backups or diagnostics from another app or over the network.
- A flaw in the update check (for example, one that would let someone else's file be offered as an update).
- A build that declares a network permission it should not: the app may hold only `INTERNET`.

## Not security issues (please use a normal issue)

- An alarm that did not ring because the phone was off, or because a manufacturer's battery manager killed the app.
  These are reliability bugs and very welcome as [bug reports](https://github.com/riyadmondol2006/WakeNudge/issues/new/choose)
  with the Alarm Health report.
- Attacks that need a rooted phone, a modified system, physical access to an unlocked phone, or the owner's own backup
  file being shared by the owner.

## Supported versions

Only the [latest release](https://github.com/riyadmondol2006/WakeNudge/releases/latest) receives fixes.

## Verifying what you install

Each GitHub release includes `SHA256SUMS.txt`. The app holds only `INTERNET`, for its update check; see [docs/PERMISSIONS.md](docs/PERMISSIONS.md) and [PRIVACY.md](PRIVACY.md).
