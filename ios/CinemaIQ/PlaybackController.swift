import SwiftUI
import AVKit
import AVFoundation
import CinemaCore

struct PlaybackSelection: Identifiable {
    let media: Media
    let queue: [Media]
    /// The series an episode belongs to, when the page that started it knows it (for skip timings).
    var series: Media? = nil
    var id: String { media.id }
}

/// Opens the full-screen player from any tab.
@MainActor final class PlayerPresenter: ObservableObject {
    @Published var selection: PlaybackSelection?
    func play(_ media: Media, queue: [Media] = [], series: Media? = nil) {
        selection = PlaybackSelection(media: media, queue: queue, series: series)
    }
}

struct MediaOption: Identifiable, Hashable {
    let id: String
    let title: String
}

/// Owns the AVPlayer. Every control of the player (quality, subtitles, audio, speed) goes through here so the
/// screen has one set of controls instead of the system ones plus an overlay.
@MainActor final class PlaybackController: ObservableObject {
    let player = AVPlayer()
    let compatibility = CompatibilityPlayback()
    @Published private(set) var usesCompatibility = false
    @Published private(set) var hasNativeItem = false
    @Published private(set) var mode: IPTVPlaybackMode
    @Published private(set) var lastFailure: PlaybackFailure?
    private var currentURL: URL?
    private var maximumHeight: Int?
    private var preferredBandwidth: Double = 0
    private var highest = false
    private var fallbackAttempted = false
    private var wantsPlay = true
    private var restartOnResume = false
    private var pendingMovieSeek: Double?
    private var nativeSeeks = PlaybackSeekQueue()
    private var pendingMode: IPTVPlaybackMode?
    private let enginePreference: PlaybackEnginePreference
    var hasItem: Bool { currentURL != nil }
    /// Unlike the decoder clock, this also represents intent during asynchronous opening.
    var playbackRequested: Bool { wantsPlay }
#if DEBUG
    var nativeSeekPendingForTesting: Bool { nativeSeeks.isPending }
    @Published private(set) var remotePlayPauseCountForTesting = 0
    @Published private(set) var nativeTraceForTesting = ""
    private var nativeTraceEvents: [String] = []
    private var jumpTokenForTesting: NSObjectProtocol?
    private var nativeItemGenerationForTesting = 0

    func recordRemotePlayPauseForTesting() {
        guard ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-player") else { return }
        remotePlayPauseCountForTesting += 1
        recordNativeEventForTesting("remote-\(remotePlayPauseCountForTesting)")
    }

    func recordNativePresentationForTesting(_ event: String) { recordNativeEventForTesting(event) }

    private func recordNativeEventForTesting(_ event: String) {
        guard ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-player") else { return }
        let seconds = player.currentTime().seconds
        let actual = seconds.isFinite ? String(format: "%.3f", seconds) : "invalid"
        nativeTraceEvents.append("i\(nativeItemGenerationForTesting),\(event):\(actual)s,r\(player.rate),s\(player.timeControlStatus.rawValue),intent\(wantsPlay)")
        nativeTraceEvents = Array(nativeTraceEvents.suffix(16))
        nativeTraceForTesting = nativeTraceEvents.joined(separator: "; ")
    }
#endif

    /// Remote playback has its own progress clock; never overwrite it with the paused local item.
    var externalPlayback = false
    @Published var error: String?
    @Published var notice: String?
    @Published var ended = false
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var playing = false
    @Published var buffering = false
    @Published var rate: Float = 1
    /// The subtitle line on screen now.
    @Published var cue: String?
    /// nil = off, "ext:<url>" = the source's file, "emb:<n>" = a track inside the video.
    @Published var subtitleID: String?
    @Published var embeddedSubtitles: [MediaOption] = []
    @Published var audioOptions: [MediaOption] = []
    @Published var audioID: String?
    var remaining: Double? { duration > 0 ? max(0, duration - position) : nil }

