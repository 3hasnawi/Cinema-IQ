"""Real remote/IME, category protection and Media3 recovery on an approved existing APK."""
from collections import Counter
from datetime import datetime, timedelta, timezone
import argparse
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit

# Keep diagnostics available even if the imported native source-form baseline fails.
diagnostic_parser = argparse.ArgumentParser(add_help=False)
diagnostic_parser.add_argument('--output', required=True)
diagnostic_arguments, _ = diagnostic_parser.parse_known_args()
diagnostic_output = Path(diagnostic_arguments.output)
diagnostic_output.mkdir(parents=True, exist_ok=True)


def diagnostic_adb(*arguments):
    try:
        return subprocess.run(['adb', *arguments], capture_output=True, timeout=20)
    except (OSError, subprocess.SubprocessError) as exception:
        return subprocess.CompletedProcess(arguments, 1, b'', str(exception).encode())


def collect_diagnostics(name):
    pid_result = diagnostic_adb('shell', 'pidof', 'com.cinemaplus.rebuilt')
    pids = [value for value in pid_result.stdout.decode(errors='replace').split() if value.isdecimal()]
    app_logs = []
    for pid in pids:
        result = diagnostic_adb('logcat', '-d', '-v', 'threadtime', '--pid='+pid)
        if result.returncode:
            # Older logcat versions may not implement --pid; retain only this app's PID.
            fallback = diagnostic_adb('logcat', '-d', '-v', 'threadtime')
            lines = fallback.stdout.decode(errors='replace').splitlines()
            result = subprocess.CompletedProcess([], fallback.returncode, '\n'.join(line for line in lines
                if re.match(r'^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+'+pid+r'\s', line)).encode(), fallback.stderr)
        app_logs.append(b'App PID '+pid.encode()+b'\n'+result.stdout+b'\n'+result.stderr)
    if not pids:
        app_logs.append(b'No running app PID; inspect the separate crash buffer.\n'+pid_result.stderr)
    (diagnostic_output/(name+'-app-logcat.txt')).write_bytes(b'\n'.join(app_logs))
    for suffix, arguments in [
        ('overlay-focus-logcat', ('logcat', '-d', '-v', 'threadtime', '-s', 'IptvOverlayFocus:D', '*:S')),
        ('crash-logcat', ('logcat', '-d', '-b', 'crash', '-v', 'threadtime')),
        ('windows', ('shell', 'dumpsys', 'window')),
        ('input', ('shell', 'dumpsys', 'input')),
    ]:
        result = diagnostic_adb(*arguments)
        (diagnostic_output/(name+'-'+suffix+'.txt')).write_bytes(result.stdout+b'\n'+result.stderr)


# Run the existing real native Next/Done/Back source-form baseline first, on this same APK.
# Only fake .invalid credentials are entered; the source is never submitted to a provider.
try:
    # The approved APK stays unchanged; the disposable emulator opts in to bounded focus diagnostics.
    subprocess.run(['adb', 'shell', 'setprop', 'log.tag.IptvOverlayFocus', 'DEBUG'], check=True, timeout=15)
    baseline = runpy.run_path(str(Path(__file__).with_name('iptv-input-validation.py')))
except BaseException:
    collect_diagnostics('glass-baseline-failure')
    raise
adb, key, nodes, label, focus, ime, record = [baseline[n] for n in ('adb', 'key', 'nodes', 'label', 'focus', 'ime', 'record')]
out, checks = baseline['out'], baseline['checks']
fixture = Path(tempfile.mkdtemp(prefix='cinema-tv-glass-fixture-'))
requests, failures, successes = Counter(), Counter(), Counter()
fault = {'media': False, 'catalogue': False}
server, error = None, None
reentry_seconds = []
startup_observations = []
native_dump_failures = baseline['hierarchy_reader'].failures
first_back_observation = None
names = ['Cinema Fixture 1', 'Cinema Fixture 2', 'الأخبار', 'Cinema Protected 1']


