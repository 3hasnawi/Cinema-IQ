import Foundation
import Security
import SwiftUI
import CinemaCore

/// The Cinema iQ account on this iPhone: sign-in, email confirmation, the chosen profile and two-way sync of its
/// library with the Android apps. Changes wait in a saved outbox until sent; the account's records are fetched
/// when the app opens or returns, every few minutes while it is in use, and on demand.
@MainActor final class CloudSync: ObservableObject {
    @Published private(set) var session: CloudSession?
    @Published private(set) var profiles: [CloudProfile] = []
    @Published private(set) var profileID: String?
    @Published private(set) var status = ""
    @Published private(set) var busy = false
    @Published var message: String?

    private let client: CloudClient?
    private weak var library: LibraryStore?
    private var outbox: [String: String] = [:]
    private var known: [String: String] = [:]
    private var flushTask: Task<Void, Never>?
    private var pollTask: Task<Void, Never>?
    private var signingOut = false
    private let defaults = UserDefaults.standard

    var configured: Bool { client != nil }
    var activeProfile: CloudProfile? { profiles.first { $0.id == profileID } }
    private var namespace: String? {
        guard let session, session.verified, let profileID else { return nil }
        return "\(session.uid).\(profileID)"
    }

    init() {
        client = Self.loadConfig().map { CloudClient(config: $0) }
        session = Keychain.load()
    }

    /// Reads the Firebase iOS app settings shipped with the app.
    private static func loadConfig() -> CloudConfig? {
        guard let url = Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist"),
              let plist = NSDictionary(contentsOf: url),
              let key = plist["API_KEY"] as? String, let project = plist["PROJECT_ID"] as? String else { return nil }
        // The bundle id registered with Firebase: the iPhone app's, also from the Apple TV app and from a sideloaded
        // copy whose bundle id was changed by the signing tool.
        return CloudConfig(apiKey: key, projectID: project, bundleID: (plist["BUNDLE_ID"] as? String) ?? Bundle.main.bundleIdentifier ?? "")
    }

    func start(library: LibraryStore) {
        self.library = library
        library.recordSink = { [weak self] changes in self?.enqueue(changes) }
        if let session { profileID = defaults.string(forKey: profileKey(session.uid)) }
        activate()
        Task { await refreshAll() }
    }

    // MARK: Account

    func signIn(email: String, password: String) async {
        await run {
            guard let client = self.client else { return }
            let session = try await client.signIn(email: email.trimmingCharacters(in: .whitespaces), password: password)
            self.store(session)
            if session.verified { try await self.loadProfiles() }
        }
    }
    func signUp(email: String, password: String) async {
        await run {
            guard let client = self.client else { return }
            let session = try await client.signUp(email: email.trimmingCharacters(in: .whitespaces), password: password)
            self.store(session)
            try await client.sendVerification(session)
            self.message = "أرسلنا رابط التأكيد إلى \(session.email). افتحه ثم اضغط «تحققت من البريد»."
        }
    }
    func resendVerification() async {
        await run {
            guard let client = self.client, let session = try await self.validSession() else { return }
            try await client.sendVerification(session)
            self.message = "أُعيد إرسال رابط التأكيد."
        }
    }
    func checkVerification() async {
        await run {
            guard let client = self.client, var session = try await self.validSession() else { return }
            guard try await client.isVerified(session) else {
                self.message = "لم يُؤكَّد البريد بعد. افتح الرابط في رسالة التأكيد ثم أعد المحاولة."
                return
            }
            // A new token carries the verified claim the database rules check.
            session = try await client.refresh(session)
            session.verified = true
            self.store(session)
            try await self.loadProfiles()
        }
    }
    func resetPassword(email: String) async {
        await run {
            try await self.client?.sendPasswordReset(email: email.trimmingCharacters(in: .whitespaces))
            self.message = "أرسلنا رابط تغيير كلمة المرور إلى بريدك."
        }
    }
    func signOut() async {
        // The last flush can itself find the session expired; that path ends the session without another flush.
        guard !signingOut else { return }
        signingOut = true
        defer { signingOut = false }
        await flush()
        endSession()
    }
    /// Forgets the account on this iPhone. Unsent changes of the profile stay saved under its own outbox key.
    private func endSession() {
        flushTask?.cancel(); flushTask = nil
        session = nil; profiles = []; profileID = nil; outbox = [:]; known = [:]; status = ""
        Keychain.save(nil)
        library?.switchTo(account: nil)
    }

    // MARK: Profiles

    func choose(profile id: String) async {
        guard let session else { return }
        await flush()
        profileID = id
        defaults.set(id, forKey: profileKey(session.uid))
        library?.switchTo(account: namespace)
        loadOutbox()
        await pull()
    }
    func createFirstProfile(name: String) async {
        await run {
            guard let client = self.client, let session = try await self.validSession() else { return }
            let id = UUID().uuidString.lowercased()
            try await client.createProfile(session, id: id, name: name.isEmpty ? "الملف الشخصي" : name)
            try await self.loadProfiles()
            await self.choose(profile: id)
        }
    }
    /// Copies this iPhone's own library into the active profile. Matching titles take the iPhone's values.
    func importDeviceLibrary() async {
        guard let library, namespace != nil else { return }
        let records = library.deviceRecords()
        guard !records.isEmpty else { message = "لا توجد بيانات على هذا الجهاز لنقلها."; return }
        enqueue(records, force: true)
        await flush()
        await pull()
        message = "نُقلت مكتبة هذا الجهاز إلى الملف."
    }

    // MARK: Sync

