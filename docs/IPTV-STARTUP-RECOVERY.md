# IPTV startup and connection recovery

User reports slow TV entry showing an empty channel list/loading bar, and transient stream failures that recover only after switching away/back.

Reserved files: IptvTvViewModel.kt, IptvClient.kt, IptvTvPlayer.kt, new catalog cache/repository and reconnect policy files, and their unit tests. Existing source credentials, favorite/history keys, form/keyboard behavior and design remain compatible. No APK build/publication without the separate authorized build request.

Implement cache-first startup with encrypted device-local bounded channel snapshots, background catalog refresh, parsing off the UI thread, and cancellable automatic live-stream recovery. Record actual compile/unit/R8 and runtime coverage in the PR.
