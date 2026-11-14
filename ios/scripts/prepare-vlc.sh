#!/bin/bash
set -euo pipefail
# Official stable dynamic frameworks, pinned to the binaries published by VideoLAN's 3.7.x podspecs.
cd "$(dirname "$0")/.."
mkdir -p .build/vlc
install_kit() {
  local name="$1" url="$2" digest="$3" archive=".build/vlc/$1.tar.xz" root=".build/vlc/$1-binary"
  if [ -f "$root/$name.xcframework/Info.plist" ] && [ -f "$root/.verified-$digest" ]; then return; fi
  if [ ! -f "$archive" ] || [ "$(shasum -a 256 "$archive" | awk '{print $1}')" != "$digest" ]; then
    curl --fail --location --retry 2 --connect-timeout 15 --max-time 300 "$url" -o "$archive.tmp"
    echo "$digest  $archive.tmp" | shasum -a 256 -c -
    mv "$archive.tmp" "$archive"
  fi
  tar -xJf "$archive" -C .build/vlc "$name-binary/$name.xcframework" "$name-binary/COPYING.txt"
  test -f "$root/$name.xcframework/Info.plist"
  touch "$root/.verified-$digest"
}
case "${CINEMA_VLC_PLATFORM:-all}" in
  ios|all) install_kit MobileVLCKit \
    https://download.videolan.org/pub/cocoapods/prod/MobileVLCKit-3.0-20260922-1512.tar.xz \
    c649c4c47bd065b3a5511746361b5a9066a34033f2df9cbc9454409c0f363b52 ;;
esac
case "${CINEMA_VLC_PLATFORM:-all}" in
  tv|all) install_kit TVVLCKit \
    https://download.videolan.org/cocoapods/prod/TVVLCKit-3.0-20260930-0854.tar.xz \
    07892cae8e59e95a23f1fcf11692f802eae106312aa5ad9782faa2556b2d65d5 ;;
esac