    private var legibleGroup: AVMediaSelectionGroup?
    private var audibleGroup: AVMediaSelectionGroup?
    private var cues: [SubtitleCue] = []
    /// The language chosen automatically for a new title (from Settings); cleared once the viewer turns
    /// subtitles off, so they are not switched back on by themselves.
    private(set) var preferredLanguage: String? = "ar"
    private var timer: Any?
    private var endToken: NSObjectProtocol?
    private var failureToken: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var controlObservation: NSKeyValueObservation?
    private var save: ((Double, Double, Bool) -> Void)?
    private var audioLease: UUID?
    private var openingTask: Task<Void, Never>?
    private var selectionTask: Task<Void, Never>?
    private var subtitleTask: Task<Void, Never>?
    private var ticks = 0
    private var watchedThroughCredits = false
    private(set) var persistedCompletion = false
    private(set) var currentMediaID: String?
    private static let arabicWindows = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
        CFStringEncoding(CFStringEncodings.windowsArabic.rawValue)))

    init(enginePreference: PlaybackEnginePreference = .shared, automaticEngine: Bool = false) {
        self.enginePreference = enginePreference
        mode = automaticEngine ? .automatic : enginePreference.mode(in: .standard)
        player.appliesMediaSelectionCriteriaAutomatically = false
#if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if !args.contains("--cinema-ui-testing-engine-preferences"),
           args.contains("--cinema-ui-testing-player") || args.contains("--cinema-tv-ui-testing-iptv-player") { mode = .automatic }
#endif
    }

    func open(_ media: Media, url: URL, resume: Double, initiallyPlaying: Bool = true, save: @escaping (Double, Double, Bool) -> Void) {
        close(); self.save = save; error = nil; lastFailure = nil; ended = false; position = max(0, resume); duration = 0; ticks = 0
        watchedThroughCredits = false; persistedCompletion = false; currentMediaID = media.id
        currentURL = url; fallbackAttempted = false; wantsPlay = initiallyPlaying
        let lease = UUID(); audioLease = lease; buffering = true
        openingTask = Task { [weak self] in
            // A new native movie must not race the previous compatibility decoder's shutdown either.
            await CompatibilityPlayback.waitForShutdown()
            do {
                try await PlaybackAudioSession.shared.acquire(lease)
                guard !Task.isCancelled, let self, audioLease == lease else {
                    await PlaybackAudioSession.shared.release(lease); return
                }
                openingTask = nil
                // The phone pauses immediately after open() when its scene is inactive.
                // Preserve that intent (and an early seek/engine choice) across activation.
                if mode.startsWithCompatibility(url) { startCompatibility(resume: position, playing: wantsPlay) }
                else { openNative(url: url, resume: position, playing: wantsPlay) }
            } catch {
                guard !Task.isCancelled, let self, audioLease == lease else { return }
                openingTask = nil; self.error = error.localizedDescription; buffering = false
            }
        }
    }

    private func openNative(url: URL, resume: Double, playing shouldPlay: Bool = true) {
        usesCompatibility = false; ended = false; wantsPlay = shouldPlay; restartOnResume = false; pendingMode = nil
        let item = AVPlayerItem(url: url)
        nativeSeeks.reset()
        if resume.isFinite && resume > 0 { nativeSeeks.enqueue(resume) }
        player.replaceCurrentItem(with: item)
        hasNativeItem = true
#if DEBUG
        nativeItemGenerationForTesting += 1
        recordNativeEventForTesting("replace")
        jumpTokenForTesting = NotificationCenter.default.addObserver(forName: .AVPlayerItemTimeJumped, object: item, queue: .main) { [weak self, weak item] _ in
            Task { @MainActor in
                guard let self, let item, self.player.currentItem === item else { return }
                self.recordNativeEventForTesting("time-jump")
            }
        }
#endif
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                if item.status == .failed { self.nativeFailure(item) }
                else if item.status == .readyToPlay { self.applyPendingNativeSeek(item) }
            }
        }
        controlObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            let observedItem = player.currentItem
            Task { @MainActor in
                guard self?.usesCompatibility == false, self?.player.currentItem === observedItem else { return }
                let status = player.timeControlStatus
                self?.playing = status != .paused
                self?.buffering = status == .waitingToPlayAtSpecifiedRate
#if DEBUG
                self?.recordNativeEventForTesting("transport")
#endif
                // AVKit's native remote can resume without calling this controller's
                // play(). Retry a seek interrupted during the initially paused opening.
                if status == .playing, let self, let observedItem { self.applyPendingNativeSeek(observedItem) }
            }
        }
        endToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                self.ended = true; self.persist(completed: true); self.player.pause()
            }
        }
        failureToken = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard self?.player.currentItem === item else { return }
                self?.nativeFailure(item)
            }
        }
        timer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        selectionTask = Task { [weak self] in await self?.loadSelectionGroups(item) }
        player.defaultRate = rate
        applyNativeQuality()
        if shouldPlay { player.play() }
    }

    private func applyPendingNativeSeek(_ item: AVPlayerItem) {
        guard player.currentItem === item, item.status == .readyToPlay,
              let attempt = nativeSeeks.begin() else { return }
#if DEBUG
        recordNativeEventForTesting("seek-\(String(format: "%.3f", attempt.seconds))s")
#endif
        player.seek(to: CMTime(seconds: attempt.seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak item] finished in
            Task { @MainActor in
                guard let self, let item, self.player.currentItem === item else { return }
                switch self.nativeSeeks.complete(attempt, finished: finished) {
                case .ignored, .awaitTrigger: break
                case .seekLatest: self.applyPendingNativeSeek(item)
                case .settled:
                    let actual = self.player.currentTime().seconds
                    if actual.isFinite { self.position = actual }
                }
#if DEBUG
                self.recordNativeEventForTesting(finished ? "seek-finished" : "seek-interrupted")
#endif
                // Seeking preserves the existing transport state. Do not replay stale
                // play/pause intent over a real AVKit remote action from this completion.
            }
        }
    }

    private func nativeFailure(_ item: AVPlayerItem) {
        let status = item.errorLog()?.events.last?.errorStatusCode
        let category = PlaybackFailure.classify(item.error, httpStatus: status)
        lastFailure = category
        if category.permitsDecoderFallback && mode == .automatic && !fallbackAttempted {
            fallbackAttempted = true; startCompatibility(resume: position, playing: wantsPlay)
        } else { error = category.message; buffering = false; playing = false }
    }

    private func startCompatibility(resume: Double, playing shouldPlay: Bool) {
        pendingMovieSeek = nil
        wantsPlay = shouldPlay; restartOnResume = false; pendingMode = nil
        let chosenAudio = audioID, chosenSubtitle = subtitleID
        releaseNative(); usesCompatibility = true; error = nil; lastFailure = nil; ended = false
        if subtitleID?.hasPrefix("emb:") == true { subtitleID = nil }
        guard let url = currentURL else { return }
        buffering = true
        compatibility.open(url: url, resume: resume, rate: rate, playing: shouldPlay, audioID: chosenAudio, subtitleID: chosenSubtitle, maximumHeight: maximumHeight, highest: highest) { [weak self] value in
            guard let self, usesCompatibility else { return }
            let nextPosition = pendingMovieSeek ?? value.position
            if position != nextPosition { position = nextPosition }
            if value.duration.isFinite, value.duration > 0, duration != value.duration { duration = value.duration }
            if playing != value.playing { playing = value.playing }
            if buffering != value.buffering { buffering = value.buffering }
            if audioOptions != value.audio { audioOptions = value.audio }
            if audioID != value.audioID { audioID = value.audioID }
            if embeddedSubtitles != value.subtitles { embeddedSubtitles = value.subtitles }
            if subtitleID == nil, let language = preferredLanguage {
                let names = language == "ar" ? ["arabic", "العربية", "عربي", "ara"] : language == "en" ? ["english", "الإنجليزية", "eng"] : [language]
                if let track = value.subtitles.first(where: { track in names.contains { track.title.localizedCaseInsensitiveContains($0) } }) {
                    subtitleID = track.id; compatibility.selectSubtitle(track.id)
                }
            }
            updateCue()
            ticks += 1
            if ticks % 20 == 0 { persist() }
            if value.ended && !ended { ended = true; persist(completed: true) }
            if value.failed { error = PlaybackFailure.unknown.message; lastFailure = .unknown; playing = false; buffering = false }
        }
    }

    func chooseEngine(_ value: IPTVPlaybackMode) {
        guard mode != value || error != nil else { return }
        let resume = position, shouldPlay = wantsPlay || error != nil
        persist(); mode = value; enginePreference.save(value, in: .standard)
        guard currentURL != nil else { return }
        error = nil; lastFailure = nil; fallbackAttempted = false
        // The pending activation reads the latest engine; do not start an item ahead of it.
        guard openingTask == nil else { return }
        if shouldPlay { restart(resume: resume, preferred: value) }
        else { restartOnResume = true; pendingMode = value }
    }

    private func restart(resume: Double, preferred: IPTVPlaybackMode? = nil) {
        guard let url = currentURL else { return }
        // A quality-only restart keeps a working fallback decoder; an explicit engine choice can replace it.
        let useCompatibility = (preferred ?? pendingMode).map { $0.startsWithCompatibility(url) } ?? usesCompatibility
        restartOnResume = false; pendingMode = nil
        if useCompatibility { startCompatibility(resume: resume, playing: true) }
        else {
            if subtitleID?.hasPrefix("vlcs:") == true { subtitleID = nil }
            compatibility.close(); releaseNative(); openNative(url: url, resume: resume, playing: true)
        }
    }

    func setQuality(maximumHeight: Int?, bandwidth: Double = 0, highest: Bool = false) {
        let changed = self.maximumHeight != maximumHeight || self.highest != highest
        self.maximumHeight = maximumHeight; self.preferredBandwidth = bandwidth; self.highest = highest
        if usesCompatibility {
            if changed {
                // Keep the paused decoder/frame intact. Reopening a paused VLC input need not render a frame.
                if wantsPlay { startCompatibility(resume: position, playing: true) }
                else { restartOnResume = true }
            }
        } else { applyNativeQuality() }
    }
    private func applyNativeQuality() {
        player.currentItem?.preferredMaximumResolution = maximumHeight.map { CGSize(width: Double($0) * 16 / 9, height: Double($0)) } ?? .zero
        // Quality can arrive while audio activation is pending and no item exists yet.
        player.currentItem?.preferredPeakBitRate = preferredBandwidth
    }
    func pause() {
        wantsPlay = false
#if DEBUG
        recordNativeEventForTesting("pause")
#endif
        if usesCompatibility { compatibility.pause() } else { player.pause() }; playing = false
    }
    func play() {
        wantsPlay = true
#if DEBUG
        recordNativeEventForTesting("play")
#endif
        guard openingTask == nil else { return }
        if restartOnResume { restart(resume: position) }
        else if usesCompatibility { compatibility.play() }
        else {
            guard let item = player.currentItem else { return }
            applyPendingNativeSeek(item)
            player.play()
        }
        playing = true
    }
    private func updateCue() {
        let line = cues.isEmpty ? nil : SubtitleCatalog.cue(at: position, in: cues)?.text
        if line != cue { cue = line }
    }

    private func tick() {
        guard let item = player.currentItem else { return }
        let now = player.currentTime().seconds, length = item.duration.seconds
        if now.isFinite && !nativeSeeks.isPending && position != now { position = now }
        if length.isFinite && length > 0 && duration != length { duration = length }
        updateCue()
        ticks += 1
        if ticks % 20 == 0 { persist() }
    }

    func persist(completed: Bool = false) {
        guard !externalPlayback, !persistedCompletion else { return }
        // The periodic clock/seek already maintains this snapshot. Do not synchronously query
        // a remote asset again during dismissal if its connection has stalled.
        guard position.isFinite, duration.isFinite, duration > 0 else { return }
        let watched = completed || ended || watchedThroughCredits
        if watched { persistedCompletion = true }
        save?(position, duration, watched)
    }

    /// Reaching credits updates the library while the credits keep playing.
    func creditAsWatched() {
        guard hasItem else { return }
        watchedThroughCredits = true
        persist()
    }

    func togglePlay() {
        if ended { watchedThroughCredits = false; persistedCompletion = false }
        if usesCompatibility {
            if ended { ended = false; position = 0; restartOnResume = true; play() }
            else if playing { pause() } else { play() }
            return
        }
        if player.timeControlStatus == .paused {
            if ended { ended = false; seek(to: 0) }
            play()
        } else {
            pause()
        }
    }
    func seek(to seconds: Double) {
        guard seconds.isFinite else { return }
        let target = max(0, duration > 0 ? min(seconds, duration) : seconds)
        position = target
        if usesCompatibility {
            if enginePreference != .shared && !wantsPlay {
                // VLC can retain the old clock while paused. Keep the selected movie time until resume,
                // then reopen at that time rather than allowing paused callbacks to erase the seek.
                pendingMovieSeek = target; restartOnResume = true
            }
            compatibility.seek(target); return
        }
        // While activation is pending, position is the intent passed into openNative.
        // Never send a seek to an empty AVPlayer and mistake that intent for completion.
        guard let item = player.currentItem else { return }
        nativeSeeks.enqueue(target)
        applyPendingNativeSeek(item)
    }
    func skip(_ delta: Double) { seek(to: position + delta) }
    func setRate(_ value: Float) {
        rate = value
        if usesCompatibility { compatibility.setRate(value); return }
        player.defaultRate = value
        if player.timeControlStatus != .paused { player.rate = value }
    }

    /// A new title starts with no subtitle chosen; the preferred language is picked automatically when offered.
    func resetSubtitles(preferred: String?) {
        subtitleTask?.cancel(); subtitleTask = nil
        subtitleID = nil; cues = []; cue = nil; preferredLanguage = preferred
    }

    func selectSubtitle(_ id: String?, external: [SubtitleTrack]) {
        subtitleTask?.cancel(); subtitleTask = nil
        subtitleID = id; cues = []; cue = nil; notice = nil
        if id == nil { preferredLanguage = nil }
        applyEmbeddedSelection()
        guard let id, id.hasPrefix("ext:"), let track = external.first(where: { "ext:" + $0.id == id }) else { return }
        let lease = audioLease
        subtitleTask = Task { [weak self] in
            do {
                let data = try await SubtitleCatalog.download(track)
                let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: Self.arabicWindows) ?? ""
                let parsed = SubtitleCatalog.cues(text)
                guard !Task.isCancelled, let self, audioLease == lease, subtitleID == id else { return }
                if parsed.isEmpty { notice = "ملف الترجمة غير صالح للعرض." }
                cues = parsed
            } catch {
                guard !Task.isCancelled, let self, audioLease == lease, subtitleID == id else { return }
                notice = "تعذّر تحميل الترجمة."
            }
        }
    }

    func selectAudio(_ id: String) {
        audioID = id
        if usesCompatibility { compatibility.selectAudio(id); return }
        guard let group = audibleGroup, let item = player.currentItem,
              let index = Int(id.dropFirst(4)), group.options.indices.contains(index) else { return }
        item.select(group.options[index], in: group)
    }

    private func applyEmbeddedSelection() {
        if usesCompatibility { compatibility.selectSubtitle(subtitleID?.hasPrefix("vlcs:") == true ? subtitleID : nil); return }
        guard let group = legibleGroup, let item = player.currentItem else { return }
        if let id = subtitleID, id.hasPrefix("emb:"), let index = Int(id.dropFirst(4)), group.options.indices.contains(index) {
            item.select(group.options[index], in: group)
        } else {
            item.select(nil, in: group)
        }
    }

    private func loadSelectionGroups(_ item: AVPlayerItem) async {
        let legible = (try? await item.asset.loadMediaSelectionGroup(for: .legible)) ?? nil
        guard !Task.isCancelled, player.currentItem === item else { return }
        let audible = (try? await item.asset.loadMediaSelectionGroup(for: .audible)) ?? nil
        guard !Task.isCancelled, player.currentItem === item else { return }
        legibleGroup = legible; audibleGroup = audible
        embeddedSubtitles = (legible?.options ?? []).enumerated().map { MediaOption(id: "emb:\($0.offset)", title: $0.element.displayName) }
        audioOptions = (audible?.options ?? []).enumerated().map { MediaOption(id: "aud:\($0.offset)", title: $0.element.displayName) }
        if let audible, let selected = item.currentMediaSelection.selectedMediaOption(in: audible),
           let index = audible.options.firstIndex(of: selected) { audioID = "aud:\(index)" }
        if subtitleID == nil, let preferred = preferredLanguage, let legible,
           let index = legible.options.firstIndex(where: { $0.locale?.language.languageCode?.identifier == preferred }) {
            subtitleID = "emb:\(index)"
        }
        applyEmbeddedSelection()
    }

    func close() {
        persist()
        openingTask?.cancel(); openingTask = nil
        subtitleTask?.cancel(); subtitleTask = nil
        let lease = audioLease; audioLease = nil
        let retirement = compatibility.close()
        releaseNative(); usesCompatibility = false
        currentURL = nil; currentMediaID = nil; save = nil; playing = false; buffering = false; restartOnResume = false; pendingMode = nil; pendingMovieSeek = nil
        if let lease {
            // Returning to details must not wait for a slow decoder/network/audio shutdown.
            // The lease guard also protects a movie opened immediately after pressing Back.
            Task {
                await retirement?.value
                await PlaybackAudioSession.shared.release(lease)
            }
        }
    }

    private func releaseNative() {
        // Invalidate completion ownership before cancellation invokes its callbacks.
        nativeSeeks.reset()
#if DEBUG
        if let jumpTokenForTesting { NotificationCenter.default.removeObserver(jumpTokenForTesting) }
        jumpTokenForTesting = nil
#endif
        selectionTask?.cancel(); selectionTask = nil
        player.currentItem?.cancelPendingSeeks()
        player.currentItem?.asset.cancelLoading()
        player.pause()
        if let timer { player.removeTimeObserver(timer) }; timer = nil
        if let endToken { NotificationCenter.default.removeObserver(endToken) }; endToken = nil
        if let failureToken { NotificationCenter.default.removeObserver(failureToken) }; failureToken = nil
        statusObservation = nil; controlObservation = nil
        legibleGroup = nil; audibleGroup = nil; embeddedSubtitles = []; audioOptions = []; audioID = nil
        player.replaceCurrentItem(with: nil)
        hasNativeItem = false
    }
}
