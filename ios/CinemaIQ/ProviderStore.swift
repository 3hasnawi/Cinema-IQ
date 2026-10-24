import SwiftUI
import Security
import CinemaCore

/// External video providers added in Settings. The list is kept in the app settings and the access tokens in the
/// Keychain; Core is told about every change, so pickers, pages and the player see the same providers.
@MainActor final class ProviderStore: ObservableObject {
    @Published private(set) var providers: [ExternalProvider] = []
    private let key = "ios.external.providers.v1"

    init() {
        let saved = (UserDefaults.standard.data(forKey: key)).flatMap { try? JSONDecoder().decode([ExternalProvider].self, from: $0) } ?? []
        providers = saved.map { provider in
            var provider = provider
            provider.token = ProviderKeychain.token(for: provider.id)
            return provider
        }
        publish()
    }

    /// Everything Core can open: the built-in demo first, then the viewer's providers.
    private func publish() { ExternalProviders.set([ExternalProvider.demo] + providers) }

    /// Checks the address answers in the Cinema iQ format before keeping it.
    func add(name: String, address: String, token: String) async throws -> ExternalProvider {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed), ExternalProvider.valid(base: url),
              url.scheme == "https" else { throw ProviderError.address }
        let id = ExternalProvider.slug(from: name.isEmpty ? (url.host ?? "src") : name, taken: Set(providers.map(\.id)))
        let provider = try ExternalProvider(id: id, name: name.isEmpty ? (url.host ?? "مصدر خارجي") : name, baseURL: url,
                                            token: token.trimmingCharacters(in: .whitespacesAndNewlines))
        let shelves = try await ExternalClient(provider: provider).home()
        guard !shelves.isEmpty else { throw ProviderError.empty }
        providers.append(provider)
        ProviderKeychain.save(provider.token, for: provider.id)
        save(); publish()
        return provider
    }

    func remove(_ provider: ExternalProvider) {
        providers.removeAll { $0.id == provider.id }
        ProviderKeychain.save(nil, for: provider.id)
        save(); publish()
    }

    private func save() { UserDefaults.standard.set(try? JSONEncoder().encode(providers), forKey: key) }

    enum ProviderError: LocalizedError {
        case address, empty
        var errorDescription: String? {
            switch self {
            case .address: return "اكتب عنوان HTTPS كاملاً للواجهة، مثل https://media.example.com/cinema-iq/v1"
            case .empty: return "ردّ المصدر بلا محتوى في صفحته الرئيسية (home)."
            }
        }
    }
}

/// Access tokens of external providers, one Keychain item per provider.
enum ProviderKeychain {
    private static let service = "com.cinemaplus.rebuilt.ios.providers"
    private static func query(_ id: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: id]
    }
    static func token(for id: String) -> String? {
        var request = query(id)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func save(_ token: String?, for id: String) {
        SecItemDelete(query(id) as CFDictionary)
        guard let token, !token.isEmpty, let data = token.data(using: .utf8) else { return }
        var item = query(id)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(item as CFDictionary, nil)
    }
}
