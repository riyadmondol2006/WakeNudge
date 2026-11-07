# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Releases are built and published automatically from `main` and numbered `MAJOR.MINOR.N`, where `N` is the commit count
(see [docs/RELEASING.md](docs/RELEASING.md)); the notes of each GitHub release list the commits it contains.

## The docs say what the app does on a real phone

- **README**: a short explanation of Easy, Normal, Hard and No mercy for a new user, Focus Tap among the eleven missions,
  and a new section, **What it can't stop**: power off isn't blocked (the alarm rings again after a restart, at full volume
  with no snooze), and what device admin, Safe mode, Do Not Disturb, calls and a code kept by the bed mean in practice
- **Measured on the test Pixel 6** (Android 17, gesture navigation) and written down in docs/TESTING.md: after a restart
  mid-ring the sound is back 21 to 23 seconds after the phone finishes starting; after Stop in Active apps, 17 seconds;
  after a force stop, when the app is next opened. The alarm screen is back about 1.2 seconds after Home or Recents. With
  device admin on, uninstall is refused and Force stop does nothing
- The store listing no longer says the app has no internet permission (it holds one, for the optional update check), and
  no longer describes the old Emergency stop and a still-awake check you can always cancel
- The phone the app was tested on is a Pixel 6, not a Pixel 6 Pro as the README said
- Power-off blocking was planned as an optional add-on and dropped; nothing offers it
- A new test fails if the app's text, the README or the store listing claims the alarm can't be escaped or that power off
  is blocked, or if the README loses its "What it can't stop" section

## A morning record, a torch for an ignored alarm, and no credit for a machine

- **How it went**, on the Morning screen: whether the backup mission was used, a way out taken, a ring missed, and how many
  Emergency pauses, restarts, times brought back to the alarm and mutes, this morning and over the last 7 days. It is
  counted from the history on the phone; nothing is sent anywhere
- **Flash the torch if I ignore it** (More options, Voice and light): once the alarm got louder for being ignored, the torch
  flashes once a second, never faster. It stops for an Emergency pause, a mute and while a camera mission is open, and goes
  off when the alarm ends
- **A machine gets no credit**: a phone on a shaker, or a held-steady metronome shake, stops counting once its rhythm is
  known; the same goes for an accelerometer-counted walk too even for a person. People shaking and walking still count
- **The photo mission suggests the torch** after 30 seconds without a match
- **"I can't do timed or physical missions"** (Settings, Look and feel): the picker leaves out shake, steps, squats, Focus
  Tap and Memory, every mission waits for you instead of timing you, backup and way-out tasks are sums and typing, and the
  still-awake check asks for three sums. Missions also wait for you when Switch Access or Voice Access is on, as they did
  with a screen reader
- The still-awake notification no longer names Focus Tap, since the check may ask for sums

## How hard? Four levels, and every detail in plain words

- **Each alarm is Easy, Normal, Hard or No mercy**, each explained in one line in the editor. Easy: swipe to stop, snooze.
  Normal: finish a mission, snooze up to 3 times, keeps ringing. Hard: no snooze, rings until you finish, checks you're
  still up a few minutes later, comes back after a restart. No mercy: Hard with two checks and the strictest limits.
  Picking one sets all of its details; changing a detail shows **Custom**, and putting it back shows the level again.
  No mercy asks once, the first time. Under Hard and No mercy the editor says "Fully protected" or how many setup steps are
  left, and opens the setup
- **The editor is shorter**: label, time, repeat, How hard?, mission, sound, and More options for everything else. Every
  per-alarm detail has a plain control there, including ones only the engine had until now: the volume floor, what the
  volume buttons do, bringing the alarm back if you leave it, mutes during a mission, full brightness, the phone's speaker,
  when and how long the way out is, the backup mission's length, how long and how often it comes back after a restart, and
  unlocking edits with the alarm's own mission
