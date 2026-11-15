# Cinema iQ for Windows

Native Windows 10/11 x64 desktop adaptation of the Android TV experience. This platform lives under `windows/`; Android and Apple applications remain independent.

The project uses WPF on .NET 10 LTS, the existing Cairo fonts and brand artwork, and a bundled LibVLC decoder. The deliverable is one self-contained EXE installer: users do not need to install .NET or VLC separately. This is a native port; Android/Google Play packages are not used by the Windows app.

## Work in progress

This branch implements providers, the TV-inspired shell, playback, IPTV, local profiles/library, metadata and account integration. The PR will record actual build/test results and remaining platform limitations before delivery.