def snapshot(name, values=None):
    if name in ('glass-failure', 'glass-timeout'):
        collect_diagnostics(name)
    values = nodes() if values is None else values
    (out/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p').stdout)
    (out/(name+'.json')).write_text(json.dumps([n.attrib for n in values], ensure_ascii=False, indent=2))
    (out/(name+'-focus.json')).write_text(json.dumps({'focused': focus(), 'imeVisible': ime()}, ensure_ascii=False, indent=2))
    return values


def visible(text, values=None):
    return any(text in label(n) for n in (nodes() if values is None else values))


def focused_on(text, values=None):
    return any(n.get('focused') == 'true' and text in ' '.join(label(c) for c in n.iter())
               for n in (nodes() if values is None else values))


def channel_row_visible(name, values=None):
    # A noninteractive HUD logo/title may have this exact name. Require a row control around it.
    for node in (nodes() if values is None else values):
        named = [child for child in node.iter() if child.get('class') != 'android.widget.ImageView' and
                 name in (child.get('text'), child.get('content-desc'))]
        if not named: continue
        if node.get('clickable') == 'true' or node.get('class') == 'android.widget.Button': return True
        # TV surfaces can expose a separate Button-role child even when disabled/non-focusable.
        # Require the name and role to share this entire row's bounds, not unrelated HUD controls.
        bounds = node.get('bounds')
        if bounds and any(child.get('content-desc') == name and child.get('bounds') == bounds for child in named) and any(
                child.get('class') == 'android.widget.Button' and child.get('bounds') == bounds for child in node.iter()):
            return True
    return False


def hud_visible(values=None):
    return any(n.get('content-desc', '').startswith(('الجودة:', 'الصوت:'))
               for n in (nodes() if values is None else values))


def player_surface_visible(values=None):
    return any(n.get('content-desc') == 'قائمة القنوات'
               for n in (nodes() if values is None else values))


def startup_source_controls(values):
    return any(text in label(n) for text in ('إضافة مصدر', 'إدارة المصادر', 'تعديل الاتصال') for n in values)


def expect(name, condition):
    record(name, condition)
    if not condition:
        snapshot('glass-failure')
        raise AssertionError(name)


def wait_for(predicate, message, timeout=25):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        values = nodes()
        if predicate(values): return values
        time.sleep(.4)
    snapshot('glass-timeout')
    raise AssertionError(message)


def seek(text, direction=20, limit=12):
    for _ in range(limit):
        values = nodes()
        if focused_on(text, values): return values
        key(direction)
    raise AssertionError('D-pad cannot focus '+text)


def start_tv(sample_saved_source=None):
    started = time.monotonic()
    adb('shell', 'am', 'start', '-W', '-n', 'com.cinemaplus.rebuilt/.MainActivity')
    # Public rail entry may be touched; every tested in-section interaction uses physical D-pad/IME events.
    rail = baseline['find']('التلفزيون', timeout=35)
    if sample_saved_source is None:
        baseline['tap'](rail)
    else:
        bounds = [int(value) for value in re.findall(r'\d+', rail.get('bounds', ''))]
        assert len(bounds) == 4
        # Start sampling without the baseline tap helper's deliberate post-tap sleep.
        adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
        entry_started = time.monotonic()
        samples = []
        try:
            deadline = entry_started+60
            while time.monotonic() < deadline:
                values = nodes()
                forbidden = startup_source_controls(values)
                pin_present = visible('أدخل رمز الحماية',values) or visible('اختر رمزًا',values)
                player_present = player_surface_visible(values)
                samples.append({'elapsedSeconds':round(time.monotonic()-entry_started,3),
                    'sourceControlsPresent':forbidden,'playerPresent':player_present,'pinPresent':pin_present})
                if len(samples) == 1: snapshot(sample_saved_source+'-startup-first-sample',values)
                if forbidden:
                    expect('Sampled saved-source startup never exposes import or management controls',False)
                if player_present or pin_present: break
                time.sleep(.1)
            else:
                snapshot('glass-timeout')
                raise AssertionError('Saved-source startup did not reach the player or required PIN')
            expect('Sampled saved-source startup keeps source controls out of loading',
                   bool(samples) and all(not sample['sourceControlsPresent'] for sample in samples))
        finally:
            observation = {'stage':sample_saved_source,'samples':samples}
            startup_observations.append(observation)
            (out/(sample_saved_source+'-startup-samples.json')).write_text(json.dumps(observation,indent=2))
    return started


def wait_live(name, timeout=60):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        key(22)  # Reveal the HUD without changing the station.
        values = nodes()
        if visible(name, values) and visible('بث مباشر', values): return values
        expect('Recoverable loading never produces a blocking retry dialog',
               not visible('تحديث الرابط وإعادة المحاولة', values))
        time.sleep(.6)
    raise AssertionError('Actual Media3 playing state not reached: '+name)


def open_channels(expected):
    time.sleep(6)
    key(23)
    values = wait_for(lambda v: visible('البحث', v) and focused_on(expected, v),
                      'One OK did not open channels at the current station')
    expect('One OK opens channel overlay on current station '+expected, not ime())
    return values


def search(query):
    seek('البحث', 19, 10)
    key(23)
    wait_for(lambda v: any(n.get('class') == 'android.widget.EditText' for n in v) and ime(),
             'One OK must open the native search editor and actual IME')
    adb('shell', 'input', 'text', query)
    expect('Filtering retains the native editor and actual IME', ime())


def choose_group(name):
    key(22)
    seek('كل القنوات', 19, 12)
    seek(name, 20, 10)
    key(23)


def return_to_playback():
    key(4)  # Dismiss the management dialog.
    values = nodes()
    if visible('التصنيفات ←', values): key(4)  # Retained channel overlay, independent from the new player.


def open_manager(group='Protected'):
    seek('البحث', 19, 10)
    key(21)
    expect('Physical Left reaches category management', focused_on('إدارة التصنيفات'))
    key(23)
    seek(group, 20, 10)
    key(23)
    wait_for(lambda v: focused_on('رجوع', v), 'Category actions should focus Back safely')


def wait_pin():
    values = wait_for(lambda v: focused_on('1', v) and (visible('أدخل رمز الحماية', v) or visible('اختر رمزًا', v)),
                      'PIN keypad did not receive initial focus')
    expect('PIN uses its remote keypad without opening an IME', not ime())
    return values


def pin_dpad(value, current='1'):
    positions = {digit: i for i, digit in enumerate(['1','2','3','4','5','6','7','8','9','delete','0','cancel'])}
    index = positions[current]
    for digit in value:
        target = positions[digit]
        while index//3 != target//3:
            step = 3 if index//3 < target//3 else -3
            key(20 if step > 0 else 19); index += step
        while index%3 != target%3:
            step = 1 if index%3 < target%3 else -1
            key(22 if step > 0 else 21); index += step
        expect('D-pad PIN digit is focused before activation', focused_on(digit))
        key(23)
    return value[-1]


def pin_digits(value='2468'):
    for digit in value: key(7+int(digit))


def admit_protected_group():
    choose_group('Protected')
    values = wait_pin()
    expect('Every explicit protected-category entry requires fresh PIN before its rows',
           not channel_row_visible(names[3], values))
    pin_digits()
    wait_for(lambda v: not ime() and focused_on(names[3], v),
             'Fresh correct PIN must focus the admitted protected channel')


def open_protected_channels():
    time.sleep(6)
    key(23)  # A locked-group remount requires fresh authorization on this single opening event.
    values = wait_pin()
    expect('One OK reopens a locked group at PIN without exposing a protected row',
           not channel_row_visible(names[3], values))
    pin_digits()
    wait_for(lambda v: not ime() and focused_on(names[3], v),
             'Fresh remount PIN must restore the protected channel focus')


def open_safe_channels_while_protected_plays():
    time.sleep(6)
    key(23)  # Startup authorization grants only the exact decoder, not a category visit.
    values = wait_for(lambda v: visible('البحث', v) and focused_on(names[0], v),
                      'One OK after protected startup must focus the first safe All Channels row')
    expect('Protected startup does not admit its channel through the All Channels overlay',
           not ime() and not channel_row_visible(names[3], values))


def frame_digest(name):
    capture = adb('exec-out', 'screencap', '-p').stdout
    (out/(name+'.png')).write_bytes(capture)
    # Timecode is burned at the top-left of actual video; exclude spinner, lists and the bottom HUD.
    image = subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','image2pipe','-i','pipe:0',
        '-vf','crop=600:120:40:40','-frames:v','1','-f','rawvideo','-pix_fmt','rgb24','pipe:1'],
        input=capture,capture_output=True,check=True,timeout=20).stdout
    return hashlib.sha256(image).hexdigest()


