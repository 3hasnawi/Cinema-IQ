`player-pattern.mp4` is a generated, silent ten-minute SMPTE color-bar reference
clip (H.264 baseline, 640×360, 24 fps), dedicated to the public domain under
CC0-1.0. It contains no third-party footage, credentials or network addresses.

Generate it with FFmpeg:

```sh
ffmpeg -v error -f lavfi -i smptebars=size=640x360:rate=24 -t 600 \
  -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p \
  -preset veryfast -crf 28 -an -movflags +faststart \
  -metadata title='Cinema iQ UI test pattern' \
  -metadata comment='Generated SMPTE color bars; test fixture, CC0-1.0' \
  player-pattern.mp4
```

The validation workflow copies it into the compiled **Debug simulator app**.
`--cinema-ui-testing-player` selects it and holds the playback controls open so
slow accessibility snapshots can inspect them. Tests still exercise the existing
AVPlayer, time observer, rotation, pause, subtitle panel and library persistence.
The normal source URL and four-second controls timer are used outside that flag;
the hook is excluded from Release compilation and this fixture is outside the
application target's source/resources directory.

Live HLS/provider availability is a separate network integration check; these
tests verify the UI without relying on a public CDN or simulator network access.

`compatibility-pattern.mkv` is also CC0-1.0, generated entirely with FFmpeg:
120 seconds of SMPTE bars (MPEG-2 video), two synthesized MP2 tones (440 Hz
English and 880 Hz Arabic track metadata), and one English SubRip track reading
“Compatibility subtitle fixture”. It contains no third-party footage or audio.
The IPTV simulator workflows alone copy it as `UICompatibilityFixture.mkv`;
the DEBUG-only `--cinema-ui-testing-compatibility` flag selects it. This tests
actual decoding, embedded track selection, pause, rotation/remote controls and
channel changes through VLCKit instead of treating a codec error message as proof
of codec support. It is never an application Release resource.

`movie-audio-pattern.mp4` is a CC0-1.0 three-minute excerpt of the generated
H.264 bars with a synthesized 440 Hz AAC tone (32 kb/s). The movie-menu workflow
uses it for real native audio/video playback, two minutes of decoding, Back and
immediate resume; the compatibility exit test uses the MPEG-2/MP2 fixture above.
It is outside Release resources. Recreate with FFmpeg by copying the first 180
seconds of `player-pattern.mp4` and muxing a `sine=frequency=440:sample_rate=44100`
input, `-c:v copy -c:a aac -b:a 32k -movflags +faststart`.
