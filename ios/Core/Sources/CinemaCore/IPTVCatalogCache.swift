import Foundation

public struct IPTVCatalogSnapshot: Codable, Sendable, Equatable {
    public let savedAt: Date
    public let catalog: IPTVCatalog
    public let retryAfter: Date?
    public init(savedAt: Date, catalog: IPTVCatalog, retryAfter: Date? = nil) {
        self.savedAt = savedAt; self.catalog = catalog; self.retryAfter = retryAfter
    }
    public func fresh(at now: Date) -> Bool {
        now >= savedAt && (now.timeIntervalSince(savedAt) < 900 || retryAfter.map { now < $0 } == true)
    }
}
public protocol IPTVCatalogCaching: Sendable {
    func read(_ source: IPTVSource) async throws -> IPTVCatalogSnapshot?
    func write(_ snapshot: IPTVCatalogSnapshot, source: IPTVSource) async throws
    func remove(sourceID: String) async
}

#if canImport(CryptoKit) && canImport(Security)
import CryptoKit
import Security

/// Optional, backup-excluded TV catalogue cache. Subscription URLs are encrypted with a device-only key.
public actor IPTVDiskCatalogCache: IPTVCatalogCaching {
    private let directory: URL
    private let service: String
    private var secret: SymmetricKey?
    private struct Warm { let source: String; let fingerprint: Data; let snapshot: IPTVCatalogSnapshot }
    private var warm: Warm?
    private let header = Data("CIQCAT2".utf8)
    private let maximum = 8 * 1024 * 1024
    public init(directory: URL, service: String) { self.directory = directory; self.service = service }
    init(directory: URL, key: SymmetricKey) { self.directory = directory; service = "test-catalog-cache"; secret = key }

    public func read(_ source: IPTVSource) async throws -> IPTVCatalogSnapshot? {
        let fingerprint = try fingerprint(source)
        if let warm, warm.source == source.id, warm.fingerprint == fingerprint { return warm.snapshot }
        let file = file(source.id)
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        do {
            let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > header.count + 28, size <= maximum + header.count + 28 else { throw IPTVError.tooLarge }
            let data = try Data(contentsOf: file)
            guard data.starts(with: header) else { throw IPTVError.invalidResponse }
            let sealed = try AES.GCM.SealedBox(combined: data.dropFirst(header.count))
            let compressed = try AES.GCM.open(sealed, using: key(), authenticating: header + fingerprint)
            let json = try (compressed as NSData).decompressed(using: .lzfse) as Data
            guard json.count <= 32 * 1024 * 1024 else { throw IPTVError.tooLarge }
            let snapshot = try JSONDecoder().decode(IPTVCatalogSnapshot.self, from: json)
            try validate(snapshot, source: source)
            keep(snapshot, source: source.id, fingerprint: fingerprint)
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
            return snapshot
        } catch {
            // Cache failure never deletes or overwrites the independent subscription vault.
            try? FileManager.default.removeItem(at: file)
            return nil
        }
    }

    public func write(_ snapshot: IPTVCatalogSnapshot, source: IPTVSource) async throws {
        try validate(snapshot, source: source)
        let fingerprint = try fingerprint(source)
        keep(snapshot, source: source.id, fingerprint: fingerprint)
        let data = try JSONEncoder().encode(snapshot)
        guard data.count <= 32 * 1024 * 1024 else { throw IPTVError.tooLarge }
        let compressed = try (data as NSData).compressed(using: .lzfse) as Data
        guard compressed.count <= maximum else { throw IPTVError.tooLarge }
        let sealed = try AES.GCM.seal(compressed, using: key(), authenticating: header + fingerprint)
        guard let combined = sealed.combined else { throw IPTVError.invalidResponse }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = file(source.id)
        try (header + combined).write(to: target, options: .atomic)
        var excluded = target, values = URLResourceValues(); values.isExcludedFromBackup = true
        try? excluded.setResourceValues(values)
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])
            .filter { $0.pathExtension == "enc" }
            .sorted {
                let left = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast
                let right = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast
                return left < right
            }
        for old in files.prefix(max(0, files.count - 3)) where old != target { try? FileManager.default.removeItem(at: old) }
    }

    public func remove(sourceID: String) async {
        if warm?.source == sourceID { warm = nil }
        try? FileManager.default.removeItem(at: file(sourceID))
    }
    private func keep(_ snapshot: IPTVCatalogSnapshot, source: String, fingerprint: Data) {
        warm = snapshot.catalog.channels.count <= 20_000 ? Warm(source: source, fingerprint: fingerprint, snapshot: snapshot) : nil
    }
    private func file(_ id: String) -> URL {
        directory.appendingPathComponent(SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined() + ".enc")
    }
    private func fingerprint(_ source: IPTVSource) throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        return Data(SHA256.hash(data: try encoder.encode(source)))
    }
    private func validate(_ snapshot: IPTVCatalogSnapshot, source: IPTVSource) throws {
        guard (1...IPTVParser.maximumChannels).contains(snapshot.catalog.channels.count) else { throw IPTVError.tooLarge }
        var seen = Set<String>()
        for value in snapshot.catalog.channels {
            guard value.sourceID == source.id, value.id.hasPrefix(source.id + ":"), seen.insert(value.id).inserted,
                  !value.name.isEmpty, value.name.count <= 500, value.group.count <= 500, value.tvgID.count <= 16_384 else { throw IPTVError.invalidResponse }
            try IPTVURLPolicy.validate(value.url, allowHTTP: source.allowHTTP)
            if let logo = value.logo { try IPTVURLPolicy.validate(logo, allowHTTP: source.allowHTTP) }
        }
        if let epg = snapshot.catalog.epgURL { try IPTVURLPolicy.validate(epg, allowHTTP: source.allowHTTP) }
    }
    private func key() throws -> SymmetricKey {
        if let secret { return secret }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "catalog.key.v1", kSecAttrSynchronizable as String: false]
        func load() throws -> Data? {
            var request = query; request[kSecReturnData as String] = true; request[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            let status = SecItemCopyMatching(request as CFDictionary, &result)
            if status == errSecItemNotFound { return nil }
            guard status == errSecSuccess, let data = result as? Data, data.count == 32 else { throw IPTVKeychainVault.VaultError.unavailable }
            return data
        }
        if let data = try load() { let key = SymmetricKey(data: data); secret = key; return key }
        let key = SymmetricKey(size: .bits256), data = key.withUnsafeBytes { Data($0) }
        var insert = query; insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(insert as CFDictionary, nil)
        if status == errSecDuplicateItem, let data = try load() { let key = SymmetricKey(data: data); secret = key; return key }
        guard status == errSecSuccess else { throw IPTVKeychainVault.VaultError.unavailable }
        secret = key; return key
    }
}
#endif
