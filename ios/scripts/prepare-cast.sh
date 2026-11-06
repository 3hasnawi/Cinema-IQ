#!/bin/bash
set -euo pipefail
# Official Google distribution, pinned and verified; never committed as a binary.
cd "$(dirname "$0")/.."
archive=.build/cast-4.8.6.zip
root=.build/cast
expected=55f6c21291a1315c68063f07e7d76225564bff70f2fd38caad135c71d66eb310
mkdir -p .build
if [ ! -f "$archive" ] || [ "$(shasum -a 256 "$archive" | awk '{print $1}')" != "$expected" ]; then
  curl --fail --location --retry 2 --connect-timeout 15 --max-time 180 \
    https://dl.google.com/dl/chromecast/sdk/ios/GoogleCastSDK-ios-4.8.6_dynamic.zip -o "$archive.tmp"
  echo "$expected  $archive.tmp" | shasum -a 256 -c -
  mv "$archive.tmp" "$archive"
fi
rm -rf "$root"
mkdir -p "$root"
unzip -q "$archive" -d "$root"