- **A new alarm is Normal** with a Mixed mission (Focus Tap, Memory or three sums, a different one each morning), and a new
  install's sound is **Surprise me**, a different tone each morning. An alarm set by voice without opening the app is Easy.
  Adding a mission to an Easy alarm makes it Normal, removing the last one from Normal makes it Easy, and Hard and No mercy
  keep at least one. Picking Easy puts missions aside; picking a harder level again brings them back
- **Existing alarms ring exactly as before.** They show the level they match, or Custom; nothing is made harder. The alarm
  list shows each alarm's level, a one-time card about levels, and for the next alarm "Ready" or what the evening check
  would warn about
- **Settings, While an alarm rings is gone**: volume buttons and coming back to the alarm screen are set on each alarm. New
  alarm defaults are now how hard, the sound and the vibration
- **Fixed on the Pixel**: dragging a slider from its left end could trigger the Back gesture and a "Discard changes?"
  dialog; sliders now keep the back gesture off themselves, and so do slide-to-dismiss and the puzzle boards. The volume
  slider's lowest position used to mean "leave the system volume", so dragging an alarm quiet could make it ring at the
  phone's loud level: "Use the phone's alarm volume" is now its own switch and the slider stops at 10 %. "Stop ringing
  after" no longer steps below 1 minute into Never: that is the switch "Keep ringing until I finish the mission"
- A settings file from an older version reads as before; new alarms from it start at Normal

## Protection setup, device admin and a rehearsal before anything hard to undo

- **Settings, Protection** (also linked from the alarm editor) takes a phone that installed WakeNudge from a download to
  full protection, one step at a time, showing what Android says each time you come back: "Display over other apps" (with
  the App info, three dots, "Allow restricted settings" detour Android asks of apps not from a store), unrestricted
  battery, notifications, full-screen alarms, a rehearsal and device admin. It says "Fully protected" or how many steps are
  left
- **Try it now**: a real ring of the alarm 5 seconds later, with its missions, through the real alarm path. No snooze, no
  backup, it gives up after 10 minutes, and Emergency and the way out work as on a real morning. It counts as rehearsed only
  when you finish its missions. Saving a strict alarm with a mission never rehearsed offers it
- **Device admin, opt-in, with no device powers**: while it is on, Android won't let WakeNudge be force-stopped or
  uninstalled. It is offered only after a finished rehearsal. Turn it off in Protection any time no alarm rings or is
  snoozed (the Turn off row is always there), or in Settings, Security, Device admin apps at any time. Alarm Health shows
  when it is on
- **No way out without the mission**, per alarm with a mission, only after a finished rehearsal of those missions and with
  a warning: if something goes wrong, restarting the phone up to the alarm's limit or Safe mode are the only ways to stop
  it. Emergency and the backup mission stay. Changing the missions, or restoring a backup, puts the way out back
- **On saving**, the editor warns about what would stop the missions (camera not allowed, NFC off, not set up, no sensor,
  the steps mission without physical activity, a motion mission with a screen reader on) and asks for the permission there
- **What isn't protected** is said plainly in the setup: power off isn't blocked, and after a restart the alarm rings
  again; Extreme Battery Saver, Total Silence, Safe mode and a flat battery are named too. Alarm Health's Battery Saver
  note now mentions Extreme Battery Saver

## No side doors before the alarm, and a still-awake check a sleeper cannot answer

- **Edit protection now covers every way round it.** While an alarm is inside its "Lock before it rings" window, pausing
  every alarm (vacation, also from the Quick Settings tile), switching its group or profile off, deleting its group, adding
  the day as an exception, switching calendar exceptions on, restoring a backup and the "While an alarm rings" settings that
  write onto every alarm are refused, as switching the alarm off, skipping, editing or deleting it already were, by the
  app, a widget or a notification. Each says the same thing. Anything that only brings alarms back (ending a vacation,
  switching a group on, removing an exception day) always works
- **The window can be up to 12 hours**, so it can cover the night before; a lock is shown in hours and minutes. Restored
  backups keep windows up to 12 hours (they were cut to 2)
