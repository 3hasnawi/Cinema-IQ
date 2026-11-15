#if canImport(Combine) && canImport(Security)
import Foundation
import Combine

@MainActor public final class IPTVStore: ObservableObject {
    @Published public private(set) var channelCollection = IPTVChannelCollection()
    @Published public private(set) var sources: [IPTVSource] = []
    @Published public var selectedID: String? {
        didSet { defaults.set(selectedID, forKey: selectionKey) }
    }
    @Published public private(set) var catalog = IPTVCatalog(channels: []) {
        didSet { catalogRevision = UUID() }
    }
    @Published public private(set) var catalogRevision = UUID()
    @Published public private(set) var sourceRevision = UUID()
    @Published public private(set) var loading = false
    @Published public private(set) var message: String?
    @Published public private(set) var storageAvailable = false
    @Published public private(set) var storageLoading = true
    private let vault: IPTVKeychainVault
    private let defaults: UserDefaults
    private let selectionKey: String
    private let client: IPTVClient
    private var cache: [String: (Date, IPTVCatalog)] = [:]
    private var requestID = UUID()
    private var inFlightSourceID: String?
    private var loadTask: Task<IPTVCatalog, Error>?
    private var storageTask: Task<Void, Never>?
    public var selected: IPTVSource? { sources.first { $0.id == selectedID } }
    public var loadKey: String { storageLoading ? "storage-loading" : (selectedID ?? "") + sourceRevision.uuidString }

    private let prepareStorage: (@Sendable (IPTVKeychainVault) throws -> Void)?
    public init(service: String, selectionKey: String, client: IPTVClient = IPTVClient(),
                defaults: UserDefaults = .standard,
                resetCollection: Bool = false, prepareStorage: (@Sendable (IPTVKeychainVault) throws -> Void)? = nil) {
        self.defaults = defaults; self.selectionKey = selectionKey; self.client = client
        self.vault = IPTVKeychainVault(service: service); self.prepareStorage = prepareStorage
        if resetCollection { defaults.removeObject(forKey: selectionKey + ".channels.v1") }
        if let data = defaults.data(forKey: selectionKey + ".channels.v1"), data.count < 2_000_000,
           let saved = try? JSONDecoder().decode(IPTVChannelCollection.self, from: data) { channelCollection = saved }
        selectedID = defaults.string(forKey: selectionKey)
        readSources()
    }
    public func noteWatched(_ channel: IPTVChannel) {
        guard channelCollection.recent.first != channel.id else { return }
        channelCollection.watched(channel.id); saveCollection()
    }
    public func toggleFavorite(_ channel: IPTVChannel) { channelCollection.toggle(channel.id); saveCollection() }
    private func saveCollection() {
        if let data = try? JSONEncoder().encode(channelCollection) { defaults.set(data, forKey: selectionKey + ".channels.v1") }
    }
    public func readSources() {
        guard storageTask == nil else { return }
        storageLoading = true
        let vault = self.vault, prepare = self.prepareStorage
        // Security's IPC must never block the app's first frame or its launch watchdog.
        storageTask = Task { @MainActor [weak self] in
            let result = await Task.detached(priority: .userInitiated) {
                Result {
                    try prepare?(vault)
                    return try vault.read()
                }
            }.value
            guard let self else { return }
            defer { self.storageLoading = false; self.storageTask = nil }
            do {
                self.sources = try result.get(); self.storageAvailable = true; self.message = nil
                self.sourceRevision = UUID()
                if !self.sources.contains(where: { $0.id == self.selectedID }) { self.selectedID = self.sources.first?.id }
            } catch {
                self.storageAvailable = false; self.message = self.safeMessage(error)
            }
        }
    }
    public func reload(force: Bool = false) async {
        guard !storageLoading else { return }
        guard let source = selected else {
            loadTask?.cancel(); loadTask = nil; inFlightSourceID = nil; requestID = UUID()
            catalog = IPTVCatalog(channels: []); loading = false; return
        }
        cache = cache.filter { Date().timeIntervalSince($0.value.0) < 300 }
        if loadTask == nil, !force, let cached = cache[source.id], Date().timeIntervalSince(cached.0) < 300 {
            catalog = cached.1; message = nil; loading = false; return
        }
        let request: UUID
        let task: Task<IPTVCatalog, Error>
        if let existing = loadTask, inFlightSourceID == source.id {
            // Join the result even if the previous SwiftUI task was cancelled during source hydration.
            request = requestID; task = existing
        } else {
            loadTask?.cancel()
            request = UUID(); requestID = request
            let client = self.client
            task = Task { try await client.load(source) }; loadTask = task
            catalog = IPTVCatalog(channels: []); loading = true; message = nil
            inFlightSourceID = source.id
        }
        defer { if requestID == request { loading = false; inFlightSourceID = nil; loadTask = nil } }
        do {
            let result = try await task.value
            try Task.checkCancellation()
            guard requestID == request, selectedID == source.id else { return }
            cacheResult(result, for: source.id); catalog = result
        } catch is CancellationError {
        } catch {
            if requestID == request && !Task.isCancelled { message = safeMessage(error) }
        }
    }
    public func connectAndSave(_ source: IPTVSource) async throws {
        guard storageAvailable else { throw IPTVKeychainVault.VaultError.unavailable }
        let result = try await client.load(source)
        try Task.checkCancellation()
        var updated = sources.filter { $0.id != source.id }; updated.append(source)
        guard updated.count <= 20 else { throw IPTVKeychainVault.VaultError.sourceLimit }
        try vault.write(updated)
        requestID = UUID(); loadTask?.cancel(); loadTask = nil; loading = false; inFlightSourceID = nil
        sources = updated; cacheResult(result, for: source.id); selectedID = source.id; sourceRevision = UUID()
        catalog = result; message = nil
    }
    public func remove(_ source: IPTVSource) {
        do {
            guard storageAvailable else { throw IPTVKeychainVault.VaultError.unavailable }
            let updated = sources.filter { $0.id != source.id }
            try vault.write(updated)
            sources = updated; cache[source.id] = nil; sourceRevision = UUID()
            if selectedID == source.id {
                requestID = UUID(); loadTask?.cancel(); loadTask = nil; inFlightSourceID = nil; loading = false
                selectedID = updated.first?.id; catalog = IPTVCatalog(channels: [])
            }
            message = nil
        } catch { message = safeMessage(error) }
    }
    /// Refresh expiring playlist links before retry, matching channel identity rather than a list index.
    public func refreshed(_ channel: IPTVChannel) async throws -> IPTVChannel {
        guard let source = sources.first(where: { $0.id == channel.sourceID }) else { throw IPTVError.noChannels }
        let result = try await client.load(source)
        try Task.checkCancellation()
        cacheResult(result, for: source.id)
        if selectedID == source.id { catalog = result }
        guard let found = result.channels.first(where: { $0.id == channel.id }) else { throw IPTVError.noChannels }
        return found
    }
    public func safeMessage(_ error: Error) -> String {
        if let error = error as? IPTVError { return error.localizedDescription }
        if let error = error as? IPTVKeychainVault.VaultError { return error.localizedDescription }
        return "تعذّر إكمال الاتصال بمصدر IPTV. تحقق من الشبكة وأعد المحاولة."
    }
    private func cacheResult(_ result: IPTVCatalog, for source: String) {
        cache[source] = (Date(), result)
        while cache.count > 3, let oldest = cache.min(by: { $0.value.0 < $1.value.0 })?.key { cache[oldest] = nil }
    }
}
#endif
