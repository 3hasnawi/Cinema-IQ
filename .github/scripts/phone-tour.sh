#!/usr/bin/env bash
# Walks the phone app in an emulator with taps and swipes and keeps a screenshot of each step.
set -u
out="${1:-phone-shots}"; mkdir -p "$out"
n=0
shot() { sleep "${2:-2}"; n=$((n + 1)); adb exec-out screencap -p > "$out/$(printf '%02d' "$n")-$1.png"; }
# Taps the first on-screen element whose text or description contains the words; returns 1 when there is none.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local point
  point=$(python3 - "$1" "${2:-0}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want, index = sys.argv[1], int(sys.argv[2])
try:
    nodes = [n for n in ET.parse("/tmp/ui.xml").iter("node")
             if want in (n.get("text") or "") or want in (n.get("content-desc") or "")]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[index].get("bounds")))
    print((x1 + x2) // 2, (y1 + y2) // 2)
except Exception:
    pass
PY
)
  [ -n "$point" ] || { echo "no '$1' on screen"; return 1; }
  adb shell input tap $point
  sleep "${3:-3}"
}
swipe_up() { adb shell input swipe 540 1700 540 700 400; sleep 2; }
swipe_down() { adb shell input swipe 540 700 540 1700 400; sleep 2; }
swipe_left() { adb shell input swipe 900 1200 200 1200 300; sleep 2; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
# Dawwirli as the source: Cinemana and VODU answer only on Iraqi networks, not from the runner.
adb shell am start -n com.cinemaplus.rebuilt/.MainActivity
sleep 15
adb shell am force-stop com.cinemaplus.rebuilt
printf '%s' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"content_source\">DAWWIRLI</string></map>" > /tmp/cinema_library.xml
adb push /tmp/cinema_library.xml /data/local/tmp/cinema_library.xml
adb shell chmod 644 /data/local/tmp/cinema_library.xml
adb shell run-as com.cinemaplus.rebuilt sh -c "'mkdir -p shared_prefs && cp /data/local/tmp/cinema_library.xml shared_prefs/cinema_library.xml'"
adb shell am start -n com.cinemaplus.rebuilt/.MainActivity
sleep 40
shot home 1
swipe_up; shot home-scrolled
swipe_up; shot home-scrolled-more
swipe_down; swipe_down; swipe_down
tap "مسلسلات" && shot series-tab
adb shell input tap 900 700; shot series-page 8
swipe_up; shot series-episodes
tap "الموسم" && shot season-picker
adb shell input keyevent KEYCODE_BACK; sleep 2
swipe_up; shot series-more
adb shell input keyevent KEYCODE_BACK; shot back-to-series 3
tap "أفلام" && shot movies-tab
adb shell input tap 900 700; shot movie-page 8
tap "شاهد" && shot player 15
adb shell input tap 540 1000; shot player-controls 1
adb shell input keyevent KEYCODE_BACK; sleep 2; adb shell input keyevent KEYCODE_BACK; sleep 3
tap "بحث" && shot search
tap "مكتبتي" && shot library
tap "الإعدادات" && shot settings
swipe_up; shot settings-more
tap "الرئيسية" && shot home-again
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$out/last-screen.xml" >/dev/null 2>&1 || true
