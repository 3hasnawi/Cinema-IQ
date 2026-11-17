#!/usr/bin/env bash
set -euo pipefail
out="${1:?output directory required}"
if [[ -n "${REUSE_RELEASE_RUN_ID:-}" ]]; then
  python3 .github/scripts/iptv-input-validation.py --apk "$GITHUB_WORKSPACE/approved-release/Cinema-iQ-release.apk" --output "$out"
else
  bash .github/scripts/tv-validation.sh "$out"
fi
