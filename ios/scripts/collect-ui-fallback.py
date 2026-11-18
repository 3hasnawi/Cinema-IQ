#!/usr/bin/env python3
"""Preserve actual UI evidence even when XCTest leaves an incomplete result bundle."""
import os
from pathlib import Path
import shutil
import subprocess


def main():
    device = os.environ.get("IOS_TEST_DEVICE")
    if not device:
        return
    try:
        container = subprocess.check_output(
            ["xcrun", "simctl", "get_app_container", device,
             "com.cinemaplus.rebuilt.ios.uitests.xctrunner", "data"],
            text=True, timeout=10, stderr=subprocess.DEVNULL,
        ).strip()
        evidence = Path(container) / "Library/Caches/CinemaUITestEvidence"
        if evidence.is_dir():
            shutil.copytree(evidence, Path(os.environ["RUNNER_TEMP"]) / "ios-fallback-evidence", dirs_exist_ok=True)
            print("Saved direct simulator screenshots and accessibility evidence")
    except (subprocess.SubprocessError, OSError):
        print("Direct UI evidence unavailable; result-bundle export will still be attempted")


if __name__ == "__main__":
    main()
