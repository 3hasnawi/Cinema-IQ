"""Real remote/IME, category protection and Media3 recovery on an approved existing APK."""
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit

# Run the existing real native Next/Done/Back source-form baseline first, on this same APK.
# Only fake .invalid credentials are entered; the source is never submitted to a provider.
baseline = runpy.run_path(str(Path(__file__).with_name('iptv-input-validation.py')))
adb, key, nodes, label, focus, ime, record = [baseline[n] for n in ('adb', 'key', 'nodes', 'label', 'focus', 'ime', 'record')]
out, checks = baseline['out'], baseline['checks']
fixture = Path(tempfile.mkdtemp(prefix='cinema-tv-glass-fixture-'))
requests, failures, successes = Counter(), Counter(), Counter()
fault = {'media': False, 'catalogue': False}
server, error = None, None
reentry_seconds = []
names = ['Cinema Fixture 1', 'Cinema Fixture 2', 'الأخبار', 'Cinema Protected 1']


def snapshot(name, values=None):
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


def start_tv():
    started = time.monotonic()
    adb('shell', 'am', 'start', '-W', '-n', 'com.cinemaplus.rebuilt/.MainActivity')
    # Public rail entry may be touched; every tested in-section interaction uses physical D-pad/IME events.
    baseline['tap'](baseline['find']('التلفزيون', timeout=35))
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
        playlist += f'#EXTINF:-1 tvg-id="fixture{number}" tvg-logo="{host}/logo{number}.png" group-title="{group}",{name}\n{host}/live{number}.m3u8\n'
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
    snapshot('21-glass-playing-hud',values)
    advancing_frames('22-initial-native-video')

    open_channels(names[0]); key(22); seek('Fixture',20)
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
    open_channels(names[0]); choose_group('Protected'); wait_pin(); pin_digits('9876')
    wait_for(lambda v: visible('رمز غير صحيح',v), 'Incorrect physical PIN must be rejected')
    key(4); expect('PIN cancellation restores the originating category', focused_on('Protected'))
    key(21); search('Protected'); key(66)
    wait_for(lambda v: not ime() and visible('لا توجد قنوات مطابقة',v), 'Search must exclude the locked station')
    key(4); choose_group('المفضلة')
    expect('Favorites cannot bypass a newly locked category', visible('لا توجد قنوات مطابقة'))
    key(22); seek('كل القنوات',19,8); key(23)
    expect('All Channels does not expose the locked station', not visible(names[3]))
    snapshot('24-locked-search-and-favorites')
    choose_group('Protected'); wait_pin(); pin_digits()
    wait_for(lambda v: focused_on(names[3],v), 'Successful PIN must focus the unlocked station'); key(23); wait_live(names[3])
    protected_reads = requests['/live4.m3u8']
    adb('shell','am','force-stop','com.cinemaplus.rebuilt'); start_tv(); wait_pin()
    expect('Locked last channel requires PIN before any new decoder request after process restart', requests['/live4.m3u8']==protected_reads)
    snapshot('25-locked-last-channel-gate')
    key(4); seek('متابعة آخر قناة',20,3); key(23); wait_pin(); pin_digits(); wait_live(names[3])

    open_channels(names[3]); open_manager(); key(19); key(23)
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
    reads = requests['/list.m3u']; key(4); started=start_tv(); wait_live(names[1]); reentry_seconds.append(round(time.monotonic()-started,2))
    expect('Warm section reentry restores the successful last channel without a catalogue request', requests['/list.m3u']==reads)
    fault['catalogue']=True
    adb('shell','am','force-stop','com.cinemaplus.rebuilt'); started=start_tv(); wait_live(names[1]); reentry_seconds.append(round(time.monotonic()-started,2))
    expect('Process restart uses the encrypted cached catalogue when catalogue network access is unavailable', requests['/list.m3u']==reads)
    advancing_frames('30-cached-last-channel-video')
    snapshot('31-final-cached-last-channel')
except Exception as exception:
    error = type(exception).__name__+': '+str(exception)
    try: snapshot('glass-failure')
    except Exception: pass
finally:
    if server: server.shutdown()
    shutil.rmtree(fixture,ignore_errors=True)
    result={'passed':error is None and all(check['passed'] for check in checks),'checks':checks,'error':error,
        'apkReusedWithoutBuild':True,'typedRealCredentials':False,'usesGeneratedLocalHls':True,
        'fixtureRequests':dict(requests),'injectedFailures':dict(failures),'reentrySeconds':reentry_seconds,
        'realProviderTested':False,'arabicUnicodeImeInputTested':False,
        'arabicNormalizationCoverage':'Four source unit tests; native runtime uses Latin group names and channel numbers',
        'readyStateFrozenClockTested':False,'recoveryCoverage':'Real HTTP outage and buffering with advancing screenshot timecode'}
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
    if not result['passed']: raise SystemExit(1)
