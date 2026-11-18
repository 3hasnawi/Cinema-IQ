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
    private var highest = false
    private var fallbackAttempted = false
    private var wantsPlay = true
    private var restartOnResume = false
    private var pendingMovieSeek: Double?
    private var pendingMode: IPTVPlaybackMode?
    private let enginePreference: PlaybackEnginePreference
    var hasItem: Bool { currentURL != nil }

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
    private static let arabicWindows = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
        CFStringEncoding(CFStringEncodings.windowsArabic.rawValue)))

    init(enginePreference: PlaybackEnginePreference = .shared) {
        self.enginePreference = enginePreference
        mode = enginePreference.mode(in: .standard)
        player.appliesMediaSelectionCriteriaAutomatically = false
#if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if !args.contains("--cinema-ui-testing-engine-preferences"),
           args.contains("--cinema-ui-testing-player") || args.contains("--cinema-tv-ui-testing-iptv-player") { mode = .automatic }
#endif
    }

    func open(_ media: Media, url: URL, resume: Double, save: @escaping (Double, Double, Bool) -> Void) {
        close(); self.save = save; error = nil; lastFailure = nil; ended = false; position = max(0, resume); duration = 0; ticks = 0
        currentURL = url; fallbackAttempted = false; wantsPlay = true
        let lease = UUID(); audioLease = lease; buffering = true
        openingTask = Task { [weak self] in
            // A new native movie must not race the previous compatibility decoder's shutdown either.
            await CompatibilityPlayback.waitForShutdown()
            do {
                try await PlaybackAudioSession.shared.acquire(lease)
                guard !Task.isCancelled, let self, audioLease == lease else {
                    await PlaybackAudioSession.shared.release(lease); return
                }
                if mode.startsWithCompatibility(url) { startCompatibility(resume: resume, playing: true) }
                else { openNative(url: url, resume: resume) }
            } catch {
                guard !Task.isCancelled, let self, audioLease == lease else { return }
                self.error = error.localizedDescription; buffering = false
            }
        }
    }

    private func openNative(url: URL, resume: Double, playing shouldPlay: Bool = true) {
        usesCompatibility = false; ended = false; wantsPlay = shouldPlay; restartOnResume = false; pendingMode = nil
        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        hasNativeItem = true
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            if item.status == .failed {
                Task { @MainActor in
                    guard self?.player.currentItem === item else { return }
                    self?.nativeFailure(item)
                }
            }
        }
        controlObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            let observedItem = player.currentItem
            Task { @MainActor in
                guard self?.usesCompatibility == false, self?.player.currentItem === observedItem else { return }
                let status = player.timeControlStatus
                self?.playing = status != .paused
                self?.buffering = status == .waitingToPlayAtSpecifiedRate
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
        if resume.isFinite && resume > 0 { player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
        player.defaultRate = rate
        applyNativeQuality()
        if shouldPlay { player.play() }
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
            position = pendingMovieSeek ?? value.position; duration = value.duration; playing = value.playing; buffering = value.buffering
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
        self.maximumHeight = maximumHeight; self.highest = highest
        if usesCompatibility {
            if changed {
                // Keep the paused decoder/frame intact. Reopening a paused VLC input need not render a frame.
                if wantsPlay { startCompatibility(resume: position, playing: true) }
                else { restartOnResume = true }
            }
        } else { applyNativeQuality(bandwidth: bandwidth) }
    }
    private func applyNativeQuality(bandwidth: Double = 0) {
        player.currentItem?.preferredMaximumResolution = maximumHeight.map { CGSize(width: Double($0) * 16 / 9, height: Double($0)) } ?? .zero
        player.currentItem?.preferredPeakBitRate = bandwidth
    }
    func pause() { wantsPlay = false; if usesCompatibility { compatibility.pause() } else { player.pause() }; playing = false }
    func play() {
        wantsPlay = true
        if restartOnResume { restart(resume: position) }
        else if usesCompatibility { compatibility.play() }
        else { player.play() }
        playing = true
    }
    private func updateCue() {
        let line = cues.isEmpty ? nil : SubtitleCatalog.cue(at: position, in: cues)?.text
        if line != cue { cue = line }
    }

    private func tick() {
        guard let item = player.currentItem else { return }
        let now = player.currentTime().seconds, length = item.duration.seconds
        if now.isFinite { position = now }
        if length.isFinite && length > 0 { duration = length }
        updateCue()
        ticks += 1
        if ticks % 20 == 0 { persist() }
    }

    func persist(completed: Bool = false) {
        guard !externalPlayback else { return }
        // The periodic clock/seek already maintains this snapshot. Do not synchronously query
        // a remote asset again during dismissal if its connection has stalled.
        guard position.isFinite, duration.isFinite, duration > 0 else { return }
        save?(position, duration, completed || ended)
    }

    func togglePlay() {
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
        let target = max(0, duration > 0 ? min(seconds, duration) : seconds)
        position = target
        if usesCompatibility {
            if enginePreference == .tvMovies && !wantsPlay {
                // VLC can retain the old clock while paused. Keep the selected movie time until resume,
                // then reopen at that time rather than allowing paused callbacks to erase the seek.
                pendingMovieSeek = target; restartOnResume = true
            }
            compatibility.seek(target); return
        }
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
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
        currentURL = nil; save = nil; playing = false; buffering = false; restartOnResume = false; pendingMode = nil; pendingMovieSeek = nil
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