def advancing_frames(prefix, stale=None):
    time.sleep(6)
    first = frame_digest(prefix+'-a'); time.sleep(1.4)
    second = frame_digest(prefix+'-b')
    expect('Actual video timecode advances: '+prefix, first != second and (stale is None or second != stale))


try:
    assert shutil.which('ffmpeg'), 'ffmpeg is needed for generated test media and actual frame evidence'
    host = 'http://10.0.2.2:8765'
    playlist = '#EXTM3U x-tvg-url="'+host+'/guide.xml"\n'
    for number, (name, group) in enumerate(zip(names, ['Fixture','Fixture','News','Protected']), 1):
        wordmark = 'NEWS' if group == 'News' else 'SPORT' if group == 'Protected' else 'DOC'
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','color=c=black@0.0:s=256x160:d=1,format=rgba',
            '-vf',f'format=rgba,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text={wordmark}:fontsize=64:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2',
            '-frames:v','1','-threads','1','-pix_fmt','rgba',str(fixture/f'logo{number}.png')],check=True,timeout=15)
        title = 'IQ: '+name if number == 1 else name
        playlist += f'#EXTINF:-1 tvg-id="fixture{number}" tvg-logo="{host}/logo{number}.png" group-title="{group}",{title}\n{host}/live{number}.m3u8\n'
    (fixture/'list.m3u').write_text(playlist)
    now = datetime.now(timezone.utc)
    stamp = lambda value: value.strftime('%Y%m%d%H%M%S +0000')
    (fixture/'guide.xml').write_text('<tv><programme channel="fixture1" start="'+stamp(now-timedelta(minutes=10))+'" stop="'+stamp(now+timedelta(minutes=20))+'"><title>برنامج اختبار حالي</title></programme><programme channel="fixture1" start="'+stamp(now+timedelta(minutes=20))+'" stop="'+stamp(now+timedelta(minutes=50))+'"><title>برنامج اختبار تالي</title></programme></tv>')
    for height, bitrate in [(360,'220k'),(720,'650k')]:
        directory = fixture/str(height); directory.mkdir()
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i',
            f'testsrc2=size={height*16//9}x{height}:rate=10','-f','lavfi','-i','sine=frequency=440:sample_rate=48000',
            '-t','600','-vf',f"drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:text='%{{pts\\:hms}}':fontsize={height//18}:x={height//24}:y={height//24}:fontcolor=white:box=1:boxcolor=black",
            '-c:v','libx264','-preset','ultrafast','-threads','2','-pix_fmt','yuv420p','-b:v',bitrate,
            '-g','20','-keyint_min','20','-sc_threshold','0','-c:a','aac','-b:a','48k','-ac','2','-f','hls',
            '-hls_time','2','-hls_playlist_type','vod','-hls_segment_filename',str(directory/'segment-%03d.ts'),str(directory/'index.m3u8')],
            check=True,timeout=180)
    master = '#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360\n360/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=750000,RESOLUTION=1280x720\n720/index.m3u8\n'
    for number in range(1,5): (fixture/f'live{number}.m3u8').write_text(master)

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs): super().__init__(*args, directory=str(fixture), **kwargs)
        def log_message(self, *args): pass
        def do_GET(self):
            path = urlsplit(self.path).path; requests[path] += 1
            initial = path == '/live1.m3u8' and failures['initial'] < 5
            blocked = (fault['media'] and path.endswith(('.m3u8','.ts'))) or (fault['catalogue'] and path == '/list.m3u')
            if initial or blocked:
                failures['initial' if initial else 'outage' if fault['media'] else 'catalogue'] += 1
                self.send_response(503); self.send_header('Content-Length','0'); self.send_header('Connection','close'); self.end_headers(); return
            successes[path] += 1
            super().do_GET()

    server = ThreadingHTTPServer(('0.0.0.0',8765),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    key(23)  # Existing native baseline leaves the Add Source button focused.
    baseline['find']('إضافة مصدر IPTV')
    key(20); key(23); adb('shell','input','text','GeneratedFixture'); key(66)
    baseline['open_editor']('20-glass-fixture-address')
    adb('shell','input','text',host+'/list.m3u'); key(66)
    expect('Fixture address Done restores HTTP control with IME closed', not ime() and focused_on('HTTP'))
    key(23); key(20); key(23)
    values = wait_live(names[0])
    expect('First entry starts actual playback directly and recovers five initial HTTP503 responses', failures['initial']==5 and requests['/live1.m3u8']>=6)
    expect('Provider now/next programme information is shown', visible('برنامج اختبار حالي',values) and visible('برنامج اختبار تالي',values))
    expect('Native TV HUD displays the exact clean channel title without its provider prefix',
           any(node.get('text') == names[0] for node in values) and not visible('IQ: '+names[0],values))
    snapshot('21-glass-playing-hud',values)
    advancing_frames('22-initial-native-video')

    values = open_channels(names[0])
    expect('Native guide displays the exact clean channel row without its provider prefix',
           channel_row_visible(names[0],values) and any(node.get('content-desc') == names[0] and
           node.get('class') != 'android.widget.ImageView' for node in values) and not visible('IQ: '+names[0],values))
    key(22); seek('Fixture',20)
    snapshot('22b-channel-list-and-categories')
    key(23)
    seek('البحث',19); search('Protected')
    snapshot('22c-native-search-ime')
    baseline['native_done']()
    wait_for(lambda v: not ime() and focused_on(names[3],v), 'Native Search must dismiss IME and focus a result from another category')
    expect('Search is global across the category selected before editing', focused_on(names[3]) and not ime())
    snapshot('22d-global-search-results-after-done')
    key(4)
    expect('Back from search results restores the prior category and exact channel', focused_on(names[0]))
    search('News'); key(4)
    wait_for(lambda v: not ime() and focused_on('بحث / تم',v), 'First Back should hide IME and focus Search Done')
    key(4)
    expect('Second Back cancels search and restores its origin', focused_on(names[0]) and not ime())
    search('2'); key(66)
    wait_for(lambda v: not ime() and focused_on(names[1],v), 'Numeric search Enter should focus station2')
    key(4); expect('Numeric search cancellation preserves the previous station', focused_on(names[0]))
    snapshot('23-search-done-and-back-restoration')
    search('Protected'); key(66); wait_for(lambda v: focused_on(names[3],v) and not ime(), 'Protected result not focused'); key(23)
    wait_live(names[3])
    key(22); seek('المفضلة',21,8); key(23)  # Add Protected before locking, to test the Favorites route too.
    key(20); wait_live(names[0])
    open_channels(names[0]); open_manager()
    key(19); key(19); expect('Lock action is reachable by remote from safe category Back', focused_on('قفل برمز PIN')); key(23)
    wait_pin(); last = pin_dpad('2468')
    wait_for(lambda v: visible('أعد إدخال الرمز للتأكيد',v), 'New PIN must require confirmation')
    pin_dpad('2468',last)
    wait_for(lambda v: focused_on('Protected',v) and not visible('أربع',v), 'Lock should return to its category row')
    expect('PIN setup closes without creating an IME', not ime())
    return_to_playback(); wait_live(names[0])
    open_channels(names[0]); seek('البحث',19); key(21); key(21)
    expect('Source management remains reachable by physical Left', focused_on('المصادر والاشتراكات'))
    key(23); wait_pin(); key(4)
    expect('Cancelling source authorization does not expose the source form', not visible('إضافة مصدر IPTV'))
    open_channels(names[0])
    expect('A single OK restores channel navigation after source-PIN cancellation', focused_on(names[0]))
    choose_group('Protected'); wait_pin(); pin_digits('9876')
    wait_for(lambda v: visible('رمز غير صحيح',v), 'Incorrect physical PIN must be rejected')
    key(4); expect('PIN cancellation restores the originating category', focused_on('Protected'))
    key(21); search('Protected'); key(66)
    wait_for(lambda v: not ime() and visible('لا توجد قنوات مطابقة',v), 'Search must exclude the locked station')
    key(4); choose_group('المفضلة')
    expect('Favorites cannot bypass a newly locked category', visible('لا توجد قنوات مطابقة'))
    key(22); seek('كل القنوات',19,8); key(23)
    expect('All Channels does not expose the locked station', not visible(names[3]))
    snapshot('24-locked-search-and-favorites')
    admit_protected_group(); key(23); wait_live(names[3])

    # Reopening a watched locked group is a new admission, even while its exact decoder continues.
    open_protected_channels()
    public_reads = sum(requests[f'/live{number}.m3u8'] for number in (1,2,3))
    choose_group('Fixture')  # Browse away without selecting or tuning another channel.
    wait_for(lambda v: focused_on(names[0],v), 'Leaving the protected category must focus its safe destination')
    expect('Leaving a category does not tune another channel or retain protected guide rows',
           sum(requests[f'/live{number}.m3u8'] for number in (1,2,3)) == public_reads and
           not channel_row_visible(names[3]))
    advancing_frames('24b-protected-stream-continues-after-leaving')
    choose_group('كل القنوات')
    expect('All Channels cannot reuse a departed protected visit', not channel_row_visible(names[3]))
    search('Protected'); key(66)
    wait_for(lambda v: not ime() and visible('لا توجد قنوات مطابقة',v),
             'Global search must not reuse the departed category grant')
    expect('Global search cannot expose the continuing protected channel row', not channel_row_visible(names[3]))
    key(4); choose_group('المفضلة')
    expect('Favorites cannot reuse a departed category grant for the continuing station',
           visible('لا توجد قنوات مطابقة') and not channel_row_visible(names[3]))
    choose_group('Fixture')
    snapshot('24c-departed-protected-visit-safe-list')
    choose_group('Protected'); values = wait_pin()
    expect('Returning to a departed protected category always asks for a fresh PIN',
           not channel_row_visible(names[3],values))
    pin_digits('9876')
    wait_for(lambda v: visible('رمز غير صحيح',v), 'Wrong PIN on category reentry must be rejected')
    expect('Wrong reentry PIN never reveals protected rows', not channel_row_visible(names[3]))
    key(4)
    wait_for(lambda v: focused_on('Protected',v), 'Cancelled reentry must restore its category button')
    expect('Cancelled reentry keeps the protected category closed without opening an IME',
           not ime() and not channel_row_visible(names[3]))
    snapshot('24d-protected-reentry-pin-cancel-focus')
    advancing_frames('24e-protected-stream-after-wrong-pin-cancel')
    admit_protected_group()
    expect('Fresh successful reentry admits the channel after the old visit was revoked', focused_on(names[3]))

    # Global search leaves a visit; cancelling it cannot silently restore protected origin rows.
    search('News'); key(4)
    wait_for(lambda v: not ime() and focused_on('بحث / تم',v),
             'First Back in protected-origin search must dismiss the actual keyboard')
    key(4); values = wait_pin()
    expect('Returning from global search to a protected origin requires fresh PIN',
           not channel_row_visible(names[3],values))
    key(4)
    wait_for(lambda v: focused_on('Protected',v), 'Cancelling protected search-origin admission restores its category focus')
    expect('Search cancellation cannot restore a stale protected row or reopen the keyboard',
           not ime() and not channel_row_visible(names[3]))
    snapshot('24f-protected-search-origin-cancel-focus')
    admit_protected_group()
    key(4); wait_live(names[3])
    # Closing a still-active group retains same-group surfing but reopening demands fresh admission.
    key(20); wait_live(names[3])
    time.sleep(6); key(23); values = wait_pin()
    expect('Single OK still requires PIN after same-group playback and surfing',
           not channel_row_visible(names[3],values))
    key(4)
    wait_for(lambda v: focused_on('Protected',v), 'Cancelled locked remount must restore its category with safe rows')
    expect('Cancelled locked remount does not reveal the playing channel row', not channel_row_visible(names[3]))
    snapshot('24g-protected-remount-cancel-focus')
    admit_protected_group(); key(4); wait_live(names[3])

    protected_reads = requests['/live4.m3u8']
    adb('shell','am','force-stop','com.cinemaplus.rebuilt'); start_tv('25-protected-cold-entry'); wait_pin()
    expect('Locked last channel requires PIN before any new decoder request after process restart', requests['/live4.m3u8']==protected_reads)
    snapshot('25-locked-last-channel-gate')
    key(4); seek('متابعة آخر قناة',20,3); key(23); wait_pin(); pin_digits(); wait_live(names[3])

    open_safe_channels_while_protected_plays()
    admit_protected_group(); open_manager(); key(19); key(23)
    expect('A fresh category deletion confirmation defaults to Cancel', focused_on('إلغاء'))
    key(20); key(23); wait_pin(); key(4)
    expect('Cancelling locked deletion PIN restores the exact Delete action', focused_on('حذف التصنيف'))
    key(19); key(23); key(19); key(23); key(20); key(23); wait_pin(); pin_digits()
    wait_for(lambda v: visible('المحذوفة (1)',v), 'Deleting the category must expose a local restore route')
    seek('المحذوفة (1)',20,8); key(23)
    expect('Hidden-category list restores focus to its deleted group', focused_on('Protected'))
    key(23); wait_pin(); key(4)
    expect('Cancelling restore PIN returns to the same hidden category row', focused_on('Protected'))
    key(23); wait_pin(); pin_digits()
    wait_for(lambda v: focused_on('رجوع',v), 'Restored empty hidden list should focus Back')
    key(23); seek('Protected',20,8)
    snapshot('26-category-restored-with-protection')
    return_to_playback(); wait_live(names[0])
    key(20); wait_live(names[1]); advancing_frames('27-before-outage')

    fault['media'] = True
    deadline = time.monotonic()+50
    buffering = None
    while time.monotonic()<deadline:
        key(22); values=nodes()
        expect('Temporary media outage does not show a blocking retry dialog', not visible('تحديث الرابط وإعادة المحاولة',values))
        if visible('جارٍ إعادة الاتصال',values) or visible('جارٍ التحميل',values): buffering=values; break
        time.sleep(1)
    expect('Controlled media outage reaches actual loading/reconnect state', buffering is not None and failures['outage']>0)
    snapshot('28-buffering-during-network-outage',buffering)
    frozen = frame_digest('28-buffered-video')
    time.sleep(8)
    successful_media = sum(count for path,count in successes.items() if path.endswith(('.m3u8','.ts')))
    fault['media'] = False
    wait_live(names[1],90)
    expect('Same station reconnects after provider recovery without a channel change', sum(count for path,count in successes.items() if path.endswith(('.m3u8','.ts')))>successful_media)
    advancing_frames('29-video-after-reconnect',frozen)
    # Visible transport: Back hides controls and restores video focus before a second Back exits.
    values = wait_live(names[1])
    expect('The revealed TV HUD has no Previous control',hud_visible(values) and not visible('السابقة',values))
    first_back_started = time.monotonic()
    first_back_failures = len(native_dump_failures)
    key(22)  # Reset the HUD's five-second idle timer; do not age it with another hierarchy read.
    key(4)
    # Inspect the first native state, not a long poll that could pass only after idle auto-hide.
    values = nodes()
    first_back_observation = {'elapsedSeconds':round(time.monotonic()-first_back_started,3),
        'nativeDumpFailures':len(native_dump_failures)-first_back_failures}
    (out/'first-back-observation.json').write_text(json.dumps(first_back_observation,indent=2))
    if first_back_observation['nativeDumpFailures'] or first_back_observation['elapsedSeconds'] >= 4.5:
        raise RuntimeError('First Back observation was delayed; immediate hiding before the five-second idle timer was not established')
    expect('First Back immediately hides the HUD and restores focused video',
           not hud_visible(values) and focused_on('قائمة القنوات',values))
    expect('First Back keeps the native player mounted with focused video and no keyboard',
           player_surface_visible(values) and not ime())
    snapshot('29b-first-back-hides-hud',values)
    advancing_frames('29c-video-continues-after-first-back')
    expect('Video focus restoration does not reveal the HUD again',not hud_visible())
    reads = requests['/list.m3u']
    key(4)
    values = wait_for(lambda v: not player_surface_visible(v) and visible('التلفزيون',v),
                      'Second Back must exit television after the HUD was dismissed')
    expect('Second Back exits television instead of reopening the HUD',not hud_visible(values))
    snapshot('29d-second-back-exits-television',values)
    started=start_tv(); wait_live(names[1]); reentry_seconds.append(round(time.monotonic()-started,2))
    expect('Warm section reentry restores the successful last channel without a catalogue request', requests['/list.m3u']==reads)
    fault['catalogue']=True
    adb('shell','am','force-stop','com.cinemaplus.rebuilt'); started=start_tv('30-cached-cold-entry'); wait_live(names[1]); reentry_seconds.append(round(time.monotonic()-started,2))
    expect('Process restart uses the encrypted cached catalogue when catalogue network access is unavailable', requests['/list.m3u']==reads)
    advancing_frames('30-cached-last-channel-video')
    snapshot('31-final-cached-last-channel')
    values = wait_live(names[1]); time.sleep(6)
    values = wait_for(lambda v: not hud_visible(v) and focused_on('قائمة القنوات',v),
                      'Idle TV HUD must hide automatically and return video focus')
    snapshot('32-auto-hidden-hud-before-back',values)
    key(4)
    values = wait_for(lambda v: not player_surface_visible(v) and visible('التلفزيون',v),
                      'One Back must exit television when the HUD was already hidden')
    expect('Back on an auto-hidden HUD exits without first revealing controls',not hud_visible(values))
    snapshot('33-back-exits-after-auto-hide',values)
except Exception as exception:
    error = type(exception).__name__+': '+str(exception)
    try: snapshot('glass-failure')
    except Exception: pass
finally:
    collect_diagnostics('glass-final')
    if server: server.shutdown()
    shutil.rmtree(fixture,ignore_errors=True)
    result={'passed':error is None and all(check['passed'] for check in checks),'checks':checks,'error':error,
        'apkReusedWithoutBuild':True,'typedRealCredentials':False,'usesGeneratedLocalHls':True,
        'fixtureRequests':dict(requests),'injectedFailures':dict(failures),'reentrySeconds':reentry_seconds,
        'savedSourceStartupObservations':startup_observations,
        'nativeHierarchyDumpFailures':native_dump_failures,
        'nativeHierarchyDumpRetryPolicy':'At most three fresh dumps for native UIAutomator null-child crash/exit137 with the same app PID; no action, PIN or assertion retries and no stale XML fallback',
        'firstBackObservation':first_back_observation,
        'startupFlashCoverage':'Sampled native UI hierarchies and screenshots from the saved-source entry tap to player/PIN; sub-frame flashes are not measured',
        'realProviderTested':False,'arabicUnicodeImeInputTested':False,
        'arabicNormalizationCoverage':'Four source unit tests; native runtime uses Latin group names and channel numbers',
        'readyStateFrozenClockTested':False,'recoveryCoverage':'Real HTTP outage and buffering with advancing screenshot timecode'}
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
    if not result['passed']: raise SystemExit(1)