- **A just-saved alarm stays editable for 10 minutes**, so saving one inside its own window does not lock a typo in
- **While an alarm with a mission rings or is snoozed it cannot be changed at all**, even with no window: deleting or
  switching off a snoozed alarm used to end it without the mission. The sums that unlock the editor are not offered then;
  the ring itself is how it ends. The engine also refuses to close such a ring as dismissed when it is changed from outside
- An alarm set to be unlocked by its own mission (a setting the levels will use) asks for that mission instead of the sums
- **"Still awake?" can ask for proof** (a per-alarm setting the levels will use): its notification has no buttons, it
  cannot be cancelled, and its screen asks for one round of Focus Tap instead of a button. If the phone's step counter has
  counted 30 steps since you dismissed, it passes on its own without asking. It comes up to 3 minutes later than set, at
  random, and plays a tone on the alarm stream while it waits, so it is heard on a phone set to silent. A ring it causes
  asks for the next mission when missions change each morning. Shaking the phone may fool the step counter
- Checks without proof are unchanged

## The alarm comes back, the volume stays up, and the ring screen is easier half asleep

The engine and the ring screen now do all of this. Some of it depends on per-alarm settings that the alarm editor gets
with the new levels; until then an existing alarm behaves as before except where said.

- **Leaving the alarm screen while it rings brings it back** for an alarm set to come back at once: Home, Recents, swiping
  the card away, Clear all, split screen, or a ring that came back after the app was killed or the phone restarted. It
  returns about a second later, then backs off a little if you keep leaving. Android allows this only with "Display over
  other apps", which Alarm Health now shows with a shortcut when an alarm needs it; nothing is drawn over other apps. Without
  it, and for every other alarm, the notification reappears as before. It never covers a call, a call ringing in, an
  Emergency pause, the lock screen's Emergency call or a permission dialog a mission opened
- **Recents now really brings the alarm back**, found on a Pixel 6 with gesture navigation: the Overview left the alarm
  screen running behind it, so the app thought it was still in front and never brought it back, while its notes said it
  had. The app now tells the Overview apart (and still leaves the notification shade, the power menu and its own dialogs
  alone), brings the screen back from Home or Recents in about 1.2 seconds, records a return only once the screen is really
  back, and keeps one alarm card per ring. Showing the alarm screen for the first time no longer counts towards the backoff
- **Opening the app while an alarm rings shows the alarm**, not the alarm list
- **Switching the screen off** while an alarm rings lights it again after 3 seconds. Pressing the power button quickly
  several times (SOS) is left alone for a minute
- **The ring screen can no longer share the screen with another app** on a phone (large screens may ignore this)
- **The volume can no longer be turned down** below an alarm's floor, by the keys, the shade slider or Settings, for an
  alarm with a floor or set to the maximum system volume, and for a ring back from a restart; on headphones the hearing
  cap still wins
- **What the volume keys do is kept per alarm.** The setting under "While an alarm rings" now sets it for every alarm.
  Silencing with a key spends the alarm's mutes
- **Mute during a mission**: every alarm with a mission gets a few short mutes per ring (3 of 30 seconds unless the alarm
  says fewer), shown with what is left. A mute ends early after 20 seconds without progress in the mission, touching the
  screen does not keep it going, and a restart neither brings it back nor gives the mutes back
- **The ring screen**: the mission button comes first and biggest, snooze below it and smaller. An alarm can ask for full
  brightness while it rings (your brightness setting is not changed) and for touches not to calm it down, only progress
  in the mission. Screenshots are blocked on alarms whose way out opens late. "You're up" shows for a moment after you
  dismiss, saying whether a still-awake check follows
- An alarm that must play on the phone speaker is checked against where the sound really goes

## No more Continue: a backup mission, a way out that takes effort, and Emergency on every ring

- **A mission the phone cannot do is no longer passed with Continue.** It is replaced by a backup mission of short tasks
  (Focus Tap, typing a phrase, simple sums, in turn): 3 to 8 steps when the phone is at fault (no hardware, never set up, a
  setup that cannot be read, a camera that fails, a sensor that says nothing for 30 seconds), and the longer way-out length
  when it could be a choice (camera permission refused, NFC off), offered next to the fix. Refusing the camera and tapping
  Continue used to pass a mission in two taps
