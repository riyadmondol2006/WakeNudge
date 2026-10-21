#!/bin/bash
# On-device battery measurement for WakeNudge (idle wakeups, widget updates, cold-start cost). Read-only on the phone
# apart from installing a PRIVATE copy of the debug app (com.riyadm.wakenudge.batmeasure) that nothing else touches.
# It never resets batterystats, forces Doze or changes system settings.
#
#   PADB=/path/to/pinned-adb-wrapper tools/battery/measure.sh <command> ...
#
#   build OUTDIR           build the play-debug app + test APK of this checkout (or $WAKENUDGE_ROOT) under the private id
#                          (needs JAVA_HOME; wrap in your own gradle lock if you share the machine)
#   setup APK TESTAPK      install both, clear data, bind the three widgets to a throw-away host, seed 4 alarms + a timer
#   snap LABEL             read-only snapshot (alarms, jobs, broadcasts, process starts, battery) into $OUT/LABEL
#   cold N                 N cold starts of the process by a no-op broadcast; prints CPU ticks (10 ms) at +4 s and +12 s
#   report LABEL_A LABEL_B what happened between two snapshots: process starts by reason, jobs, wakeup alarms registered
#   cleanup                uninstall the private app: removes its alarms, timer and widgets
#
# A typical before/after run: build + setup the baseline (git checkout of the commit before the change), `snap before`,
# turn the screen off, wait 60+ minutes, `snap after`, `report before after`; repeat with the new build.
# Plugged in, batterystats does not accumulate, so everything here is counted from AlarmManager, JobScheduler, the
# activity manager's event log and /proc, none of which depend on the charging state.
set -u
PKG=com.riyadm.wakenudge.batmeasure
: "${PADB:?set PADB to the adb wrapper pinned to the phone under test}"
OUT=${OUT:-$HOME/.cache/wakenudge-battery/dumps}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=${WAKENUDGE_ROOT:-$(cd "$HERE/../.." && pwd)}   # build another checkout (e.g. a baseline worktree) by pointing this at it

cmd=${1:-}; shift || true
case "$cmd" in
build)
  out=${1:?output directory}
  mkdir -p "$out"
  harness=$ROOT/app/src/androidTest/java/com/riyadm/wakenudge/battery
  mkdir -p "$harness"
  cp "$HERE/WidgetHostHarness.kt.txt" "$harness/WidgetHostHarness.kt"
  trap 'rm -rf "$harness"' EXIT
  (cd "$ROOT" && ./gradlew -I "$HERE/appid.init.gradle" :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest --console=plain -q) || exit 1
  cp "$ROOT/app/build/outputs/apk/play/debug/app-play-debug.apk" "$out/app.apk"
  cp "$ROOT/app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk" "$out/test.apk"
  ls -la "$out"
  ;;
setup)
  apk=${1:?app apk}; test_apk=${2:?test apk}
  $PADB install -r -t -d "$apk" >/dev/null && $PADB install -r -t -d "$test_apk" >/dev/null || exit 1
  sleep 3
  $PADB shell pm clear $PKG >/dev/null
  # Binding goes through an instrumentation run, and the system force-stops the package when one finishes, which would
  # drop alarms registered before it: bind first, seed after.
  $PADB shell am instrument -w -r -e class com.riyadm.wakenudge.battery.WidgetHostHarness#bind $PKG.test/com.riyadm.wakenudge.HiltTestRunner | grep -E "^OK|FAIL"
  sleep 2
  A=$PKG/com.riyadm.wakenudge.MainActivity
  alarm() { # hour minute label days(csv, may be empty). Labels must not contain spaces: adb flattens the quoting.
    local extra=()
    [ -n "$4" ] && extra=(--eial android.intent.extra.alarm.DAYS "$4")
    $PADB shell am start -n $A -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR "$1" \
      --ei android.intent.extra.alarm.MINUTES "$2" --es android.intent.extra.alarm.MESSAGE "$3" "${extra[@]}" \
      --ez android.intent.extra.alarm.SKIP_UI true >/dev/null
    sleep 2
  }
  alarm 6 30 Work 2,3,4,5,6
  alarm 7 0 Daily 1,2,3,4,5,6,7
  alarm 8 15 Weekend 1,7
  alarm 12 0 GymMWF 2,4,6
  alarm 17 45 TueThu 3,5
  alarm 22 30 OneOff ""
  $PADB shell am start -n $A -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 86400 \
    --es android.intent.extra.alarm.MESSAGE LongTimer --ez android.intent.extra.alarm.SKIP_UI true >/dev/null
  sleep 2
  $PADB shell input keyevent KEYCODE_HOME
  echo "seeded. Alarms ring for real: run '$0 cleanup' when done."
  ;;
