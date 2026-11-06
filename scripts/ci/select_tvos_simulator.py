"""Select an available tvOS runtime and a device it explicitly supports."""
import json
import re
import sys


def select_runtime(payload):
    candidates = []
    for runtime in payload.get("runtimes", []):
        if not runtime.get("isAvailable", False):
            continue
        if not runtime.get("identifier", "").startswith("com.apple.CoreSimulator.SimRuntime.tvOS-"):
            continue
        version = tuple(int(part) for part in re.findall(r"\d+", runtime.get("version", "")))
        if version < (18,):
            continue
        devices = [device for device in runtime.get("supportedDeviceTypes", [])
                   if device.get("name", "").startswith("Apple TV") and device.get("identifier")]
        if devices:
            # Prefer 4K hardware, but only if this runtime declares it supported.
            device = max(devices, key=lambda item: ("4K" in item["name"], item["name"]))
            candidates.append((version, runtime["identifier"], device["identifier"]))
    if not candidates:
        raise ValueError("No available tvOS 18+ runtime with a supported Apple TV device")
    _, runtime, device = max(candidates)
    return runtime, device


if __name__ == "__main__":
    try:
        print(" ".join(select_runtime(json.load(sys.stdin))))
    except ValueError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
