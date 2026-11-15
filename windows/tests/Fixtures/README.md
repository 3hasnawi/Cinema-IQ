# Native player fixture

`compatibility-pattern.mkv` is copied unchanged from the existing Apple test
fixture. It is dedicated to the public domain under CC0-1.0: 120 seconds of
FFmpeg-generated SMPTE bars (MPEG-2 video), two synthesized MP2 tones (440 Hz
English and 880 Hz Arabic metadata), and a SubRip subtitle track. There is no
third-party footage, music, authentication data or external stream address.

Only the isolated `--smoke-test` command loads this file. It is not copied into
the installed application. The native Windows smoke test decodes it, selects
its audio/subtitle tracks, pauses/resumes and shuts down LibVLC. This is not
evidence that a particular provider subscription or DRM stream works.
