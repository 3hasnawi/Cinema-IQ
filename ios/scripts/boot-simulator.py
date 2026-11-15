#!/usr/bin/env python3
"""Boot an isolated UI-test device and require an actual display before XCTest."""
import os
import pathlib
import struct
import subprocess
import sys
import time

device, output = sys.argv[1], pathlib.Path(sys.argv[2])
developer = subprocess.check_output(["xcode-select", "-p"], text=True).strip()
simulator = str(pathlib.Path(developer) / "Applications/Simulator.app")

def run(*command, timeout=30, check=True):
    return subprocess.run(command, timeout=timeout, check=check)

# Hosted runners may have Simulator already open on another device. open -a alone
# then ignores startup arguments and leaves the test device without a framebuffer.
if os.environ.get("GITHUB_ACTIONS") == "true":
    run("killall", "Simulator", timeout=10, check=False)
    run("xcrun", "simctl", "shutdown", "all", check=False)

for attempt in range(2):
    try:
        run("xcrun", "simctl", "boot", device, check=False)
        run("open", "-a", simulator, "--args", "-CurrentDeviceUDID", device)
        run("xcrun", "simctl", "bootstatus", device, "-b", timeout=110)
        run("osascript", "-e", 'tell application "Simulator" to activate', timeout=10, check=False)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                result = run("xcrun", "simctl", "io", device, "screenshot", str(output), timeout=15, check=False)
                if result.returncode == 0 and output.exists():
                    data = output.read_bytes()
                    if data[:8] == b"\x89PNG\r\n\x1a\n" and min(struct.unpack(">II", data[16:24])) > 100:
                        print("Simulator display ready:", struct.unpack(">II", data[16:24]), flush=True)
                        sys.exit(0)
            except subprocess.TimeoutExpired:
                pass
            time.sleep(2)
    except subprocess.TimeoutExpired:
        pass
    if attempt == 0:
        print("Restarting the test simulator after an unavailable display", flush=True)
        run("killall", "Simulator", timeout=10, check=False)
        run("xcrun", "simctl", "shutdown", device, check=False)

raise RuntimeError("The test simulator never produced a display; UI tests were not run")
