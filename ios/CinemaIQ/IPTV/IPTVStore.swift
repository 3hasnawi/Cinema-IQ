import Foundation
import Security
import SwiftUI
import CinemaCore

/// One local Keychain item. It never participates in the app's library or Firebase synchronization.
struct IPTVKeychainVault: Sendable {
    let service: String
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "sources.v1", kSecAttrSynchronizable as String: false]
    }
    func read() throws -> [IPTVSource] {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &value)
        trace("read", status)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let data = value as? Data else { throw VaultError.unavailable }
        do { return try JSONDecoder().decode([IPTVSource].self, from: data) }
        catch { throw VaultError.unreadable }
    }
    func write(_ sources: [IPTVSource]) throws {
        let data = try JSONEncoder().encode(sources)
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        trace("update", status)
        if status == errSecItemNotFound {
            let inserted = query.merging(attributes) { _, new in new }
            let added = SecItemAdd(inserted as CFDictionary, nil)
            trace("add", added)
            guard added == errSecSuccess else { throw VaultError.unavailable }
        } else if status != errSecSuccess { throw VaultError.unavailable }
    }
    private func trace(_ operation: String, _ status: OSStatus) {
#if DEBUG
        if service.hasSuffix(".uitests") { print("CINEMA_IPTV_VAULT \(operation) \(status)") }
#endif
    }
    enum VaultError: LocalizedError {
        case unavailable, unreadable, sourceLimit
        var errorDescription: String? {
            switch self {
            case .unavailable: return "تعذّر الوصول إلى بيانات IPTV الآمنة. افتح قفل الجهاز وأعد المحاولة."
            case .unreadable: return "تعذّر قراءة بيانات IPTV المحفوظة. لم تُحذف البيانات؛ أعد المحاولة لاحقًا."
            case .sourceLimit: return "يمكن حفظ 20 مصدر IPTV كحد أقصى. احذف مصدرًا قبل إضافة آخر."
            }
        }
    }
}

@MainActor final class IPTVStore: ObservableObject {
    @Published private(set) var sources: [IPTVSource] = []
    @Published var selectedID: String? {
        didSet { defaults.set(selectedID, forKey: selectionKey) }
    }
    @Published private(set) var catalog = IPTVCatalog(channels: []) {
        didSet { catalogRevision = UUID() }
    }
    @Published private(set) var catalogRevision = UUID()
    @Published private(set) var sourceRevision = UUID()
    @Published private(set) var loading = false
    @Published private(set) var message: String?
    @Published private(set) var storageAvailable = false
    @Published private(set) var storageLoading = true
    private let vault: IPTVKeychainVault
    private let defaults: UserDefaults
    private let selectionKey: String
    private let client: IPTVClient
    private var cache: [String: (Date, IPTVCatalog)] = [:]
    private var requestID = UUID()
    private var inFlightSourceID: String?
    private var storageTask: Task<Void, Never>?
    var selected: IPTVSource? { sources.first { $0.id == selectedID } }
    var loadKey: String { (selectedID ?? "") + sourceRevision.uuidString }

