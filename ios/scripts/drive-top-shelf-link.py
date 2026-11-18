#!/usr/bin/env python3
"""Deliver real simulator URLs after XCTest confirms the app is ready, not before test installation."""
import pathlib
import subprocess
import sys
import time

device, action, runner = sys.argv[1:]
if action not in ('display', 'play'):
    raise SystemExit('Unknown test action')

def await_marker(stage):
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        result = subprocess.run(['xcrun', 'simctl', 'get_app_container', device, runner, 'data'],
                                capture_output=True, text=True, timeout=15)
        if result.returncode == 0:
            marker = pathlib.Path(result.stdout.strip()) / 'Library/Caches' / ('top-shelf-link-ready-' + stage)
            if marker.exists():
                marker.unlink()
                return
        time.sleep(0.5)
    raise RuntimeError('XCTest never confirmed foreground readiness for ' + stage)

def open_item(item, verb):
    # Only fixed, licensed demo IDs enter this driver; never print production or account URLs.
    url = 'cinemaiq://top-shelf?media=ext%3Ademo%3A' + item + '&action=' + verb
    subprocess.run(['xcrun', 'simctl', 'openurl', device, url], check=True, timeout=20)
    print('Delivered system URL for test stage:', verb, flush=True)

if action == 'display':
    await_marker('prime')
    open_item('big-buck-bunny', 'play')
    await_marker('display')
    open_item('sintel', 'display')
else:
    await_marker('play')
    open_item('big-buck-bunny', 'play')
