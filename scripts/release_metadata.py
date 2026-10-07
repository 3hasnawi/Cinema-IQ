#!/usr/bin/env python3
"""Create public update metadata from the exact signed APK; never infer Android versionCode."""
import argparse, hashlib, json, re, subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--apk', required=True)
parser.add_argument('--aapt', required=True)
parser.add_argument('--tag', required=True)
parser.add_argument('--notes-file')
parser.add_argument('--output', default='update.json')
a = parser.parse_args()
apk = Path(a.apk)
badging = subprocess.check_output([a.aapt, 'dump', 'badging', str(apk)], text=True)
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
if not package or package[1] != 'com.cinemaplus.rebuilt':
    raise SystemExit('Unexpected package')
if a.tag != f'v{package[3]}':
    raise SystemExit('Tag must match the actual APK versionName')
metadata = dict(packageName=package[1], versionCode=int(package[2]), versionName=package[3],
    minSdk=int(re.search(r"sdkVersion:'(\d+)'", badging)[1]),
    apkUrl=f'https://github.com/3hasnawi/Cinema-IQ-Releases/releases/download/{a.tag}/{apk.name}',
    sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
    releaseNotes=Path(a.notes_file).read_text() if a.notes_file else '')
Path(a.output).write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