    init() {
        defaults = .standard
#if DEBUG
        let testing = ProcessInfo.processInfo.arguments.contains("--cinema-ui-testing-iptv")
        vault = IPTVKeychainVault(service: testing ? "com.cinemaplus.rebuilt.ios.iptv.uitests" : "com.cinemaplus.rebuilt.ios.iptv")
        selectionKey = testing ? "ios.iptv.uitests.selected.v1" : "ios.iptv.selected.v1"
        client = testing ? IPTVClient(transport: IPTVFixtureTransport()) : IPTVClient()
#else
        vault = IPTVKeychainVault(service: "com.cinemaplus.rebuilt.ios.iptv")
        selectionKey = "ios.iptv.selected.v1"
        client = IPTVClient()
#endif
        selectedID = defaults.string(forKey: selectionKey)
        readSources()
    }
    func readSources() {
        guard storageTask == nil else { return }
        storageLoading = true
        let vault = self.vault
        // Security's IPC must never block the app's first frame or its launch watchdog.
        storageTask = Task { @MainActor [weak self] in
            let result = await Task.detached(priority: .userInitiated) {
                Result {
#if DEBUG
                    let arguments = ProcessInfo.processInfo.arguments
                    if arguments.contains("--cinema-ui-testing-iptv") && arguments.contains("--cinema-ui-testing-iptv-reset") {
                        try vault.write([])
                    }
                    if IPTVFixtureSupport.snapshot {
                        let source = try IPTVSource(id: "iptv-snapshot-playlist", name: "قنوات تجريبية", kind: .playlist,
                                                    address: URL(string: "https://iptv.example/channels.m3u")!)
                        try vault.write([source])
                    }
#endif
                    return try vault.read()
                }
            }.value
            guard let self else { return }
            defer { self.storageLoading = false; self.storageTask = nil }
            do {
                self.sources = try result.get(); self.storageAvailable = true; self.message = nil
                self.sourceRevision = UUID()
#if DEBUG
                if IPTVFixtureSupport.snapshot { self.selectedID = self.sources.first?.id }
#endif
                if !self.sources.contains(where: { $0.id == self.selectedID }) { self.selectedID = self.sources.first?.id }
            } catch {
                self.storageAvailable = false; self.message = self.safeMessage(error)
            }
        }
    }
    func reload(force: Bool = false) async {
        guard let source = selected else { catalog = IPTVCatalog(channels: []); loading = false; return }
        guard !(loading && inFlightSourceID == source.id) else { return }
        let request = UUID(); requestID = request
        cache = cache.filter { Date().timeIntervalSince($0.value.0) < 300 }
        if !force, let cached = cache[source.id], Date().timeIntervalSince(cached.0) < 300 {
            catalog = cached.1; message = nil; loading = false; return
        }
        catalog = IPTVCatalog(channels: []); loading = true; message = nil
        inFlightSourceID = source.id
        defer { if requestID == request { loading = false; inFlightSourceID = nil } }
        do {
            let result = try await client.load(source)
            try Task.checkCancellation()
            guard requestID == request, selectedID == source.id else { return }
            cacheResult(result, for: source.id); catalog = result
        } catch is CancellationError {
        } catch {
            if requestID == request && !Task.isCancelled { message = safeMessage(error) }
        }
    }
    func connectAndSave(_ source: IPTVSource) async throws {
        guard storageAvailable else { throw IPTVKeychainVault.VaultError.unavailable }
        let result = try await client.load(source)
        try Task.checkCancellation()
        var updated = sources.filter { $0.id != source.id }; updated.append(source)
        guard updated.count <= 20 else { throw IPTVKeychainVault.VaultError.sourceLimit }
        try vault.write(updated)
        requestID = UUID(); loading = false; inFlightSourceID = nil
        sources = updated; cacheResult(result, for: source.id); selectedID = source.id; sourceRevision = UUID()
        catalog = result; message = nil
    }
    func remove(_ source: IPTVSource) {
        do {
            guard storageAvailable else { throw IPTVKeychainVault.VaultError.unavailable }
            let updated = sources.filter { $0.id != source.id }
            try vault.write(updated)
            sources = updated; cache[source.id] = nil; sourceRevision = UUID()
            if selectedID == source.id { requestID = UUID(); selectedID = updated.first?.id; catalog = IPTVCatalog(channels: []) }
            message = nil
        } catch { message = safeMessage(error) }
    }
    /// Refresh expiring playlist links before retry, matching channel identity rather than a list index.
    func refreshed(_ channel: IPTVChannel) async throws -> IPTVChannel {
        guard let source = sources.first(where: { $0.id == channel.sourceID }) else { throw IPTVError.noChannels }
        let result = try await client.load(source)
        try Task.checkCancellation()
        cacheResult(result, for: source.id)
        if selectedID == source.id { catalog = result }
        guard let found = result.channels.first(where: { $0.id == channel.id }) else { throw IPTVError.noChannels }
        return found
    }
    func safeMessage(_ error: Error) -> String {
        if let error = error as? IPTVError { return error.localizedDescription }
        if let error = error as? IPTVKeychainVault.VaultError { return error.localizedDescription }
        return "تعذّر إكمال الاتصال بمصدر IPTV. تحقق من الشبكة وأعد المحاولة."
    }
    private func cacheResult(_ result: IPTVCatalog, for source: String) {
        cache[source] = (Date(), result)
        while cache.count > 3, let oldest = cache.min(by: { $0.value.0 < $1.value.0 })?.key { cache[oldest] = nil }
    }
}
