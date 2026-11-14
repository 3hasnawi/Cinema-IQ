import Foundation
import Combine
import AVFoundation
import CinemaCore

/// One AVPlayer for the entire live session. Channel changes cancel stale work and never write movie progress.
@MainActor final class IPTVPlayerSession: ObservableObject {
    let controller = PlaybackController()
    let sourceID: String
    @Published private(set) var channel: IPTVChannel
    @Published private(set) var channels: [IPTVChannel] = []
    @Published var group: String?
    @Published private(set) var catalogRevision = UUID()
    @Published private(set) var playing = false
    @Published private(set) var buffering = false
    @Published private(set) var ended = false
    @Published private(set) var tuning = false
    @Published private(set) var retrying = false
    @Published private(set) var problem: String?
    @Published private(set) var qualities: [IPTVStreamQuality] = []
    @Published private(set) var quality: IPTVStreamQuality?
    @Published private(set) var maximumHeight: Int?
    @Published private(set) var prefersHighestQuality = false
    @Published private(set) var audio: [MediaOption] = []
    @Published private(set) var subtitles: [MediaOption] = []
    @Published private(set) var audioID: String?
    @Published private(set) var subtitleID: String?
    @Published private(set) var previousID: String?
    private weak var store: IPTVStore?
    private var observers = Set<AnyCancellable>()
    private var catalogObserver: AnyCancellable?
    private var tuneTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var qualityTask: Task<Void, Never>?
    private var generation = UUID()
    private var attached = false
    var status: String { tuning || buffering || retrying ? "جارٍ التحميل…" : ended ? "انتهى البث" : playing ? "بث مباشر" : "متوقف مؤقتًا" }
    var queue: [IPTVChannel] { IPTVNavigation.channels(channels, sourceID: sourceID, group: group) }
    var groups: [String] { Array(Set(channels.map(\.group))).sorted { $0.localizedStandardCompare($1) == .orderedAscending } }
    init(initial: IPTVChannel, group: String?, preferredMaximumHeight: Int? = nil) {
        channel = initial; sourceID = initial.sourceID; self.group = group
        maximumHeight = preferredMaximumHeight
        controller.$playing.removeDuplicates().sink { [weak self] in self?.playing = $0 }.store(in: &observers)
        controller.$buffering.removeDuplicates().sink { [weak self] in self?.buffering = $0 }.store(in: &observers)
        controller.$ended.removeDuplicates().sink { [weak self] in self?.ended = $0 }.store(in: &observers)
        controller.$error.removeDuplicates().sink { [weak self] in self?.problem = $0 }.store(in: &observers)
        controller.$audioOptions.sink { [weak self] in self?.audio = $0 }.store(in: &observers)
        controller.$embeddedSubtitles.sink { [weak self] in self?.subtitles = $0 }.store(in: &observers)
        controller.$audioID.removeDuplicates().sink { [weak self] in self?.audioID = $0 }.store(in: &observers)
        controller.$subtitleID.removeDuplicates().sink { [weak self] in self?.subtitleID = $0 }.store(in: &observers)
    }
    func attach(_ store: IPTVStore) {
        guard !attached else { return }; attached = true; self.store = store
        catalogObserver = store.$catalog.sink { [weak self] catalog in
            guard let self else { return }
            self.channels = catalog.channels.filter { $0.sourceID == self.sourceID }
            self.catalogRevision = UUID()
        }
        tune(channel)
    }
    func surf(_ step: Int) {
        guard let next = IPTVNavigation.neighbor(of: channel.id, step: step, in: queue), next.id != channel.id else { return }
        tune(next, debounce: true)
    }
    func previous() { if let value = channels.first(where: { $0.id == previousID }) { tune(value) } }
    func tune(_ next: IPTVChannel, debounce: Bool = false) {
        guard next.sourceID == sourceID else { return }
        tuneTask?.cancel(); retryTask?.cancel(); qualityTask?.cancel()
        let token = UUID(); generation = token
        if channel.id != next.id { previousID = channel.id }
        channel = next; problem = nil; tuning = true; retrying = false; qualities = []; quality = nil; audio = []; subtitles = []; audioID = nil; subtitleID = nil
        tuneTask = Task { @MainActor in
            if debounce { do { try await Task.sleep(for: .milliseconds(180)) } catch { return } }
            guard !Task.isCancelled, generation == token else { return }
            open(next, token: token)
        }
    }
    private func open(_ next: IPTVChannel, token: UUID) {
        if prefersHighestQuality { qualities = []; quality = nil; maximumHeight = nil }
        var url = next.url
#if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--cinema-ui-testing-player") || args.contains("--cinema-tv-ui-testing-iptv-player") {
            guard let fixture = Bundle.main.url(forResource: "UIPlayerFixture", withExtension: "mp4") else {
                problem = "تعذّر العثور على فيديو الاختبار المحلي."; tuning = false; return
            }
            url = fixture
        }
#endif
        var media = Media(id: next.id, title: next.name); media.overview = next.group
        let language = UserDefaults.standard.string(forKey: "ios.subtitle.language.v1") ?? "ar"
        controller.resetSubtitles(preferred: language == "off" ? nil : language)
        controller.open(media, url: url, resume: 0) { _, _, _ in }
        // Retain the existing TV default and the user's session choice across channel changes.
        controller.player.currentItem?.preferredMaximumResolution = maximumHeight.map {
            CGSize(width: Double($0) * 16 / 9, height: Double($0))
        } ?? .zero
        controller.player.currentItem?.preferredPeakBitRate = 0
        tuning = false
        guard url.scheme == "https" || url.scheme == "http", (url.pathExtension.lowercased() == "m3u8" || (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).contains { ["ext", "format", "type", "mime"].contains($0.name.lowercased()) && ($0.value?.lowercased().contains("m3u8") == true || $0.value?.lowercased().contains("mpegurl") == true) }) else { return }
        let allowHTTP = store?.sources.first(where: { $0.id == sourceID })?.allowHTTP ?? false
        qualityTask = Task { @MainActor in
            do {
                try await Task.sleep(for: .milliseconds(250))
                let data = try await IPTVURLSessionTransport().data(from: url)
                let available = IPTVStreamQualities.parse(data, url: url, allowHTTP: allowHTTP)
                guard !Task.isCancelled, generation == token else { return }
                qualities = available
                if prefersHighestQuality { applyHighestQuality() }
            } catch { /* Optional quality discovery must never interrupt authorized playback. */ }
        }
    }
    func chooseQuality(_ value: IPTVStreamQuality?) {
        prefersHighestQuality = false
        applyQuality(value)
    }
    func preferHighestQuality() {
        prefersHighestQuality = true
        applyHighestQuality()
    }
    private func applyHighestQuality() {
        // HLS choices are sorted by resolution/bit-rate. Never replace the master and lose alternate tracks.
        applyQuality(qualities.first)
    }
    private func applyQuality(_ value: IPTVStreamQuality?) {
        quality = value
        maximumHeight = value.flatMap { $0.height > 0 ? $0.height : nil }
        // AVPlayer's public rendition controls are caps: keep the master so alternate audio remains available.
        controller.player.currentItem?.preferredMaximumResolution = value.map { CGSize(width: Double($0.width), height: Double($0.height)) } ?? .zero
        controller.player.currentItem?.preferredPeakBitRate = value?.bandwidth ?? 0
    }
    func chooseAudio(_ id: String) { controller.selectAudio(id) }
    func chooseSubtitle(_ id: String?) { controller.selectSubtitle(id, external: []) }
    func toggle() { controller.togglePlay() }
    func pause() { controller.player.pause() }
    func retry() {
        guard !retrying, let store else { return }
        tuneTask?.cancel(); retryTask?.cancel(); qualityTask?.cancel()
        let token = UUID(); generation = token; let requested = channel
        retrying = true; problem = nil; controller.close()
        retryTask = Task { @MainActor in
            defer { if generation == token { retrying = false } }
            do {
                let fresh = try await store.refreshed(requested)
                guard !Task.isCancelled, generation == token else { return }
                channel = fresh; open(fresh, token: token)
            } catch { if !Task.isCancelled, generation == token { problem = store.safeMessage(error) } }
        }
    }
    func close() {
        generation = UUID(); tuneTask?.cancel(); retryTask?.cancel(); qualityTask?.cancel()
        catalogObserver?.cancel(); controller.close()
    }
}
