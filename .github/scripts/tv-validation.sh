#!/usr/bin/env bash
set -euo pipefail
out="${1:?output directory required}"
mkdir -p "$out"
collect_files() {
  local archive="$out/captures.tmp.tar"
  if timeout 10s adb exec-out run-as com.cinemaplus.rebuilt tar -C files/player-smoke -cf - . > "$archive"; then
    tar -xf "$archive" -C "$out"
  fi
  rm -f "$archive"
}
collector=""
cleanup() {
  if [[ -n "$collector" ]]; then kill "$collector" 2>/dev/null || true; wait "$collector" 2>/dev/null || true; fi
  collect_files || true
  timeout 10s adb logcat -d -v threadtime > "$out/logcat.txt" || true
}
trap cleanup EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size > "$out/display.txt"
adb shell getprop ro.build.fingerprint > "$out/emulator.txt"
# Keep real captures on the host before instrumentation finishes: an emulator disconnect must not lose them.
(while true; do collect_files || true; sleep 5; done) &
collector=$!
set +e
timeout 300s adb shell am instrument -w com.cinemaplus.rebuilt.test/com.cinemaplus.rebuilt.ui.PlayerSmokeInstrumentation | tee "$out/instrumentation.txt"
instrument_status=${PIPESTATUS[0]}
set -e
kill "$collector" 2>/dev/null || true
wait "$collector" 2>/dev/null || true
collector=""
collect_files || true
printf '%s\n' "$instrument_status" > "$out/instrumentation-exit.txt"
python3 - "$out/results.json" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
print(json.dumps(result, ensure_ascii=False, indent=2))
assert result.get('passed'), result.get('error') or result.get('browseError', 'Instrumentation failed')
PY
if [[ "$instrument_status" != 0 ]]; then
  echo "::warning::All JSON assertions completed, but instrumentation transport exited with $instrument_status; inspect emulator logs."
fi
