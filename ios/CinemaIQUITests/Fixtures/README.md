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
