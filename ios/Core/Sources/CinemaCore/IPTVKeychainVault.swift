#if canImport(Security)
import Foundation
import Security

/// One local Keychain item. It never participates in the app's library or Firebase synchronization.
public struct IPTVKeychainVault: Sendable {
    public let service: String
    public init(service: String) { self.service = service }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "sources.v1", kSecAttrSynchronizable as String: false]
    }
    public func read() throws -> [IPTVSource] {
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
    public func write(_ sources: [IPTVSource]) throws {
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
    public enum VaultError: LocalizedError {
        case unavailable, unreadable, sourceLimit
        public var errorDescription: String? {
            switch self {
            case .unavailable: return "تعذّر الوصول إلى بيانات IPTV الآمنة. افتح قفل الجهاز وأعد المحاولة."
            case .unreadable: return "تعذّر قراءة بيانات IPTV المحفوظة. لم تُحذف البيانات؛ أعد المحاولة لاحقًا."
            case .sourceLimit: return "يمكن حفظ 20 مصدر IPTV كحد أقصى. احذف مصدرًا قبل إضافة آخر."
            }
        }
    }
}

#endif