- **A camera that shows only black** (a covered or broken lens) is noticed: after 15 seconds the flashlight is switched
  on, and after a minute of nothing the backup mission takes over. A dark room the flashlight lights up is not affected
- **The way out without the mission** replaces the Emergency stop. By default it is a 10-second hold (it was 3 seconds, and
  the hold time setting is gone). A strict alarm (Emergency stop switched off) now opens its way out only after a few
  minutes of ringing (3 for alarms that were strict before), and then asks for 10 tasks; the progress survives a restart.
  An alarm that ends with one tap has no hold, since there is nothing to skip. Both are still recorded as an emergency stop
- **Emergency** is on every ring and mission screen, whatever the alarm: it pauses the alarm for 3 minutes and opens the
  phone app, or on a locked phone the unlock screen with its Emergency call button. It does not stop the alarm. The first two
  pauses of a ring are silent, later ones keep the sound on unless a call is going on. A phone call, or one ringing in,
  keeps the alarm quiet for as long as it lasts; a call in an app for at most 10 minutes per ring
- **If the alarm screen keeps crashing**, a plain screen takes over instead of the notification buttons: an alarm with a
  mission is ended there with a few sums, never with one tap from the notification
- Pauses, backup missions and way-out tasks are counted in the history

## A new mission, and a different one each morning

- **Focus Tap**: shapes come and go on a 3 x 3 board and you tap only the one you are asked for. A wrong tap starts the
  round again, the shape to find changes every round, and neither its colour nor its shape alone gives it away. With a
  screen reader nothing is timed: each shape is announced and you answer "That's it" or "Not this one"
- **"Change the mission each morning"** (shown once an alarm has two or more missions): each ring asks for one of them, the
  next in turn, so no mission is asked for two mornings running. A snooze, a backup or a restart asks for the same one; a
  still-awake re-ring moves on to the next. With several codes in different rooms, the ring screen says which one
- The mission picker lists a code in another room first and says honestly which missions wear off or can be faked
- Older versions skip a Focus Tap mission in a backup instead of refusing the file

## Hear about it the night before, not the morning after

- **About 8 hours before an alarm (and with the bedtime reminder) WakeNudge checks that it will really work**: its trigger,
  notifications, the full-screen alarm, exact alarms, background restriction, Do Not Disturb, the battery, and every
  mission the alarm has (camera access, NFC on, a code or photo set, the sensor). If all is well you see nothing. If not,
  one notification names the problem and has a button that fixes it
- A lost alarm trigger is put back silently, then once more half an hour before the alarm. That is all the wake-ups a
  night costs
- **When an alarm rang late or not at all, the next start says why** where it can tell: the app was force stopped, the phone
  was off, Android closed the app or delivered it late, the phone restarted or the app was stopped while it rang, or a
  backup sound was used. Alarm Health lists the last week of these
- The low-battery switch on the Sleep screen is now "Warn me the night before if an alarm might fail" and covers all of this

## Restarting the phone no longer pays

- **How many restarts a ring survives is now per alarm** (3 by default, no more than 20). Past that, or past its window, it
  is recorded as missed and you are told
- **A ring that comes back after a restart is at full volume at once, with no fade-in and no snooze**, and touching the screen
  does not calm it. Hearing protection on headphones still applies
- **An alarm due while the phone was off rings when it comes back** within 10 minutes or within the alarm's own window, whichever
  is longer, and is reported as missed after that. This now also works if the database cannot be read
- The database moves to version 5 and backups stay at format 1. Existing alarms keep their behaviour. A restored backup never
  brings along an alarm with its way out removed: it gets one again, and you are told

## A ring that survives what happens to the phone

- **A ring now outlives its process.** What it must not lose (the alarm as it fired, the snooze count, the missions done) is
  saved outside the database, so being killed, a database failure or a restart cannot soften it. After a restart it rings
  again if it was unresolved, up to 3 times per ring and within the alarm's window, then it is recorded as missed and you
  are told
