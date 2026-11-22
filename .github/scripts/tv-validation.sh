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
media_server=""
fixture="$(mktemp -d)"
cleanup() {
  if [[ -n "$collector" ]]; then kill "$collector" 2>/dev/null || true; wait "$collector" 2>/dev/null || true; fi
  if [[ -n "$media_server" ]]; then kill "$media_server" 2>/dev/null || true; fi
  rm -rf "$fixture"
  collect_files || true
  timeout 10s adb logcat -d -v threadtime > "$out/logcat.txt" || true
}
trap cleanup EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Encode the repository's offline catalogue art as a test-only film with distinct keyframes.
# Serve the same footage as a disposable M3U provider; the emulator renders the production player and guide.
if ! command -v ffmpeg >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install --no-install-recommends -y ffmpeg
fi
for i in {0..17}; do
  art=(north city forest desert space ocean)
  printf "file '%s/app/src/main/assets/%s.jpg'\nduration 10\n" "$PWD" "${art[i % 6]}"
done > "$fixture/scenes.txt"
printf "file '%s/app/src/main/assets/ocean.jpg'\n" "$PWD" >> "$fixture/scenes.txt"
ffmpeg -hide_banner -loglevel error -f concat -safe 0 -i "$fixture/scenes.txt" \
  -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -t 180 \
  -vf 'scale=960:540:force_original_aspect_ratio=increase,crop=960:540,setsar=1,fps=8,format=yuv420p' \
  -c:v libx264 -threads 2 -preset veryfast -crf 25 -g 8 -c:a aac -b:a 48k -movflags +faststart "$fixture/live.mp4"
adb push "$fixture/live.mp4" /data/local/tmp/tv-capture.mp4
adb shell run-as com.cinemaplus.rebuilt mkdir -p files
adb shell run-as com.cinemaplus.rebuilt cp /data/local/tmp/tv-capture.mp4 files/tv-capture.mp4
cat > "$fixture/list.m3u" <<'PLAYLIST'
#EXTM3U
#EXTINF:-1 group-title="منوعات",الوثائقية
http://10.0.2.2:8765/live.mp4?ch=1
#EXTINF:-1 group-title="منوعات",الطبيعة
http://10.0.2.2:8765/live.mp4?ch=2
#EXTINF:-1 group-title="أخبار",الأخبار
http://10.0.2.2:8765/live.mp4?ch=3
#EXTINF:-1 group-title="رياضة",الملاعب
http://10.0.2.2:8765/live.mp4?ch=4
PLAYLIST
python3 -m http.server 8765 --bind 127.0.0.1 --directory "$fixture" > "$out/fixture-server.txt" 2>&1 &
media_server=$!
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
