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


def adb(*args, timeout=15, check=True):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    if check: result.check_returncode()
    return result


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
    # Service flags can remain set after a TV keyboard disappears; inspect its actual window.
    raw = adb('shell', 'dumpsys', 'window', 'windows').stdout.decode(errors='replace')
    blocks = re.split(r'\n(?=\s*Window #\d+ )',raw)
    for block in blocks:
        header = block.splitlines()[0] if block.splitlines() else ''
        if 'InputMethod' not in header and not re.search(r'\bty=INPUT_METHOD\b|\btype=2011\b',block): continue
        for key in ['isOnScreen','isVisible','isReadyForDisplay()']:
            found = re.search(re.escape(key)+r'=(true|false)',block)
            if found:
                if found[1]=='true': return True
                break
    return False


def focus():
    return [dict(n.attrib, labels=[label(child) for child in n.iter() if label(child)])
        for n in nodes() if n.get('focused') == 'true']


def capture(name):
    (out/(name+'.xml')).write_bytes(adb('exec-out', 'cat', '/sdcard/iptv-input.xml').stdout)
    (out/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p').stdout)
    (out/(name+'.json')).write_text(json.dumps({'focused':focus(),'imeVisible':ime()},ensure_ascii=False,indent=2))
    (out/(name+'-windows.txt')).write_bytes(adb('shell','dumpsys','window').stdout)
    (out/(name+'-input.txt')).write_bytes(adb('shell','dumpsys','input').stdout)
    (out/(name+'-ime.txt')).write_bytes(adb('shell','dumpsys','input_method').stdout)


def record(name, passed):
    checks.append({'name':name,'passed':bool(passed)})
    print(('PASS' if passed else 'FAIL')+': '+name, flush=True)


def open_editor(name):
    key(23)
    until = time.monotonic() + 6
    while time.monotonic() < until:
        if any(n.get('class') == 'android.widget.EditText' for n in nodes()) and ime():
            capture(name)
            return
        time.sleep(.3)
    capture(name+'-single-ok-failed')
    record('A single OK opens '+name+' after restoring form focus',False)
    # Diagnostic recovery is reported as a failure, never counted as a passing single-OK flow.
    key(23); time.sleep(1)
    if not any(n.get('class') == 'android.widget.EditText' for n in nodes()):
        capture(name+'-two-ok-failed')
        key(19); capture(name+'-up-diagnostic')
        key(20); key(23); capture(name+'-down-ok-diagnostic')
        keyboard_package = adb('shell','settings','get','secure','default_input_method').stdout.decode().strip().split('/')[0]
        if re.fullmatch(r'[A-Za-z0-9_.]+',keyboard_package):
            adb('shell','am','force-stop',keyboard_package)
            key(23); time.sleep(1); capture(name+'-ime-reset-diagnostic')
        raise AssertionError('Editor not opened by two OK presses: '+name)
    capture(name+'-second-ok')


def native_done():
    # This fixed 1080p English TV-IME fixture starts on Q. Reach its bottom-row action key
    # with real D-pad events; do not replace the IME action with an app callback or a tap.
    for _ in range(3): key(20)
    for _ in range(6): key(22)
    capture('native-action-'+str(len(checks)))
    key(23); time.sleep(.8)


error = None
try:
    adb('shell','settings','put','secure','show_ime_with_hard_keyboard','1')
    adb('install', '-r', a.apk, timeout=60).check_returncode()
    # The disposable emulator may permit root. Fall back to the public app entry otherwise.
    adb('root',check=False); adb('wait-for-device', timeout=30)
    r = adb('shell', 'am', 'start', '-W', '-n', 'com.cinemaplus.rebuilt/.ui.iptv.IptvTvActivity',check=False)
    if b'SecurityException' in r.stderr or b'Permission Denial' in r.stdout or b'Error' in r.stderr:
        adb('shell','am','start','-W','-n','com.cinemaplus.rebuilt/.MainActivity')
        tap(find('التلفزيون', timeout=35)); key(23)
    find('إضافة مصدر', timeout=30)
    key(23)
    find('إضافة مصدر IPTV')
    capture('01-source-dialog')
    record('Source dialog initially owns a source-type focus target', any('M3U' in str(n) or 'Xtream' in str(n) for n in focus()))
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
    open_editor('04b-url-open')
    adb('shell','input','text','https://example.invalid/list.m3u')
    capture('04b-url-edit')
    native_done()
    capture('04c-url-done')
    record('Native IME Done commits the M3U URL and focuses HTTP without keyboard',
        not ime() and any('HTTP' in str(n) for n in focus()) and any('example.invalid/list.m3u' in label(n) for n in nodes()))
    key(20)
    record('A completed M3U form reaches its enabled Connect control',
        not ime() and any('اتصال وحفظ' in str(n) and n.get('enabled')=='true' for n in focus()))
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
    find('إضافة مصدر'); key(23)
    find('إضافة مصدر IPTV')
    key(21)  # Physical Left from the focused M3U button to Xtream.
    capture('07-xtream-mode')
    record('Physical Left reaches the Xtream source-type button',any('Xtream' in str(n) for n in focus()))
    key(23)
    find('عنوان خادم Xtream')
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
    key(19); key(19)  # Password -> User -> Address.
    open_editor('11-address-open')
    adb('shell','input','text','https://example.invalid')
    native_done()
    capture('11-xtream-address-next')
    record('Native IME Next commits the Xtream address and focuses username',
        not ime() and any('اسم المستخدم' in str(n) for n in focus()))
    open_editor('12-user-open')
    adb('shell','input','text','fixture_user')
    native_done()
    capture('12-xtream-user-next')
    record('Native IME Next commits username and focuses password',
        not ime() and any('كلمة المرور' in str(n) for n in focus()))
    open_editor('13-password-open')
    adb('shell','input','text','fixture-pass')
    native_done()
    capture('13-xtream-password-done')
    record('Native IME Done commits password and focuses stream format',
        not ime() and any('صيغة البث' in str(n) for n in focus()))
    record('Completed credentials stay in the form and password remains masked',
        any('fixture_user' in label(n) for n in nodes()) and not any('fixture-pass' in label(n) for n in nodes()))
    key(20); key(20)
    record('Completed Xtream form reaches Connect without reopening the keyboard',
        not ime() and any('اتصال وحفظ' in str(n) for n in focus()))
    key(21)
    record('Physical Left from Connect reaches Cancel',any('إلغاء' in str(n) for n in focus()))
    key(23)
    capture('14-xtream-cancel')
    record('Cancel closes the completed source form and restores Add Source focus',
        not ime() and any('إضافة مصدر' in str(n) for n in focus()))
except Exception as e:
    error = type(e).__name__ + ': ' + str(e)
    try: capture('failure')
    except Exception: pass
finally:
    result = {'passed':error is None and all(c['passed'] for c in checks),'checks':checks,'error':error,
        'apkReusedWithoutBuild':'approved-release' in str(a.apk) or 'approved-input' in str(a.apk),'typedRealCredentials':False}
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))
    if not result['passed']: raise SystemExit(1)
