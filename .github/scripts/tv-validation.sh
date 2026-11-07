#!/usr/bin/env bash
set -euo pipefail
out="${1:?output directory required}"
mkdir -p "$out"
collect() {
  adb logcat -d -v threadtime > "$out/logcat.txt"
  adb exec-out run-as com.cinemaplus.rebuilt tar -C files/player-smoke -cf - . > "$out/captures.tar" && tar -xf "$out/captures.tar" -C "$out"
}
trap 'collect || true' EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size > "$out/display.txt"
adb shell getprop ro.build.fingerprint > "$out/emulator.txt"
timeout 300s adb shell am instrument -w com.cinemaplus.rebuilt.test/com.cinemaplus.rebuilt.ui.PlayerSmokeInstrumentation | tee "$out/instrumentation.txt"
collect
python3 - "$out/results.json" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
print(json.dumps(result, ensure_ascii=False, indent=2))
assert result.get('passed'), result.get('error', 'Instrumentation failed')
PY
