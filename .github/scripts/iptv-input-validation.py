"""Remote/IME baseline on an existing signed APK; no app build, patch or test hook."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--apk', required=True)
p.add_argument('--output', required=True)
a = p.parse_args()
out = Path(a.output); out.mkdir(parents=True, exist_ok=True)
checks = []


def adb(*args, timeout=15):
    return subprocess.run(['adb', *args], capture_output=True, timeout=timeout)


def key(code):
    adb('shell', 'input', 'keyevent', str(code)); time.sleep(.4)


def nodes():
    r = adb('shell', 'uiautomator', 'dump', '/sdcard/iptv-input.xml', timeout=20)
    if r.returncode: raise RuntimeError('Native UI hierarchy dump failed')
    raw = adb('exec-out', 'cat', '/sdcard/iptv-input.xml').stdout
    return ET.fromstring(raw).findall('.//node')


def label(n):
    return (n.get('text', '') + ' ' + n.get('content-desc', '')).strip()


def find(text, timeout=15):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        for n in nodes():
            if text in label(n): return n
        time.sleep(.5)
    raise AssertionError('UI text not found: ' + text)


def tap(n):
    box = [int(v) for v in re.findall(r'\d+', n.get('bounds', ''))]
    assert len(box) == 4
    adb('shell', 'input', 'tap', str((box[0]+box[2])//2), str((box[1]+box[3])//2)); time.sleep(.5)


def ime():
    raw = adb('shell', 'dumpsys', 'input_method').stdout.decode(errors='replace')
    window = re.search(r'mImeWindowVis=(0x[0-9a-fA-F]+|[0-9]+)', raw)
    if window: return bool(int(window[1], 0) & 2)
    return bool(re.search(r'(?:mInputShown|isInputViewShown|mIsInputViewShown)=true', raw))


def focus():
    return [dict(n.attrib, labels=[label(child) for child in n.iter() if label(child)])
        for n in nodes() if n.get('focused') == 'true']


def capture(name):
    (out/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p').stdout)
    (out/(name+'.json')).write_text(json.dumps({'focused':focus(),'imeVisible':ime()},ensure_ascii=False,indent=2))


def record(name, passed):
    checks.append({'name':name,'passed':bool(passed)})
    print(('PASS' if passed else 'FAIL')+': '+name, flush=True)


error = None
try:
    adb('shell','settings','put','secure','show_ime_with_hard_keyboard','1')
    adb('install', '-r', a.apk, timeout=60).check_returncode()
    # The disposable emulator may permit root. Fall back to the public app entry otherwise.
    adb('root'); adb('wait-for-device', timeout=30)
    r = adb('shell', 'am', 'start', '-W', '-n', 'com.cinemaplus.rebuilt/.ui.iptv.IptvTvActivity')
    if b'SecurityException' in r.stderr or b'Permission Denial' in r.stdout or b'Error' in r.stderr:
        adb('shell','am','start','-W','-n','com.cinemaplus.rebuilt/.MainActivity')
        tap(find('التلفزيون', timeout=35)); key(23)
    tap(find('إضافة مصدر', timeout=30))
    if not any('إضافة مصدر IPTV' in label(n) for n in nodes()): key(23)
    find('إضافة مصدر IPTV')
    capture('01-source-dialog')
    record('Source dialog initially owns a source-type focus target', any('M3U' in str(n) or 'Xtream' in str(n) for n in focus()))
    tap(find('M3U')); key(23)
    key(20)  # Physical D-pad Down, not a touch on the field.
    time.sleep(1)
    capture('02-name-navigation')
    record('Browsing from type to the name field does not capture navigation in the IME', not ime())
    key(23)  # OK explicitly enters editing.
    time.sleep(1)
    capture('03-explicit-edit')
    record('Explicit field edit opens the real system keyboard', ime())
    adb('shell','input','text','CinemaTest')
    key(66)  # A physical Enter must finish the input session and restore the next form control.
    time.sleep(.8)
    capture('04-enter-next')
    record('Completing the source-name input dismisses IME and focuses the address',
        not ime() and any('رابط قائمة M3U' in str(n) for n in focus()))
    if ime(): key(4)
    time.sleep(1)
    capture('05-keyboard-back')
    record('Keyboard is hidden after completing or backing out of input', not ime())
    # Close a possible separate editor and then the source form, with bounded real Back events.
    for _ in range(4):
        if any(n.get('text') == 'إضافة مصدر' or n.get('content-desc') == 'إضافة مصدر' for n in nodes()): break
        key(4)
    capture('06-form-exit')
    record('Back can leave the source form and return to its source screen',
        any(n.get('text') == 'إضافة مصدر' or n.get('content-desc') == 'إضافة مصدر' for n in nodes()))
    tap(find('إضافة مصدر'))
    if not any('إضافة مصدر IPTV' in label(n) for n in nodes()): key(23)
    tap(find('Xtream')); key(23)
    for index, field in enumerate(['اسم المصدر','عنوان خادم Xtream','اسم المستخدم','كلمة المرور']):
        key(20); time.sleep(.5)
        capture('07-xtream-'+str(index))
        record('Xtream D-pad traversal reaches '+field+' without opening IME',
            not ime() and any(field in str(n) for n in focus()))
        if ime(): key(4)
    key(23); time.sleep(.8)
    capture('08-password-edit')
    record('OK opens the password keyboard', ime())
    key(4); time.sleep(.8)
    capture('09-password-keyboard-back')
    record('Back hides the password keyboard', not ime())
    key(4); time.sleep(.5)
    capture('10-password-return')
    record('Leaving the field editor restores password-control focus in the form',
        not ime() and any('كلمة المرور' in str(n) and n.get('class') != 'android.widget.EditText' for n in focus()))
except Exception as e:
    error = type(e).__name__ + ': ' + str(e)
    try: capture('failure')
    except Exception: pass
finally:
    result = {'passed':error is None and all(c['passed'] for c in checks),'checks':checks,'error':error,
        'apkReusedWithoutBuild':'approved-release' in str(a.apk),'typedRealCredentials':False}
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))
    if not result['passed']: raise SystemExit(1)
