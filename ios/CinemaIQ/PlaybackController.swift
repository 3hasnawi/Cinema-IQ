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
    private var subtitleTask: Task<Void, Never>?
    private var pendingAudio: PlaybackTrackIdentity?
    private var pendingEmbeddedSubtitle: PlaybackTrackIdentity?
    private var completion = PlaybackCompletion()
    /// The language chosen automatically for a new title (from Settings); cleared once the viewer turns
    /// subtitles off, so they are not switched back on by themselves.
    private(set) var preferredLanguage: String? = "ar"
    private var timer: Any?
    private var endToken: NSObjectProtocol?
    private var failureToken: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var controlObservation: NSKeyValueObservation?
    private var save: ((Double, Double, Bool) -> Void)?
    private var ticks = 0
    private static let arabicWindows = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
        CFStringEncoding(CFStringEncodings.windowsArabic.rawValue)))

    init() { player.appliesMediaSelectionCriteriaAutomatically = false }

    func open(_ media: Media, url: URL, resume: Double, preservingState: Bool = false,
              save: @escaping (Double, Double, Bool) -> Void) {
        // Read the item's selection, not audioID: tvOS also changes audio through its native menu.
        let shouldPlay = !preservingState || player.timeControlStatus != .paused
        let audio = preservingState ? selectedTrack(in: audibleGroup) ?? pendingAudio : nil
        let subtitle = preservingState ? selectedTrack(in: legibleGroup) ?? pendingEmbeddedSubtitle : nil
        let wasCompleted = preservingState && completion.completed
        close(preservingSubtitles: true)
        self.save = save; error = nil; ended = false; position = resume.isFinite ? max(0, resume) : 0; duration = 0; ticks = 0
        completion = PlaybackCompletion(completed: wasCompleted)
        pendingAudio = audio; pendingEmbeddedSubtitle = subtitle
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch { self.error = error.localizedDescription; return }
        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            if item.status == .failed {
                Task { @MainActor in
                    guard let self, self.player.currentItem === item else { return }
                    self.error = "تعذّر تشغيل هذه النسخة. قد تكون صيغة الفيديو أو الصوت غير مدعومة على iOS. جرّب جودة أخرى."
                }
            }
        }
        controlObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                self.playing = status != .paused
                self.buffering = status == .waitingToPlayAtSpecifiedRate
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
                guard let self, self.player.currentItem === item else { return }
                self.error = "انقطع تشغيل الفيديو. تحقق من الاتصال وأعد المحاولة."
            }
        }
        timer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                self.tick()
            }
        }
        Task { await loadSelectionGroups(item) }
        if resume.isFinite && resume > 0 { player.seek(to: CMTime(seconds: resume, preferredTimescale: 600)) }
        player.defaultRate = rate
        if shouldPlay { player.play() }
    }

    private func tick() {
        guard let item = player.currentItem else { return }
        let now = player.currentTime().seconds, length = item.duration.seconds
        if now.isFinite { position = now }
        if length.isFinite && length > 0 { duration = length }
        let line = cues.isEmpty ? nil : SubtitleCatalog.cue(at: position, in: cues)?.text
        if line != cue { cue = line }
        ticks += 1
        if ticks % 20 == 0 { persist() }
    }

    func persist(completed: Bool = false) {
        guard let item = player.currentItem else { return }
        let isCompleted = completion.record(completed: completed || ended)
        let now = player.currentTime().seconds, length = item.duration.seconds
        guard now.isFinite, length.isFinite, length > 0 else { return }
        save?(now, length, isCompleted)
    }

    func togglePlay() {
        if player.timeControlStatus == .paused {
            if ended { ended = false; completion = PlaybackCompletion(); seek(to: 0) }
            player.play()
        } else {
            player.pause()
        }
    }
    func seek(to seconds: Double) {
        let target = max(0, duration > 0 ? min(seconds, duration) : seconds)
        position = target
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    func skip(_ delta: Double) { seek(to: position + delta) }
    func setRate(_ value: Float) {
        rate = value
        player.defaultRate = value
        if player.timeControlStatus != .paused { player.rate = value }
    }

    /// A new title starts with no subtitle chosen; the preferred language is picked automatically when offered.
    func resetSubtitles(preferred: String?) {
        subtitleTask?.cancel(); subtitleTask = nil
        subtitleID = nil; cues = []; cue = nil; notice = nil; preferredLanguage = preferred
        pendingEmbeddedSubtitle = nil
    }

    func selectSubtitle(_ id: String?, external: [SubtitleTrack]) {
        subtitleTask?.cancel(); subtitleTask = nil; pendingEmbeddedSubtitle = nil
        subtitleID = id; cues = []; cue = nil; notice = nil
        if id == nil { preferredLanguage = nil }
        applyEmbeddedSelection()
        guard let id, id.hasPrefix("ext:"), let track = external.first(where: { "ext:" + $0.id == id }) else { return }
        subtitleTask = Task {
            do {
                let data = try await SubtitleCatalog.download(track)
                let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: Self.arabicWindows) ?? ""
                let parsed = SubtitleCatalog.cues(text)
                guard !Task.isCancelled, subtitleID == id else { return }
                if parsed.isEmpty { notice = "ملف الترجمة غير صالح للعرض." }
                cues = parsed
            } catch {
                if !Task.isCancelled, subtitleID == id { notice = "تعذّر تحميل الترجمة. جرّبها مجدداً من قائمة الترجمة." }
            }
        }
    }

    func selectAudio(_ id: String) {
        guard let group = audibleGroup, let item = player.currentItem,
              let index = Int(id.dropFirst(4)), group.options.indices.contains(index) else { return }
        audioID = id; pendingAudio = nil
        item.select(group.options[index], in: group)
    }

    private func selectedTrack(in group: AVMediaSelectionGroup?) -> PlaybackTrackIdentity? {
        guard let group, let selected = player.currentItem?.currentMediaSelection.selectedMediaOption(in: group) else { return nil }
        return identity(of: selected)
    }

    private func identity(of option: AVMediaSelectionOption) -> PlaybackTrackIdentity {
        let characteristics: [AVMediaCharacteristic] = [.isMainProgramContent, .isAuxiliaryContent,
                                                        .describesVideoForAccessibility, .containsOnlyForcedSubtitles]
        return PlaybackTrackIdentity(language: option.extendedLanguageTag ?? option.locale?.identifier,
                                     title: option.displayName,
                                     characteristics: characteristics.filter { option.hasMediaCharacteristic($0) }.map(\.rawValue))
    }

    private func applyEmbeddedSelection() {
        guard let group = legibleGroup, let item = player.currentItem else { return }
        if let id = subtitleID, id.hasPrefix("emb:"), let index = Int(id.dropFirst(4)), group.options.indices.contains(index) {
            item.select(group.options[index], in: group)
        } else {
            item.select(nil, in: group)
        }
    }

    private func loadSelectionGroups(_ item: AVPlayerItem) async {
        let legible = (try? await item.asset.loadMediaSelectionGroup(for: .legible)) ?? nil
        let audible = (try? await item.asset.loadMediaSelectionGroup(for: .audible)) ?? nil
        guard player.currentItem === item else { return }
        legibleGroup = legible; audibleGroup = audible
        embeddedSubtitles = (legible?.options ?? []).enumerated().map { MediaOption(id: "emb:\($0.offset)", title: $0.element.displayName) }
        audioOptions = (audible?.options ?? []).enumerated().map { MediaOption(id: "aud:\($0.offset)", title: $0.element.displayName) }
        if let pendingAudio, let audible,
           let index = PlaybackPolicy.matchingTrack(pendingAudio, in: audible.options.map({ identity(of: $0) })) {
            item.select(audible.options[index], in: audible)
        }
        self.pendingAudio = nil
        if let pendingEmbeddedSubtitle {
            if let legible,
               let index = PlaybackPolicy.matchingTrack(pendingEmbeddedSubtitle, in: legible.options.map({ identity(of: $0) })) {
                subtitleID = "emb:\(index)"
            } else {
                subtitleID = nil
                notice = "الترجمة المختارة غير متاحة بهذه الجودة. اختر ترجمة من القائمة."
            }
        }
        pendingEmbeddedSubtitle = nil
        if let audible, let selected = item.currentMediaSelection.selectedMediaOption(in: audible),
           let index = audible.options.firstIndex(of: selected) { audioID = "aud:\(index)" }
        if subtitleID == nil, let preferred = preferredLanguage, let legible,
           let index = legible.options.firstIndex(where: { $0.locale?.language.languageCode?.identifier == preferred }) {
            subtitleID = "emb:\(index)"
        }
        applyEmbeddedSelection()
    }

    func close(preservingSubtitles: Bool = false) {
        persist(); player.pause()
        if !preservingSubtitles { subtitleTask?.cancel(); subtitleTask = nil; notice = nil; cue = nil }
        if let timer { player.removeTimeObserver(timer) }; timer = nil
        if let endToken { NotificationCenter.default.removeObserver(endToken) }; endToken = nil
        if let failureToken { NotificationCenter.default.removeObserver(failureToken) }; failureToken = nil
        statusObservation = nil; controlObservation = nil
        legibleGroup = nil; audibleGroup = nil; embeddedSubtitles = []; audioOptions = []; audioID = nil
        player.replaceCurrentItem(with: nil); save = nil
        pendingAudio = nil; pendingEmbeddedSubtitle = nil
        playing = false; buffering = false; error = nil; ended = false; position = 0; duration = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
