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
    @Published private(set) var numbers: [String: Int] = [:]
    @Published var group: String?
    @Published var section: IPTVChannelSection = .all
    @Published private(set) var catalogRevision = UUID()
    @Published private(set) var engineMode = IPTVPlaybackMode.automatic
    @Published private(set) var usesCompatibility = false
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
    private var renewalTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var stableTask: Task<Void, Never>?
    private let automaticallyReconnect: Bool
    private var active = true
    private var pausedByUser = false
    private var resumeOnActive = false
    private var attempts = 0
    private var renewedLink = false
    private var recoveryID = UUID()
    private var qualityTask: Task<Void, Never>?
    private var generation = UUID()
    private var attached = false
    var status: String { retrying && automaticallyReconnect ? "جارٍ إعادة الاتصال…" : tuning || buffering || retrying ? "جارٍ التحميل…" : ended ? "انتهى البث" : playing ? "بث مباشر" : "متوقف مؤقتًا" }
    var queue: [IPTVChannel] {
        if automaticallyReconnect {
            if section == .all { return channels }
            return store?.channelCollection.channels(channels, sourceID: sourceID, section: section) ?? []
        }
        return IPTVNavigation.channels(channels, sourceID: sourceID, group: group)
    }
    var groups: [String] { Array(Set(channels.map(\.group))).sorted { $0.localizedStandardCompare($1) == .orderedAscending } }
    var videoHeight: Int {
        let height = usesCompatibility ? Double(quality?.height ?? maximumHeight ?? 0) : Double(controller.player.currentItem?.presentationSize.height ?? 0)
        return height.isFinite && height > 0 ? Int(min(height, 100_000)) : maximumHeight ?? 0
    }
    init(initial: IPTVChannel, group: String?, preferredMaximumHeight: Int? = nil, automaticallyReconnect: Bool = false) {
        channel = initial; sourceID = initial.sourceID; self.group = group
        self.automaticallyReconnect = automaticallyReconnect
        self.section = group.map(IPTVChannelSection.group) ?? .all
        maximumHeight = preferredMaximumHeight
        controller.$mode.removeDuplicates().sink { [weak self] in self?.engineMode = $0 }.store(in: &observers)
        controller.$usesCompatibility.removeDuplicates().sink { [weak self] in self?.usesCompatibility = $0 }.store(in: &observers)
        controller.$playing.removeDuplicates().sink { [weak self] value in
            guard let self else { return }; self.playing = value
            if value { self.store?.noteWatched(self.channel) }; self.transportChanged()
        }.store(in: &observers)
        controller.$buffering.removeDuplicates().sink { [weak self] in self?.buffering = $0; self?.transportChanged() }.store(in: &observers)
        controller.$ended.removeDuplicates().sink { [weak self] value in
            guard let self else { return }; ended = value
            if value && automaticallyReconnect { recover(.unknown) }
        }.store(in: &observers)
        controller.$error.removeDuplicates().sink { [weak self] value in
            guard let self else { return }
            guard let value else { problem = nil; return }
            if automaticallyReconnect, IPTVReconnectPolicy.allowed(active: active, pausedByUser: pausedByUser),
               IPTVReconnectPolicy.retry(controller.lastFailure ?? .unknown, attempts: attempts) != nil {
                problem = nil; recover(controller.lastFailure ?? .unknown)
            } else { cancelRecovery(); retrying = false; problem = value }
        }.store(in: &observers)
        controller.$audioOptions.sink { [weak self] in self?.audio = $0 }.store(in: &observers)
        controller.$embeddedSubtitles.sink { [weak self] in self?.subtitles = $0 }.store(in: &observers)
        controller.$audioID.removeDuplicates().sink { [weak self] in self?.audioID = $0 }.store(in: &observers)
        controller.$subtitleID.removeDuplicates().sink { [weak self] in self?.subtitleID = $0 }.store(in: &observers)
    }
    func attach(_ store: IPTVStore) {
        guard !attached else { return }; attached = true; active = true; pausedByUser = false; self.store = store
        catalogObserver = store.$catalog.sink { [weak self] catalog in
            guard let self else { return }
            self.channels = catalog.channels.filter { $0.sourceID == self.sourceID }
            self.numbers = IPTVNavigation.numbers(self.channels, sourceID: self.sourceID)
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
        tuneTask?.cancel(); cancelRecovery(); qualityTask?.cancel()
        let token = UUID(); generation = token
        pausedByUser = false; attempts = 0; renewedLink = false
        if channel.id != next.id { previousID = channel.id }
        channel = next; problem = nil; tuning = true; retrying = false; qualities = []; quality = nil; audio = []; subtitles = []; audioID = nil; subtitleID = nil
        tuneTask = Task { @MainActor in
            if debounce { do { try await Task.sleep(for: .milliseconds(180)) } catch { return } }
            guard !Task.isCancelled, generation == token, !automaticallyReconnect || IPTVReconnectPolicy.allowed(active: active, pausedByUser: pausedByUser) else { return }
            open(next, token: token)
        }
    }
    private func open(_ next: IPTVChannel, token: UUID) {
        if prefersHighestQuality { qualities = []; quality = nil; maximumHeight = nil }
        var url = next.url
#if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--cinema-ui-testing-player") || args.contains("--cinema-tv-ui-testing-iptv-player") {
            let compatible = args.contains("--cinema-ui-testing-compatibility")
            guard let fixture = Bundle.main.url(forResource: compatible ? "UICompatibilityFixture" : "UIPlayerFixture", withExtension: compatible ? "mkv" : "mp4") else {
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
        controller.setQuality(maximumHeight: maximumHeight, highest: prefersHighestQuality)
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
        controller.setQuality(maximumHeight: maximumHeight, bandwidth: value?.bandwidth ?? 0, highest: prefersHighestQuality)
    }
    func chooseAudio(_ id: String) { controller.selectAudio(id) }
    func chooseSubtitle(_ id: String?) { controller.selectSubtitle(id, external: []) }
    func toggle() {
        pausedByUser = !pausedByUser && (playing || retrying || tuning)
        if pausedByUser { cancelRecovery(); retrying = false; controller.pause(); return }
        if !pausedByUser && automaticallyReconnect && controller.error != nil { recover(controller.lastFailure ?? .unknown) }
        else { controller.togglePlay() }
    }
    func pause() { pausedByUser = true; cancelRecovery(); retrying = false; controller.pause() }
    func setActive(_ value: Bool) {
        guard active != value else { return }
        if !value {
            resumeOnActive = !pausedByUser && (playing || retrying || tuning)
            active = false; tuneTask?.cancel(); cancelRecovery(); qualityTask?.cancel(); retrying = false; controller.pause()
        } else {
            active = true
            if resumeOnActive && !pausedByUser {
                if controller.error != nil || ended { recover(controller.lastFailure ?? .unknown) }
                else if controller.hasItem { controller.play() }
                else { tune(channel) }
            }
            resumeOnActive = false
        }
    }
    func programmes() async throws -> IPTVNowNext { try await store?.programmes(for: channel) ?? IPTVNowNext() }
    private func cancelRecovery() {
        recoveryID = UUID(); retryTask?.cancel(); retryTask = nil
        renewalTask?.cancel(); renewalTask = nil; watchdogTask?.cancel(); watchdogTask = nil; stableTask?.cancel(); stableTask = nil
    }
    private func transportChanged() {
        guard automaticallyReconnect else { return }
        if playing && !buffering {
            retrying = false; retryTask?.cancel(); retryTask = nil
            watchdogTask?.cancel(); watchdogTask = nil
            if stableTask == nil {
                let token = generation
                stableTask = Task { @MainActor [weak self] in
                    do { try await Task.sleep(for: .seconds(30)) } catch { return }
                    guard let self, generation == token, playing, !buffering else { return }
                    attempts = 0; renewedLink = false; stableTask = nil
                }
            }
        } else {
            stableTask?.cancel(); stableTask = nil
            if buffering && watchdogTask == nil && IPTVReconnectPolicy.allowed(active: active, pausedByUser: pausedByUser) {
                let token = generation
                watchdogTask = Task { @MainActor [weak self] in
                    do { try await Task.sleep(for: .seconds(30)) } catch { return }
                    guard let self, generation == token, buffering else { return }
                    watchdogTask = nil; recover(.timeout)
                }
            }
        }
    }
    private func recover(_ failure: PlaybackFailure) {
        guard automaticallyReconnect, attached, retryTask == nil,
              IPTVReconnectPolicy.allowed(active: active, pausedByUser: pausedByUser),
              let plan = IPTVReconnectPolicy.retry(failure, attempts: attempts) else { return }
        let token = generation, requested = channel, request = UUID(); recoveryID = request
        retrying = true; problem = nil
        watchdogTask?.cancel(); watchdogTask = nil
        retryTask = Task { @MainActor [weak self] in
            do { try await Task.sleep(for: .seconds(plan.delay)) } catch { return }
            guard let self, generation == token, recoveryID == request,
                  IPTVReconnectPolicy.allowed(active: active, pausedByUser: pausedByUser) else { return }
            if plan.renewLink && !renewedLink, let store {
                renewedLink = true; renewalTask?.cancel()
                renewalTask = Task { @MainActor [weak self] in
                    do {
                        let fresh = try await store.refreshed(requested)
                        guard let self, !Task.isCancelled, generation == token, channel.id == requested.id,
                              !(playing && !buffering), IPTVReconnectPolicy.allowed(active: active, pausedByUser: pausedByUser) else { return }
                        channel = fresh; qualityTask?.cancel(); open(fresh, token: token)
                    } catch is CancellationError { return }
                    catch {
                        guard let self, generation == token, !(playing && !buffering) else { return }
                        if let reason = error as? IPTVError,
                           [.invalidCredentials, .inactiveSubscription, .http(401), .http(403), .insecureConnection].contains(reason) {
                            retryTask?.cancel(); retryTask = nil; retrying = false; problem = store.safeMessage(error)
                        }
                    }
                }
            }
            let fresh = channels.first(where: { $0.id == requested.id }) ?? requested
            attempts = min(attempts + 1, 1000); channel = fresh; retryTask = nil; qualityTask?.cancel()
            open(fresh, token: token)
        }
    }
    func retry() {
        guard !retrying, let store else { return }
        tuneTask?.cancel(); cancelRecovery(); qualityTask?.cancel(); pausedByUser = false; attempts = 0; renewedLink = false
        let token = UUID(); generation = token; let requested = channel
        retrying = true; problem = nil; controller.close()
        retryTask = Task { @MainActor in
            defer { if generation == token { retrying = false; retryTask = nil } }
            do {
                let fresh = try await store.refreshed(requested)
                guard !Task.isCancelled, generation == token else { return }
                channel = fresh; open(fresh, token: token)
            } catch { if !Task.isCancelled, generation == token { problem = store.safeMessage(error) } }
        }
    }
    func close() {
        attached = false; active = false; generation = UUID(); tuneTask?.cancel(); cancelRecovery(); qualityTask?.cancel()
        catalogObserver?.cancel(); controller.close()
    }
}