- An alarm set to ring until it is dealt with also gets a sentinel trigger every 30 seconds, so Stop in Active apps brings
  the sound back within about half a minute, and it keeps being rescued through a crash loop instead of giving up after five
- Force stop stays silent until the app is next opened, and then the alarm rings again, within its window (or an hour);
  found later than that, it is reported as missed
- Two alarms at once: the one waiting survives a kill too, and both ring in turn
- A strict alarm whose database cannot be read when it fires still rings with its missions, from a saved copy
- The ring service runs as `systemExempted` on Android 14 and newer, which unlike `mediaPlayback` may start right after a
  restart, and falls back if the system refuses it
- A ring's timeout and a temporary silence count real time, so changing the clock neither ends nor extends them
- Restoring a backup is refused while an alarm is ringing or waiting to come back

## One rule for ending a ring

- **Every way to end or quiet a ring now goes through one decision.** A notification action, a volume key or a button left
  from an earlier ring proves nothing, so none of them can dismiss or snooze an alarm that has a mission: the notification's
  Snooze on such an alarm now opens the ring screen, and its volume key does nothing. Alarms with no mission and no hold
  behave as before
- A dismissal records the missions really done, counted one at a time, instead of assuming all of them
- The database moves to version 4 and backups stay at format 1. Every new per-alarm setting defaults to what alarms do
  today, and your volume-key and keep-in-front choices are copied onto each alarm once. A strict alarm (Emergency stop off)
  also gets a way out starting 3 minutes into the ring; nothing uses that yet
- Known downgrade edge: an older app restoring a backup from this one turns a "Never" ring timeout into 1 minute

## An alarm that rings until it is dealt with

- **"Stop ringing after" can now be Never**: step below 1 min and an ignored alarm keeps ringing until you dismiss or
  snooze it. Backups still upgrade it while it rings. New alarms stay at 10 minutes; timers and the test alarm keep
  their own short timeouts, and backups restore a Never alarm as Never
- The ring service now renews its wake lock while it rings; it used to lapse after 45 minutes

### Fixed
- A snoozed alarm stayed silent if the phone restarted (or the app was force-stopped) more than an hour after the
  alarm first rang: start-up housekeeping closed the ring as missed, so the restored snooze found nothing to ring
- An ignored "Still awake?" re-ring could ring again hours later, when the next reschedule re-armed a deadline that had
  already fired. It now ends the check as failed once nothing is left to ring, instead of leaving it pending for good
- "Wake now" on a snoozed test alarm did nothing and left its notification stuck, and with an unreadable history it
  cancelled a real snooze without ringing
- Dismissing a snoozed alarm from its notification skipped the still-awake check and did not delete a delete-after-use alarm
- A timer stayed silent if the database could not be read or written when it finished

## One app, published as an APK

- **There is now one WakeNudge.** The separate Google Play build is gone, and releases carry only the signed APK
  (`WakeNudge-<version>.apk`, also as `WakeNudge.apk`), its R8 mapping and the checksums; no more `.aab`
- Every install has the optional update check (About, Updates) and holds `INTERNET` for it alone; switch it off and
  WakeNudge never goes online. No other network permission is allowed, and the build still enforces that
- Finishing onboarding now looks for an update straight away, so someone who installs an old APK is offered the new
  one at once instead of on their next visit

## Easier to use, half asleep or not

A review of every screen, as a groggy, rushed or screen-reader user meets it. What changed:

### Waking up
- Above a mission the ring screen keeps to one slim row (Back, the time, the label), so the maths keypad's bottom row
  no longer falls below the screen on a phone of ordinary size; the problem and keys shrink to fit
- A wrong maths answer stays on screen in red with "Not quite, try again"; an empty ✓ no longer buzzes
- The snoozed notification no longer offers Dismiss for an alarm that needs a mission or a hold
- The Quick Settings tile asks before it pauses every alarm; the widget offers "Undo skip", and its "Turn off" for a
  one-time alarm takes a second tap
- The ring buttons, the slider and the "Still awake?" screen grow and scroll with large fonts and in landscape; the
  typing mission's keyboard no longer covers it; a failed camera's Continue stays reachable
