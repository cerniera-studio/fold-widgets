#!/usr/bin/env bash
# Windows (adaptive quality, 19 Nov 2026; Sveglia's tools/windows.sh is the template). Continuity of
# the settings page on the main screen's shape (859 x 896 dp). Results in shots/windows.txt; a line
# starting ::error:: fails the run.
#
# 1. A town typed into the weather search survives a turn (the page is rebuilt).
# 2. ...and leaving for the home screen and coming back (rebuilt again, on resume).
# 3. ...and a pop-up window being shrunk (the whole activity is rebuilt, from its saved state).
# Before Fold Widgets PR #10, each of these emptied the search box.
#
# Pop-up windows need freeform support, which the emulator only reads at boot: this reboots it,
# so it runs after the launch test.
PKG=com.fergusonconsultancy.foldwidgets
mkdir -p shots
out=shots/windows.txt; : > $out
note() { echo "::notice::$*" | tee -a $out; }
fail() { echo "::error::$*" | tee -a $out; }
warn() { echo "::warning::$*" | tee -a $out; }
shot() { adb exec-out screencap -p > "shots/win-$1.png"; }

adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
sleep 30
adb shell input keyevent 82
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c

# The main screen, upright
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3

task() {  # "<id> <mode>" of the task whose affinity is exactly $1
  adb shell dumpsys activity activities | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.search(r'\* Task\{\S+ #(\d+) .*?A=\d+:(\S+) .*?mode=(\S+)', line)
    if m and m.group(2) == '$1': print(m.group(1), m.group(3)); break"
}
# What the weather search holds, from the app's own record (debug builds write files/search.json
# after every rebuild). The settings page shows live widget previews that never stop moving, so
# the screen reader can't read it. Clear the record before an event, then wait for the rebuild.
clear() { adb shell run-as $PKG rm -f files/search.json; }
held() {
  local r=""
  for try in 1 2 3 4 5 6 7 8 9 10; do
    r=$(adb shell run-as $PKG cat files/search.json 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['text'])" 2>/dev/null)
    [ -n "$r" ] && break; sleep 1
  done
  echo "$r"
}
check() {  # what happened, expected words
  local h=$(held)
  if [ "$h" = "$2" ]; then note "Weather search: '$2' survived $1"
  else fail "Weather search: $1 emptied the box ('${h:-nothing recorded}')"; fi
}

# ---- 1. Across a turn, and 2. away and back (full screen)
adb shell am force-stop $PKG; clear
adb shell am start -W --windowingMode 1 -n $PKG/.SettingsActivity --es search Lisbon >/dev/null; sleep 6
if [ "$(held)" != Lisbon ]; then
  warn "Weather search: the test words didn't arrive ('$(held)'); checks 1 and 2 skipped"
else
  shot search-typed
  clear; adb shell settings put system user_rotation 1; sleep 5; shot search-turned
  check "the turn" Lisbon
  clear; adb shell settings put system user_rotation 0; sleep 4; held >/dev/null
  clear; adb shell input keyevent KEYCODE_HOME; sleep 3
  adb shell am start -W -n $PKG/.SettingsActivity >/dev/null; sleep 5; shot search-back
  check "leaving and coming back" Lisbon
fi
adb shell am force-stop $PKG; sleep 2

# ---- 3. A pop-up window shrunk (the activity is rebuilt from its saved state)
clear
adb shell am start -W --windowingMode 5 -n $PKG/.SettingsActivity --es search Leeds >/dev/null; sleep 5
set -- $(task $PKG); id=$1; mode=$2
echo "task: $id $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "Pop-up window: the emulator would not open one (mode ${mode:-none}); check 3 skipped"
elif [ "$(held)" != Leeds ]; then
  warn "Weather search in a pop-up window: the test words didn't arrive; skipped"
else
  # Widen first, so that shrinking is a real change of size
  adb shell am task resize "$id" 0 0 1503 1568; sleep 5; shot search-popup-wide
  clear; adb shell am task resize "$id" 0 0 740 1568; sleep 6; shot search-popup-narrow
  check "shrinking a pop-up window" Leeds
fi
adb shell am force-stop $PKG
adb shell wm size reset; adb shell wm density reset

adb logcat -d > shots/logcat-windows.txt
if grep -A2 "FATAL EXCEPTION" shots/logcat-windows.txt | grep -q "Process: $PKG"; then fail "Fold Widgets crashed in the windows test"; grep -A20 "FATAL EXCEPTION" shots/logcat-windows.txt | head -40; fi
grep -q "^::error::" $out && exit 1
exit 0
