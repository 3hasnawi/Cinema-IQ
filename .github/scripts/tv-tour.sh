#!/usr/bin/env bash
# Walks the Android TV app in the emulator with the remote's keys and keeps a screenshot of each step.
set -u
out="${1:-tv-shots}"; mkdir -p "$out"
n=0
shot() { sleep "${2:-2}"; n=$((n + 1)); adb exec-out screencap -p > "$out/$(printf '%02d' "$n")-$1.png"; }
key() { adb shell input keyevent "$1"; sleep "${2:-1.5}"; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
# Dawwirli as the source: the other sources answer only on Iraqi networks, not from the runner.
adb shell am start -n com.cinemaplus.rebuilt/.MainActivity
sleep 15
adb shell am force-stop com.cinemaplus.rebuilt
printf '%s' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"content_source\">DAWWIRLI</string></map>" > /tmp/cinema_library.xml
adb push /tmp/cinema_library.xml /data/local/tmp/cinema_library.xml
adb shell chmod 644 /data/local/tmp/cinema_library.xml
adb shell run-as com.cinemaplus.rebuilt sh -c "'mkdir -p shared_prefs && cp /data/local/tmp/cinema_library.xml shared_prefs/cinema_library.xml && cat shared_prefs/cinema_library.xml'"
adb shell am start -n com.cinemaplus.rebuilt/.MainActivity
sleep 45
shot home 1
# Play the featured movie and scrub: the time bubble over the bar, then a faster step.
key KEYCODE_DPAD_CENTER 20; shot player 1
key KEYCODE_DPAD_RIGHT 0.4; shot scrub-1 0.3
key KEYCODE_DPAD_RIGHT 0.3; key KEYCODE_DPAD_RIGHT 0.3; shot scrub-faster 0.2
key KEYCODE_DPAD_LEFT 0.3; key KEYCODE_DPAD_LEFT 0.3; shot scrub-back 0.2
sleep 3; shot after-scrub 1
key KEYCODE_BACK 2; key KEYCODE_BACK 4; shot home-after-player 1
key KEYCODE_DPAD_DOWN; shot home-down
key KEYCODE_DPAD_UP 2
# The rail is on the right (Arabic layout).
key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_RIGHT; shot rail-open
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; shot rail-series
key KEYCODE_DPAD_CENTER 12; shot series-grid
key KEYCODE_DPAD_CENTER 15; shot series-page
key KEYCODE_DPAD_DOWN 3; shot series-down-1
key KEYCODE_DPAD_UP 2; key KEYCODE_DPAD_DOWN 3; shot series-down-again
key KEYCODE_DPAD_LEFT 3; shot series-left
key KEYCODE_DPAD_UP 3; shot series-up
key KEYCODE_DPAD_LEFT 3; shot series-up-left
key KEYCODE_DPAD_CENTER 4; shot series-up-left-select
key KEYCODE_DPAD_DOWN 3; shot series-after
key KEYCODE_BACK 3; shot back-to-grid
key KEYCODE_DPAD_LEFT; key KEYCODE_DPAD_CENTER 15; shot second-series
key KEYCODE_DPAD_DOWN 3; shot second-series-down
key KEYCODE_DPAD_LEFT 3; shot second-series-left
key KEYCODE_BACK 3; key KEYCODE_BACK 3; shot back-home
