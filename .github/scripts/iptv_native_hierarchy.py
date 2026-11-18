"""Fresh native observations with a bounded workaround for Android 30's null-child dumper crash."""
import json
from pathlib import Path
import time
import xml.etree.ElementTree as ET


class NativeHierarchyReader:
    def __init__(self, adb, output):
        self.adb = adb
        self.output = Path(output)
        self.failures = []

    def _app_pids(self):
        result = self.adb('shell', 'pidof', 'com.cinemaplus.rebuilt', check=False)
        return tuple(sorted(value for value in result.stdout.decode(errors='replace').split() if value.isdecimal()))

    def nodes(self):
        # Retry only the proven observation crash, never a remote action or application assertion.
        expected_pids = self._app_pids()
        if not expected_pids: raise RuntimeError('App is not running before the native hierarchy dump')
        path = '/sdcard/iptv-input.xml'
        for attempt in range(1,4):
            if self._app_pids() != expected_pids: raise RuntimeError('App restarted before the native hierarchy dump')
            self.adb('shell', 'rm', '-f', path)  # A failed dump cannot leave usable stale XML.
            result = self.adb('shell', 'uiautomator', 'dump', path, timeout=20, check=False)
            after_pids = self._app_pids()
            if result.returncode:
                crash = self.adb('logcat', '-d', '-b', 'crash', '-v', 'threadtime', check=False)
                prefix = f'native-dump-failure-{len(self.failures)+1:03d}'
                for suffix, contents in [('stdout',result.stdout),('stderr',result.stderr),('crash',crash.stdout+crash.stderr)]:
                    (self.output/(prefix+'-'+suffix+'.txt')).write_bytes(contents)
                combined = result.stdout+result.stderr+crash.stdout+crash.stderr
                native_null_child = b'java.lang.NullPointerException' in combined and b'com.android.uiautomator.core.AccessibilityNodeInfoDumper.childNafCheck' in combined
                same_app = after_pids == expected_pids
                failure = {'attempt':attempt,'exitCode':result.returncode,'appPidsBefore':list(expected_pids),
                    'appPidsAfter':list(after_pids),'nativeNullChildCrash':native_null_child,'sameAppProcess':same_app,
                    'evidencePrefix':prefix}
                self.failures.append(failure)
                (self.output/(prefix+'-state.json')).write_text(json.dumps(failure,indent=2))
                memory = self.adb('shell', 'dumpsys', 'meminfo', 'com.cinemaplus.rebuilt', check=False)
                (self.output/(prefix+'-app-memory.txt')).write_bytes(memory.stdout+memory.stderr)
                if not same_app: raise RuntimeError('App died or restarted during the native hierarchy dump')
                if result.returncode != 137 or not native_null_child or attempt == 3:
                    result.check_returncode()
                time.sleep(.3)
                continue
            if after_pids != expected_pids: raise RuntimeError('App died or restarted during the native hierarchy dump')
            fresh = self.adb('exec-out', 'cat', path)
            root = ET.fromstring(fresh.stdout)
            values = root.findall('.//node')
            if root.tag != 'hierarchy' or not values:
                raise RuntimeError('Native hierarchy dump did not produce a fresh populated hierarchy')
            return values
