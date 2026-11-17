"""Exercise the approved signed APK with real D-pad/IME events and generated local IPTV media."""
import base64
from collections import Counter
from datetime import datetime, timedelta, timezone
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

# Reuse all native input checks before exercising the player; no app test hooks or APK rebuild.
baseline = runpy.run_path(str(Path(__file__).with_name('iptv-input-validation.py')))
adb, key, nodes, label, focus, ime, record = [baseline[n] for n in ('adb', 'key', 'nodes', 'label', 'focus', 'ime', 'record')]
out, checks = baseline['out'], baseline['checks']
requests = Counter()
failures = Counter()
fixture = Path(tempfile.mkdtemp(prefix='cinema-iptv-fixture-'))
server = None
error = None


def snapshot(name, values=None):
    values = nodes() if values is None else values
    (out/(name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p').stdout)
    (out/(name+'.json')).write_text(json.dumps([n.attrib for n in values], ensure_ascii=False, indent=2))
    return values


def focused_on(text, values=None):
    values = nodes() if values is None else values
    return any(n.get('focused') == 'true' and text in ' '.join(label(c) for c in n.iter()) for n in values)


def visible(text, values=None):
    return any(text in label(n) for n in (nodes() if values is None else values))


def assert_case(name, condition):
    record(name, condition)
    if not condition:
        snapshot('controls-failure')
        raise AssertionError(name)


def toolbar(text):
    key(22)
    for _ in range(8):
        values = nodes()
        if focused_on(text, values): return values
        key(21)
    raise AssertionError('Remote cannot reach toolbar: '+text)


def start_tv():
    # A release activity is intentionally private; re-enter through its public launcher and TV rail.
    adb('shell','am','start','-W','-n','com.cinemaplus.rebuilt/.MainActivity')
    baseline['tap'](baseline['find']('التلفزيون', timeout=35))


def wait_live(channel, timeout=45):
    until = time.monotonic()+timeout
    while time.monotonic() < until:
        key(22)
        values = nodes()
        if visible(channel, values) and visible('بث مباشر', values): return values
        assert not visible('تحديث الرابط وإعادة المحاولة', values), 'Transient failure produced a blocking error dialog'
        time.sleep(1)
    raise AssertionError('Generated HLS did not reach real Media3 playing state: '+channel)


try:
    # A public-domain single-pixel logo keeps this fixture independent of outside image servers.
    (fixture/'logo.png').write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=='))
    host = 'http://10.0.2.2:8765'
    playlist = '#EXTM3U x-tvg-url="'+host+'/guide.xml"\n'
    for number in (1,2):
        playlist += f'#EXTINF:-1 tvg-id="fixture{number}" tvg-logo="{host}/logo.png" group-title="Fixture",Cinema Fixture {number}\n{host}/live{number}.m3u8\n'
    (fixture/'list.m3u').write_text(playlist)
    now = datetime.now(timezone.utc)
    stamp = lambda date: date.strftime('%Y%m%d%H%M%S +0000')
    programmes = ''.join(f'<programme channel="fixture{number}" start="{stamp(now-timedelta(minutes=10))}" stop="{stamp(now+timedelta(minutes=20))}"><title lang="ar">برنامج اختبار حالي</title></programme><programme channel="fixture{number}" start="{stamp(now+timedelta(minutes=20))}" stop="{stamp(now+timedelta(minutes=50))}"><title lang="ar">برنامج اختبار تالي</title></programme>' for number in (1,))
    (fixture/'guide.xml').write_text('<?xml version="1.0" encoding="UTF-8"?><tv>'+programmes+'</tv>')

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs): super().__init__(*args, directory=str(fixture), **kwargs)
        def log_message(self, *args): pass
        def do_GET(self):
            path = urlsplit(self.path).path
            requests[path] += 1
            # Exceed Media3's default manifest retry threshold, then allow the app to reconnect.
            if path == '/live1.m3u8' and failures[path] < 5:
                failures[path] += 1; self.send_response(503); self.end_headers(); return
            super().do_GET()

    assert shutil.which('ffmpeg'), 'The runner needs ffmpeg to generate its own test media'
    for height, bitrate in ((360, '220k'), (720, '650k')):
        directory=fixture/str(height); directory.mkdir()
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y',
            '-f','lavfi','-i',f'testsrc2=size={height*16//9}x{height}:rate=10',
            '-f','lavfi','-i','sine=frequency=440:sample_rate=48000',
            '-t','600','-c:v','libx264','-preset','ultrafast','-threads','2','-pix_fmt','yuv420p',
            '-b:v',bitrate,'-g','20','-keyint_min','20','-sc_threshold','0',
            '-c:a','aac','-b:a','48k','-ac','2','-f','hls','-hls_time','2','-hls_playlist_type','vod',
            '-hls_segment_filename',str(directory/'segment-%03d.ts'),str(directory/'index.m3u8')],
            check=True,timeout=180)
    master='#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360\n360/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=750000,RESOLUTION=1280x720\n720/index.m3u8\n'
    for number in (1,2): (fixture/f'live{number}.m3u8').write_text(master)
    server = ThreadingHTTPServer(('0.0.0.0',8765),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    key(23)  # Add Source is focused by the successful baseline.
    baseline['find']('إضافة مصدر IPTV')
    key(20); key(23); adb('shell','input','text','GeneratedFixture'); key(66)
    baseline['open_editor']('20-fixture-address')
    adb('shell','input','text',host+'/list.m3u'); key(66)
    assert_case('Fixture URL completion closes IME and restores HTTP control',not ime() and focused_on('HTTP'))
    key(23); key(20); key(23)  # Explicit HTTP consent, Connect, OK.
    values = wait_live('Cinema Fixture 1')
    assert_case('Automatic recovery plays generated HLS after five injected HTTP 503 failures',failures['/live1.m3u8']==5 and requests['/live1.m3u8']>=6)
    assert_case('Provider current and next programme names appear in the playing HUD',visible('برنامج اختبار حالي',values) and visible('برنامج اختبار تالي',values))
    assert_case('TV HUD contains no pause or close action',not visible('إيقاف مؤقت',values) and not visible('إغلاق',values))
    snapshot('21-live-hud',values)

    for target, title, prefix in [('الجودة:', 'جودة البث','22-quality'),('الصوت:', 'مسار الصوت','23-audio'),('الترجمة', 'الترجمة','24-subtitles')]:
        toolbar(target); key(23)
        values=nodes()
        assert_case(title+' opens a focused compact popover with one OK',visible(title,values) and (focused_on('تلقائي',values) or focused_on('إيقاف الترجمة',values)))
        snapshot(prefix,values)
        time.sleep(6)
        assert_case(title+' stays open beyond the HUD inactivity timeout',visible(title) and (focused_on('تلقائي') or focused_on('إيقاف الترجمة')))
        key(4)
        assert_case('Back restores the '+title+' toolbar icon',focused_on(target))
        key(23)
        assert_case('A single OK reopens '+title+' after Back',visible(title))
        key(4)

    time.sleep(6)
    values = nodes()
    assert_case('Five-second inactivity hides the HUD even after a toolbar item held focus',not visible('الجودة:',values))
    assert_case('HUD dismissal restores the playback focus surface',focused_on('قائمة القنوات',values))
    snapshot('25-hud-hidden',values)
    key(22)
    values=nodes()
    assert_case('Remote interaction reveals HUD and reaches its first action',visible('الجودة:',values) and focused_on('القنوات',values))
    time.sleep(6); key(23)
    values=nodes()
    assert_case('One OK from the hidden HUD opens the channel list',visible('ابحث عن قناة',values) and focused_on('Cinema Fixture 1',values))
    time.sleep(6)
    assert_case('Channel list remains open during inactivity',visible('ابحث عن قناة'))
    key(22)
    assert_case('Physical Right opens classifications',visible('التصنيفات') and focused_on('كل القنوات'))
    snapshot('26-channel-classifications')
    key(21)
    assert_case('Physical Left restores the previously focused channel',focused_on('Cinema Fixture 1'))
    key(20); key(23)
    values=wait_live('Cinema Fixture 2')
    assert_case('A channel without EPG shows the exact no-information fallback',visible('لا تتوفر معلومات',values))
    snapshot('27-second-channel',values)
    reads=requests['/list.m3u']
    key(4); key(4); start_tv()
    snapshot('28-warm-restore',wait_live('Cinema Fixture 2'))
    assert_case('Activity reentry restores the last channel without downloading the playlist',requests['/list.m3u']==reads)
    adb('shell','am','force-stop','com.cinemaplus.rebuilt'); start_tv()
    snapshot('29-disk-restore',wait_live('Cinema Fixture 2'))
    assert_case('Process restart restores the encrypted catalogue and last channel without playlist download',requests['/list.m3u']==reads)
except Exception as exception:
    error=type(exception).__name__+': '+str(exception)
    try: snapshot('controls-failure')
    except Exception: pass
finally:
    if server: server.shutdown()
    shutil.rmtree(fixture,ignore_errors=True)
    result={'passed':error is None and all(c['passed'] for c in checks),'checks':checks,'error':error,
        'apkReusedWithoutBuild':True,'typedRealCredentials':False,'usesGeneratedLocalHls':True,
        'fixtureRequests':dict(requests),'injectedFailures':dict(failures),'realProviderTested':False}
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
    if not result['passed']: raise SystemExit(1)