snap)
  d=$OUT/${1:?label}; mkdir -p "$d"
  $PADB shell 'date +%s; cat /proc/uptime' > "$d/device_clock.txt"
  $PADB shell dumpsys alarm > "$d/alarm.txt"
  $PADB shell dumpsys jobscheduler > "$d/jobscheduler.txt"
  $PADB shell dumpsys activity broadcasts > "$d/broadcasts.txt" 2>&1
  $PADB shell dumpsys appwidget > "$d/appwidget.txt"
  $PADB shell dumpsys power > "$d/power.txt"
  $PADB shell dumpsys battery > "$d/battery.txt"
  $PADB shell "logcat -b events -d -v epoch -t 30000" 2>/dev/null | grep -E "$PKG" > "$d/events.txt"
  $PADB shell "ps -A -o PID,UID,TIME+,ARGS" | grep "$PKG" > "$d/ps.txt"
  $PADB shell am get-standby-bucket $PKG > "$d/bucket.txt"
  echo "snapshot $1 saved in $d"
  ;;
cold)
  n=${1:-5}
  for i in $(seq 1 "$n"); do
    for _ in 1 2 3 4 5; do [ -z "$($PADB shell pidof $PKG)" ] && break; $PADB shell am kill $PKG; sleep 2; done
    $PADB shell am broadcast -n $PKG/com.riyadm.wakenudge.engine.SystemEventsReceiver -a com.riyadm.wakenudge.test.PING >/dev/null
    line="run $i:"
    for s in 4 8; do
      sleep "$s"
      pid=$($PADB shell pidof $PKG | awk '{print $1}')
      line="$line  at +$((s == 4 ? 4 : 12))s: $([ -n "$pid" ] && $PADB shell cat /proc/"$pid"/stat | awk '{print $14+$15 " ticks"}' || echo dead)"
    done
    echo "$line"
  done
  ;;
report)
  python3 -I - "$OUT/${1:?first label}" "$OUT/${2:?second label}" "$PKG" <<'PY'
import re, sys
a, b, pkg = sys.argv[1:4]

def read(d, name):
    try:
        return open(f"{d}/{name}", errors="replace").read()
    except OSError:
        return ""

clock_a = int(read(a, "device_clock.txt").split()[0])
clock_b = int(read(b, "device_clock.txt").split()[0])
print(f"window: {(clock_b - clock_a) / 60:.1f} minutes of device time")

starts = {}
for line in read(b, "events.txt").splitlines():
    m = re.search(r"(\d+)\.\d+\s.*am_proc_start: \[\d+,\d+,\d+,%s,([^,]+)," % re.escape(pkg), line)
    if m and clock_a <= int(m.group(1)) <= clock_b:
        starts[m.group(2)] = starts.get(m.group(2), 0) + 1
print("process starts by reason:", starts or "none", "(total %d)" % sum(starts.values()))

jobs = [l for l in read(b, "jobscheduler.txt").splitlines() if re.search(r"\s(START|STOP)(-P)?:", l) and pkg in l]
print("job START/STOP lines mentioning the app in the retained history:", len(jobs))

def pending(d):
    t = read(d, "alarm.txt")
    blocks = re.split(r"\n(?=    (?:RTC_WAKEUP|RTC|ELAPSED_WAKEUP|ELAPSED) #\d+:)", t)
    rows = []
    for blk in blocks:
        head = blk.split("\n")[0]
        tag = re.search(r"tag=(\S+)", blk)
        if pkg in head and tag:
            rows.append((re.search(r"type=(\S+)", blk).group(1), tag.group(1).split(":", 1)[-1],
                         (re.search(r"repeatInterval=(\d+)", blk) or [0, "0"])[1]))
        elif "APPWIDGET_UPDATE" in blk and head.rstrip().endswith("android}"):
            rows.append((re.search(r"type=(\S+)", blk).group(1), "system:APPWIDGET_UPDATE (all apps)",
                         (re.search(r"repeatInterval=(\d+)", blk) or [0, "0"])[1]))
    return rows

for label, d in (("first", a), ("second", b)):
    rows = pending(d)
    print(f"pending alarms in the {label} snapshot ({len(rows)}):")
    for typ, tag, rep in sorted(rows):
        print(f"   {typ:15s} {tag}  repeatInterval={rep}")
PY
  ;;
cleanup)
  $PADB shell am instrument -w -r -e class com.riyadm.wakenudge.battery.WidgetHostHarness#unbind $PKG.test/com.riyadm.wakenudge.HiltTestRunner >/dev/null 2>&1
  $PADB shell pm uninstall $PKG.test
  $PADB shell pm uninstall $PKG
  ;;
*)
  sed -n '2,22p' "$0"
  exit 2
  ;;
esac
