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
# The search box: "<text>|<l> <t> <r> <b>" if it is on screen (an empty box reads as its hint)
box_once() {
  adb shell rm -f /sdcard/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, re
for m in re.finditer(r'<node [^>]*>', sys.stdin.read()):
    n = m.group(0)
    if 'class=\"android.widget.EditText\"' in n and 'package=\"$PKG\"' in n:
        t = re.search(r' text=\"([^\"]*)\"', n).group(1)
        b = re.findall(r'\d+', re.search(r'bounds=\"([^\"]*)\"', n).group(1))
        print(t + '|' + ' '.join(b)); break"
}
box() { local r=""; for try in 1 2 3; do r=$(box_once); [ -n "$r" ] && break; sleep 1; done; echo "$r"; }
# Scroll down until the search box is in view (from x, the middle of the window's left part)
find_box() {
  local r=$(box)
  for n in 1 2 3 4 5 6; do
    [ -n "$r" ] && break
    adb shell input swipe 300 1300 300 500 400; sleep 1.2; r=$(box)
  done
  echo "$r"
}
typed() { local r=$1; echo "${r%%|*}"; }
type_into() {  # box, words
  set -- ${1#*|} "$2"
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 1
  adb shell input text "$5"; sleep 1
}

# ---- 1. Across a turn, and 2. away and back (full screen)
adb shell am force-stop $PKG
adb shell am start -W --windowingMode 1 -n $PKG/.SettingsActivity >/dev/null; sleep 6
b=$(find_box)
if [ -z "$b" ]; then
  warn "Weather search: the box never came into view; checks 1 and 2 skipped"
else
  type_into "$b" Lisbon
  b=$(box); shot search-typed
  if [ "$(typed "$b")" != Lisbon ]; then
    warn "Weather search: typing gave '$(typed "$b")', not Lisbon; checks 1 and 2 skipped"
  else
    adb shell settings put system user_rotation 1; sleep 5; shot search-turned
    b=$(box); place="kept its place on the page"
    [ -z "$b" ] && { b=$(find_box); place="had to be scrolled back to"; }
    if [ "$(typed "$b")" = Lisbon ]; then note "Weather search: 'Lisbon' survived the turn (the box $place)"
    else fail "Weather search: the turn emptied the box ('$(typed "$b")')"; fi
    adb shell settings put system user_rotation 0; sleep 4

    adb shell input keyevent KEYCODE_HOME; sleep 3
    adb shell am start -W -n $PKG/.SettingsActivity >/dev/null; sleep 5; shot search-back
    b=$(find_box)
    if [ "$(typed "$b")" = Lisbon ]; then note "Weather search: 'Lisbon' survived leaving and coming back"
    else fail "Weather search: coming back emptied the box ('$(typed "$b")')"; fi
  fi
fi
adb shell am force-stop $PKG; sleep 2

# ---- 3. A pop-up window shrunk (the activity is rebuilt from its saved state)
adb shell am start -W --windowingMode 5 -n $PKG/.SettingsActivity >/dev/null; sleep 5
set -- $(task $PKG); id=$1; mode=$2
echo "task: $id $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "Pop-up window: the emulator would not open one (mode ${mode:-none}); check 3 skipped"
else
  adb shell am task resize "$id" 0 0 1503 1568; sleep 5
  b=$(find_box)
  if [ -z "$b" ]; then
    warn "Weather search in a pop-up window: the box never came into view; skipped"
  else
    type_into "$b" Leeds; shot search-popup-wide
    if [ "$(typed "$(box)")" != Leeds ]; then
      warn "Weather search in a pop-up window: typing didn't take; skipped"
    else
      adb shell am task resize "$id" 0 0 740 1568; sleep 6; shot search-popup-narrow
      b=$(find_box)
      if [ "$(typed "$b")" = Leeds ]; then note "Weather search in a pop-up window: 'Leeds' survived shrinking it"
      else fail "Weather search in a pop-up window: shrinking it emptied the box ('$(typed "$b")')"; fi
    fi
  fi
fi
adb shell am force-stop $PKG
adb shell wm size reset; adb shell wm density reset

adb logcat -d > shots/logcat-windows.txt
if grep -A2 "FATAL EXCEPTION" shots/logcat-windows.txt | grep -q "Process: $PKG"; then fail "Fold Widgets crashed in the windows test"; grep -A20 "FATAL EXCEPTION" shots/logcat-windows.txt | head -40; fi
grep -q "^::error::" $out && exit 1
exit 0
