#!/usr/bin/env bash
# Opens Fold Widgets' settings and its widget previews in the four shapes of the Fold: the cover and
# the main screen, each upright and turned. The glass in dp, from Mechack's screenshots: cover about
# 411 x 960, main about 859 x 896. The emulator stops at 1920 px, so density is lowered to keep the dp.
set +e
PKG=com.fergusonconsultancy.foldwidgets
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
sleep 30
adb install -r "$APK"
adb logcat -c
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }
open() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.SettingsActivity ${1:+--es open $1} >/dev/null; sleep 6; }
shape() {  # name WxH dpi
  adb shell wm density $3; adb shell wm size $2; sleep 4
  open; shot "$1-settings"
  adb shell input swipe 300 1500 300 300 300; sleep 0.6; adb shell input swipe 300 1500 300 300 300; sleep 1.5
  shot "$1-settings-lower"
  open preview; shot "$1-preview"
  # Meridiana's rows sit below the other widgets
  local sz=${2#*x}
  for n in 1 2 3 4; do adb shell input swipe 300 $((sz*4/5)) 300 $((sz/6)) 400; sleep 1.2; shot "$1-preview-$n"; done
}
shape 1-cover-portrait 823x1920 320
shape 2-cover-landscape 1920x823 320
shape 3-main-portrait 1503x1568 280
shape 4-main-landscape 1568x1503 280
adb shell wm size reset; adb shell wm density reset
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt | grep -E "AndroidRuntime" | head -40 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do echo "::error::$line"; done
  exit 1
fi
grep "FoldWidgets" logcat.txt | grep -i " E " | head -20 | while IFS= read -r l; do echo "::warning::$l"; done
echo "::notice::Fold Widgets opened in every shape without a crash"
exit 0
