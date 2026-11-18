#!/usr/bin/env bash
set -euo pipefail
out="${1:?output directory required}"
if [[ -n "${REUSE_RELEASE_RUN_ID:-}" ]]; then
  if [[ "${TELEVISION_GLASS:-false}" == true ]]; then
    python3 .github/scripts/iptv-glass-validation.py --apk "$GITHUB_WORKSPACE/approved-release/Cinema-iQ-release.apk" --output "$out"
  else
    python3 .github/scripts/iptv-controls-validation.py --apk "$GITHUB_WORKSPACE/approved-release/Cinema-iQ-release.apk" --output "$out"
  fi
elif [[ -n "${REUSE_INPUT_RUN_ID:-}" ]]; then
  python3 .github/scripts/iptv-input-validation.py --apk "$GITHUB_WORKSPACE/approved-input/app-debug.apk" --output "$out"
elif [[ "${SOURCE_INPUT:-false}" == true ]]; then
  python3 .github/scripts/iptv-input-validation.py --apk "$GITHUB_WORKSPACE/app/build/outputs/apk/debug/app-debug.apk" --output "$out"
else
  bash .github/scripts/tv-validation.sh "$out"
fi