- NFC switched off and not switched on from the settings now offers Continue; with TalkBack the memory sequence is
  spoken tile by tile and pattern dots can be tapped in order; the photo meter no longer talks over itself
- Ring badges such as "Snoozed 2 times" are readable instead of faint, and notifications read well without a label

### Setting up
- Deleting from the editor really offers Undo on the list, as its dialog promised
- "Only this" profile and Duplicate say what they did (with Undo and Edit); a copy of a switched-off alarm stays off
- Removing a mission offers Undo; its move and remove buttons no longer squeeze its name
- Date pickers start at today, the vacation picker has Cancel, and "This alarm will not ring" is shown as a problem
- Seven weekday chips fit a 360 dp phone; long sound names, long labels and large fonts no longer crush setting rows,
  cards or the onboarding permissions page, which now scrolls and shows the battery setting as done
- TalkBack hears the whole alarm card (label, status, missions) and which alarm or profile a switch belongs to, the
  severity of each Alarm Health item and the health badge; counts use proper plurals ("1 problem")
- Settings pages no longer flash default values before the stored ones; the test alarm's time follows the app's
  12/24-hour choice; backup results are announced and scrolled into view, with plain words for errors

### Clock tools
- Spoken times keep their seconds ("1 minute, 30 seconds"); a new timer's button says Start; deleting a running timer
  asks first; the new-timer sheet and the stopwatch scroll in landscape; fastest and slowest laps are named
- World clock cards keep the city readable on small phones; Plan ahead moves in whole hours with one tap back to now
- "Remind me" in the sleep planner shows which bedtime is set; the morning chart marks unclean mornings by shape too

## Real-life audit

Every part of the app was read again as a half-awake user would break it. What changed:

### Easier wake-up missions
- **Math is much easier at every level.** Easy is like 7 + 5 or 9 − 4, medium (the default) 27 + 6 or 7 × 4, hard
  38 + 47 or 7 × 8 + 23. The editor shows a sample question under the difficulty
- The sliding puzzle is always 3 × 3 (hard was a fully shuffled 15-puzzle); the memory game starts one tile shorter and
  plays a little slower; typing phrases are shorter, medium has no punctuation, and curly quotes or long dashes typed by
  the keyboard count as the plain ones
- The photo mission still matches a spot photographed under a warm lamp when the alarm rings in daylight
- Pattern tracing never asks for a "knight's jump" line that grazes two other dots

### Fixed: alarms that could fail to ring
- After a **force-stop** (or a phone that "cleans" swiped-away apps) Android forgot every alarm and nothing put them
  back until a reboot. The app now re-arms everything the next time it starts
- Switching on one alarm of a switched-off group turned the group on but armed only that one alarm
- After a reboot, an alarm due in the second between re-arming and the missed-alarm check was lost; after an app
  update, an alarm due during the install was never rung or reported
- Deleting a switched-off group made its alarms ring
- A ring that the backup alarm had just taken over could be ended by the ring timeout due in the same second
- On Android 12 with "Alarms & reminders" switched off, alarms could ring up to an hour late while Alarm Health said
  they were not affected; it is now an error, with a notification
- A camera that is refused, broken or busy no longer leaves a QR or photo mission with nothing to press: Continue appears

### Fixed: the wrong thing happening
- A typed time could be saved as a different one (06:45 typed over 07:00 became 06:55): the wheels reported the rows
  they glided past. A locked editor's wheels no longer turn
- Saving in an editor opened before the alarm rang turned a finished one-time alarm back on, undid a skip, or brought
  back a deleted alarm. Double-tapping Save created two alarms
- "Skip next" tapped twice (widget, notification) skipped the following day instead
- A Stop or Dismiss that arrived just after another ring took over ended that ring; volume keys during the silent
  sunrise glow could snooze, or delete a delete-after-use alarm before it rang
- A second alarm waiting behind the first played the first alarm's sound after the first was dismissed
- Rotating the phone or tapping Back in a chain of missions started the chain over; in landscape on a large screen
  the mission area had no height at all
