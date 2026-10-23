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

/// Settings section: the demo and the added providers, with adding and removing.
struct ExternalSourcesSection: View {
    @EnvironmentObject private var store: ProviderStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @State private var adding = false
    var body: some View {
        Section {
            ForEach([ExternalProvider.demo] + store.providers) { provider in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(provider.name)
                        Text(provider.id == "demo" ? "أفلام مرخّصة للتجربة" : (provider.baseURL.host ?? ""))
                            .font(.caption).foregroundStyle(Theme.muted)
                    }
                    Spacer()
                    if sourceName == ContentSource.external(provider.id).rawValue {
                        Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { sourceName = ContentSource.external(provider.id).rawValue }
                .swipeActions {
                    if provider.id != "demo" {
                        Button("حذف", role: .destructive) {
                            if sourceName == ContentSource.external(provider.id).rawValue { sourceName = SourcePreference.fallback.rawValue }
                            store.remove(provider)
                        }
                    }
                }
            }
            Button { adding = true } label: { Label("إضافة مصدر", systemImage: "plus") }
        } header: {
            Text("المصادر الخارجية")
        } footer: {
            Text("أضف واجهة REST تعطي المحتوى بصيغة Cinema iQ (روابط MP4 أو HLS عبر HTTPS). اضغط مصدراً لاختياره، واسحب لحذفه.")
        }
        .sheet(isPresented: $adding) { AddProviderSheet() }
    }
}

private struct AddProviderSheet: View {
    @EnvironmentObject private var store: ProviderStore
    @AppStorage(SourcePreference.key) private var sourceName = SourcePreference.fallback.rawValue
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var address = "https://"
    @State private var token = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("الاسم", text: $name)
                    TextField("عنوان الواجهة", text: $address)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                    SecureField("رمز الوصول (اختياري)", text: $token)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } footer: {
                    Text("يُفحص العنوان بطلب صفحته الرئيسية قبل الحفظ. رمز الوصول يُحفظ في سلسلة المفاتيح ويُرسل كـ Authorization: Bearer.")
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .navigationTitle("إضافة مصدر")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button("فحص وإضافة") {
                            busy = true; error = nil
                            Task {
                                do {
                                    let provider = try await store.add(name: name, address: address, token: token)
                                    sourceName = ContentSource.external(provider.id).rawValue
                                    dismiss()
                                } catch is CancellationError {
                                } catch { self.error = error.localizedDescription }
                                busy = false
                            }
                        }
                        .disabled(address.count < 12)
                    }
                }
            }
        }
    }
}