    /// Called when the app becomes active or goes to the background.
    func scene(active: Bool) {
        if active { activate(); Task { await pull() } }
        else { pollTask?.cancel(); Task { await flush() } }
    }
    private func activate() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(180))
                await self?.pull()
            }
        }
    }

    func refreshAll() async {
        guard session != nil else { return }
        await run {
            guard let session = try await self.validSession() else { return }
            if session.verified { try await self.loadProfiles() }
        }
    }

    func pull() async {
        guard let client, let namespace, let profileID, let current = try? await validSession() else { return }
        await flush()
        do {
            let records = try await client.records(current, profile: profileID)
            guard namespace == self.namespace else { return }
            known = Dictionary(records.map { ($0.key, $0.value) }, uniquingKeysWith: { first, _ in first })
            // Unsent edits from this iPhone stay until they reach the server.
            var merged = records.filter { outbox[$0.key] == nil }
            merged += outbox.map { CloudRecord(key: $0.key, value: $0.value, updatedAt: Date()) }
            library?.applyRemote(merged)
            status = "تمت المزامنة " + Self.time.string(from: Date())
        } catch is CancellationError {
        } catch {
            status = "تعذّرت المزامنة: " + error.localizedDescription
        }
    }

    private func enqueue(_ changes: [String: String], force: Bool = false) {
        guard namespace != nil else { return }
        // Items of external providers stay on this iPhone: the Android apps do not know those sources.
        let changes = changes.filter { !$0.key.contains("ext:") }
        var urgent = false
        for (key, value) in changes where force || known[key] != value {
            outbox[key] = value; known[key] = value
            if !key.hasPrefix("playback/") { urgent = true }
        }
        saveOutbox()
        guard !outbox.isEmpty else { return }
        // Lists go out within seconds; playback positions are batched, as on Android.
        if urgent || flushTask == nil {
            flushTask?.cancel()
            flushTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(urgent ? 2 : 30))
                guard !Task.isCancelled else { return }
                await self?.flush()
            }
        }
    }

    func flush() async {
        flushTask?.cancel(); flushTask = nil
        guard let client, let profileID, !outbox.isEmpty, namespace != nil,
              let current = try? await validSession() else { return }
        let pending = outbox
        // The account or profile can change while the write is under way; the sent records then belong to the
        // outbox that was saved for the old profile, never to the one now loaded.
        let sentFrom = namespace, sentKey = outboxKey
        do {
            try await client.write(pending, session: current, profile: profileID)
            if namespace == sentFrom {
                for (key, value) in pending where outbox[key] == value { outbox[key] = nil }
                saveOutbox()
            } else if let sentKey {
                var stored = defaults.dictionary(forKey: sentKey) as? [String: String] ?? [:]
                for (key, value) in pending where stored[key] == value { stored[key] = nil }
                if stored.isEmpty { defaults.removeObject(forKey: sentKey) } else { defaults.set(stored, forKey: sentKey) }
            }
        } catch {
            status = "التغييرات محفوظة على الجهاز وستُرسل عند الاتصال."
        }
    }

    // MARK: Helpers

    private func run(_ work: @escaping () async throws -> Void) async {
        busy = true; message = nil
        defer { busy = false }
        do { try await work() }
        catch is CancellationError {}
        catch { message = error.localizedDescription }
    }

    private func validSession() async throws -> CloudSession? {
        guard let client, let session else { return nil }
        guard session.needsRefresh else { return session }
        do {
            let fresh = try await client.refresh(session)
            store(fresh)
            return fresh
        } catch CloudError.auth(let code) {
            // The account was removed or the password changed elsewhere.
            // Nothing can be sent with this session any more, so it ends without flushing (which would refresh again).
            if ["TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND", "USER_DISABLED"].contains(code) { endSession() }
            throw CloudError.auth(code)
        }
    }

    private func store(_ session: CloudSession) {
        let changedUser = self.session?.uid != session.uid
        self.session = session
        Keychain.save(session)
        if changedUser { profileID = defaults.string(forKey: profileKey(session.uid)) }
        library?.switchTo(account: namespace)
        loadOutbox()
    }

    private func loadProfiles() async throws {
        guard let client, let session = try await validSession() else { return }
        profiles = try await client.profiles(session)
        if let profileID, !profiles.contains(where: { $0.id == profileID }) { self.profileID = nil }
        // A single profile is chosen without asking.
        if self.profileID == nil, profiles.count == 1 { await choose(profile: profiles[0].id); return }
        library?.switchTo(account: namespace)
        loadOutbox()
        await pull()
    }

    private func profileKey(_ uid: String) -> String { "ios.cloud.profile.\(uid)" }
    private var outboxKey: String? { namespace.map { "ios.cloud.\($0).outbox.v1" } }
    private func loadOutbox() {
        outbox = outboxKey.flatMap { defaults.dictionary(forKey: $0) as? [String: String] } ?? [:]
    }
    private func saveOutbox() {
        guard let outboxKey else { return }
        if outbox.isEmpty { defaults.removeObject(forKey: outboxKey) } else { defaults.set(outbox, forKey: outboxKey) }
    }
    private static let time: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()
}

/// The sign-in tokens live in the Keychain, not in the app's settings file.
enum Keychain {
    private static let service = "com.cinemaplus.rebuilt.ios.cloud"
    private static let account = "session"
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }
    static func load() -> CloudSession? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(CloudSession.self, from: data)
    }
    static func save(_ session: CloudSession?) {
        SecItemDelete(query as CFDictionary)
        guard let session, let data = try? JSONEncoder().encode(session) else { return }
        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(item as CFDictionary, nil)
    }
}