- Restoring a backup could bring back an old alarm's snooze and ring a restored alarm out of nowhere
- Times read backwards (05:10 as 01:50) on phones set to right-to-left languages

### New
- **Low-battery warning:** about 8 hours before an alarm, if the battery is at 20 % or less and not charging, a
  notification says to plug the phone in (Sleep, then "Warn me when the battery is low")
- Switching an alarm on says when it rings ("Rings in 9 h 40 min"), so a 7:00 PM slip is caught at once
- "Pause all" can be undone from the snackbar
- Alarm Health also checks the backup alarm sound channel and Do Not Disturb modes that mute alarms

## Initial public release (1.0.x)

### Alarms
- Unlimited alarms with labels, repeat days, one-time dates, groups/profiles, skip next ring, delete after use
- Alarm options are grouped into six collapsible categories with a live summary each: wake-up challenge, sound and
  light, volume and vibration, snooze, oversleep protection and schedule rules. "New alarm defaults" uses the same groups
- **Strict alarms:** snooze has a switch right on its card, and the Emergency stop (on by default) can be switched off
  per alarm, after a confirmation. Missions the phone cannot perform still offer Continue, and the ring timeout still
  ends the alarm
- Vacation mode and holiday/calendar exceptions in one place, upcoming-alarm heads-up, bedtime reminders and a sleep planner
- Timer, stopwatch with laps, world clock, live countdown, three home-screen widgets and a Quick Settings tile
- Settings is a short grouped hub (alarms, ringing and sound, look and feel, reliability and backup, about)
- Local backup and restore to a user-chosen JSON file, with validation and a confirmation before anything is replaced

### Sound
- Tapping a tone in the picker plays it immediately; the preview never outlives the picker
- 12 selectable tones from gentle to loud (plus two internal ones), your own music, random tone per ring
- Volume ramp up to 10 minutes, a sound that intensifies after 40 s, hearing-safety cap on headphones
- **Extra Loud is a strength slider** (Off to 100 %); it is never applied on headphones while hearing protection is on
- Strong vibration patterns including a recorded rhythm, offline spoken announcements, sunrise mode, backup alarm

### Missions and wake-up check
- Math, memory, typing, QR/barcode, NFC, shake, steps, squats, puzzles (three kinds) and photo matching
- Configurable difficulty and count, chaining, previews, Solve-to-Snooze
- "Still awake?" check with countdown, re-ring and repeat limits

### Reliability
- Exact `setAlarmClock` scheduling, `USAGE_ALARM` audio, foreground service and full-screen lock-screen UI
- Direct Boot, reboot/update/time-zone/DST handling, persisted snooze, backup rings and wake-check
- Hardened against audio-focus loss, dying players and services, ring-screen crashes (with a rescue ring), refused alarm
  registration, full storage, corrupt settings, wall-clock jumps and refused foreground-service starts
- Alarm Health screen with diagnostics, fixes and a real test alarm; morning dashboard with history and streaks
- Room database migrations are explicit and tested; there is no destructive fallback

### Battery
- Widgets refresh on events instead of a 30-minute wakeup per widget; one non-waking alarm covers the date rolling over

### Two builds
- `play`: no network permission and no update code
- `github` (the APK on GitHub Releases): holds only `INTERNET`, used for an optional update notice that checks GitHub at
  most once an hour and can be switched off in About. The Welcome screen, About and Settings link to the source code and the website

### Design
- Material You dynamic colour, light, dark and AMOLED themes, expressive shapes and spring motion, edge-to-edge
- An original adaptive icon, onboarding and settings

### Quality and delivery
- Hundreds of JVM tests (including seeded fuzz tests) for both builds and 130+ instrumented tests, with real-world abuse suites
- Build-time guard for network permissions per build; R8-minified release builds verified on a device
- Every push to `main` builds, verifies and publishes a signed APK and AAB (see [docs/RELEASING.md](docs/RELEASING.md))
- See [docs/TESTING.md](docs/TESTING.md) for what was and was not verified on hardware
