import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The iOS app of the shared Firebase project (from GoogleService-Info.plist).
public struct CloudConfig: Sendable {
    public let apiKey: String
    public let projectID: String
    public let bundleID: String
    public init(apiKey: String, projectID: String, bundleID: String) {
        self.apiKey = apiKey; self.projectID = projectID; self.bundleID = bundleID
    }
}

public struct CloudSession: Codable, Sendable, Equatable {
    public var uid: String
    public var email: String
    public var idToken: String
    public var refreshToken: String
    public var expiresAt: Date
    public var verified: Bool
    public var needsRefresh: Bool { expiresAt.timeIntervalSinceNow < 120 }
}

public struct CloudProfile: Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let avatar: String
}

public struct CloudRecord: Sendable, Equatable {
    public let key: String
    public let value: String
    public let updatedAt: Date?
    public init(key: String, value: String, updatedAt: Date?) { self.key = key; self.value = value; self.updatedAt = updatedAt }
}

public enum CloudError: LocalizedError, Equatable {
    case auth(String), http(Int), network(Int), invalidResponse, signedOut
    public var errorDescription: String? {
        switch self {
        case .auth(let code):
            switch code {
            case "EMAIL_EXISTS": return "هذا البريد مسجّل مسبقاً. سجّل الدخول بدلاً من إنشاء حساب."
            case "INVALID_LOGIN_CREDENTIALS", "INVALID_PASSWORD", "EMAIL_NOT_FOUND": return "البريد أو كلمة المرور غير صحيحة."
            case "WEAK_PASSWORD": return "كلمة المرور ضعيفة. استخدم 6 أحرف على الأقل."
            case "INVALID_EMAIL", "MISSING_EMAIL": return "صيغة البريد الإلكتروني غير صحيحة."
            case "MISSING_PASSWORD": return "أدخل كلمة المرور."
            case "TOO_MANY_ATTEMPTS_TRY_LATER": return "محاولات كثيرة. انتظر قليلاً ثم أعد المحاولة."
            case "USER_DISABLED": return "هذا الحساب موقوف."
            case "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_NOT_FOUND", "INVALID_ID_TOKEN", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN":
                return "انتهت جلسة الدخول. سجّل الدخول من جديد."
            default:
                if code.contains("API_KEY") || code.contains("API key") || code.contains("blocked") {
                    return "إعداد Firebase لنسخة الآيفون غير مكتمل (المفتاح أو معرّف التطبيق)."
                }
                return "تعذّر إتمام العملية (\(code))."
            }
        case .http(let code):
            return code == 403 ? "رفضت قاعدة البيانات الطلب. تأكد من تأكيد بريدك ثم أعد المحاولة." : "تعذّر الاتصال بالمزامنة (HTTP \(code))."
        case .network(let code): return CinemaError.networkMessage(code) + " (رمز \(code))"
        case .invalidResponse: return "استجابة غير متوقعة من خدمة المزامنة."
        case .signedOut: return "سجّل الدخول أولاً."
        }
    }
}

/// Firebase Authentication and Firestore over their REST APIs, so the app needs no Firebase SDK. Paths, rules
/// and record format are the same as the Android app's.
public struct CloudClient: Sendable {
    public let config: CloudConfig
    public init(config: CloudConfig) { self.config = config }

    // MARK: Authentication

    public func signUp(email: String, password: String) async throws -> CloudSession {
        try session(try await identity("accounts:signUp", ["email": email, "password": password, "returnSecureToken": true]), email: email)
    }
    public func signIn(email: String, password: String) async throws -> CloudSession {
        var result = try session(try await identity("accounts:signInWithPassword", ["email": email, "password": password, "returnSecureToken": true]), email: email)
        result.verified = try await isVerified(result)
        return result
    }
    public func sendVerification(_ session: CloudSession) async throws {
        _ = try await identity("accounts:sendOobCode", ["requestType": "VERIFY_EMAIL", "idToken": session.idToken])
    }
    public func sendPasswordReset(email: String) async throws {
        _ = try await identity("accounts:sendOobCode", ["requestType": "PASSWORD_RESET", "email": email])
    }
    public func isVerified(_ session: CloudSession) async throws -> Bool {
        let result = try await identity("accounts:lookup", ["idToken": session.idToken]) as? [String: Any]
        let user = (result?["users"] as? [[String: Any]])?.first
        return (user?["emailVerified"] as? Bool) ?? false
    }
    /// A fresh ID token. After the address is confirmed, the new token also carries the verified claim the
    /// database rules require.
    public func refresh(_ old: CloudSession) async throws -> CloudSession {
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "grant_type", value: "refresh_token"), URLQueryItem(name: "refresh_token", value: old.refreshToken)]
        let url = URL(string: "https://securetoken.googleapis.com/v1/token?key=\(config.apiKey)")!
        let body = Data((components.percentEncodedQuery ?? "").utf8)
        guard let json = try await send(url, method: "POST", body: body, contentType: "application/x-www-form-urlencoded") as? [String: Any],
              let token = json["id_token"] as? String, let refresh = json["refresh_token"] as? String else { throw CloudError.invalidResponse }
        var result = old
        result.idToken = token; result.refreshToken = refresh
        result.expiresAt = Date().addingTimeInterval(Double(json["expires_in"] as? String ?? "3600") ?? 3600)
        return result
    }

    // MARK: Firestore

    private var database: String { "projects/\(config.projectID)/databases/(default)/documents" }
    private var documentsURL: String { "https://firestore.googleapis.com/v1/" + database }

    public func profiles(_ session: CloudSession) async throws -> [CloudProfile] {
        let documents = try await list("users/\(session.uid)/profiles", session: session)
        return documents.compactMap { document -> CloudProfile? in
            guard let name = document["name"] as? String, let fields = document["fields"] as? [String: Any] else { return nil }
            if Self.bool(fields["deleted"]) == true { return nil }
            let id = String(name.split(separator: "/").last ?? "")
            let title = Self.string(fields["name"]) ?? ""
            guard !id.isEmpty, !title.isEmpty else { return nil }
            return CloudProfile(id: id, name: title, avatar: Self.string(fields["avatar"]) ?? "avatar-0")
        }
    }

    /// A first profile for an account that has none yet, in the shape the Android app writes.
    public func createProfile(_ session: CloudSession, id: String, name: String) async throws {
        let write: [String: Any] = ["update": [
            "name": "\(database)/users/\(session.uid)/profiles/\(id)",
            "fields": ["name": ["stringValue": name], "color": ["integerValue": "4294937131"],
                       "avatar": ["stringValue": "avatar-0"], "deleted": ["booleanValue": false]]
        ]]
        try await commit([write], session: session)
    }

    public func records(_ session: CloudSession, profile: String) async throws -> [CloudRecord] {
        try await list("users/\(session.uid)/profiles/\(profile)/entries", session: session).compactMap { document in
            guard let fields = document["fields"] as? [String: Any], let key = Self.string(fields["key"]),
                  let value = Self.string(fields["value"]) else { return nil }
            return CloudRecord(key: key, value: value, updatedAt: Self.date(fields["updatedAt"]))
        }
    }

    /// Writes whole records (an empty value is a removal) with the server's time, as the rules require.
    public func write(_ records: [String: String], session: CloudSession, profile: String) async throws {
        let writes: [[String: Any]] = records.map { key, value in
            ["update": ["name": "\(database)/users/\(session.uid)/profiles/\(profile)/entries/\(SHA256.hex(key))",
                        "fields": ["key": ["stringValue": key], "value": ["stringValue": value]]],
             "updateTransforms": [["fieldPath": "updatedAt", "setToServerValue": "REQUEST_TIME"]]]
        }
        for start in stride(from: 0, to: writes.count, by: 200) {
            try await commit(Array(writes[start..<min(start + 200, writes.count)]), session: session)
        }
    }

    private func commit(_ writes: [[String: Any]], session: CloudSession) async throws {
        let url = URL(string: "https://firestore.googleapis.com/v1/\(database):commit")!
        _ = try await send(url, method: "POST", body: try JSONSerialization.data(withJSONObject: ["writes": writes]), token: session.idToken)
    }

    private func list(_ path: String, session: CloudSession) async throws -> [[String: Any]] {
        var documents: [[String: Any]] = []
        var pageToken: String?
        repeat {
            var components = URLComponents(string: "\(documentsURL)/\(path)")!
            components.queryItems = [URLQueryItem(name: "pageSize", value: "300")] + (pageToken.map { [URLQueryItem(name: "pageToken", value: $0)] } ?? [])
            let json = try await send(components.url!, method: "GET", body: nil, token: session.idToken) as? [String: Any]
            documents += json?["documents"] as? [[String: Any]] ?? []
            pageToken = json?["nextPageToken"] as? String
        } while pageToken != nil && documents.count < 20_000
        return documents
    }

    // MARK: Transport

    private func identity(_ method: String, _ body: [String: Any]) async throws -> Any {
        let url = URL(string: "https://identitytoolkit.googleapis.com/v1/\(method)?key=\(config.apiKey)")!
        return try await send(url, method: "POST", body: try JSONSerialization.data(withJSONObject: body))
    }

    private func send(_ url: URL, method: String, body: Data?, token: String? = nil, contentType: String = "application/json") async throws -> Any {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 25
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        // The iOS API key is restricted to this app; Google checks the bundle identifier header.
        request.setValue(config.bundleID, forHTTPHeaderField: "X-Ios-Bundle-Identifier")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let data: Data, response: URLResponse
        do { (data, response) = try await URLSession.shared.data(for: request) }
        catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            throw CloudError.network(error.code.rawValue)
        }
        guard let http = response as? HTTPURLResponse else { throw CloudError.invalidResponse }
        let json: Any? = data.isEmpty ? [String: Any]() : (try? JSONSerialization.jsonObject(with: data))
        guard (200..<300).contains(http.statusCode) else {
            if let message = ((json as? [String: Any])?["error"] as? [String: Any])?["message"] as? String,
               url.host?.contains("googleapis.com") == true, !url.host!.hasPrefix("firestore") {
                throw CloudError.auth(Self.errorCode(message))
            }
            throw CloudError.http(http.statusCode)
        }
        return json ?? [String: Any]()
    }

    // MARK: Parsing

    /// "WEAK_PASSWORD : Password should be at least 6 characters" → "WEAK_PASSWORD".
    static func errorCode(_ message: String) -> String {
        String(message.split(separator: ":").first ?? Substring(message)).trimmingCharacters(in: .whitespaces)
    }
    private func session(_ value: Any, email: String) throws -> CloudSession {
        guard let json = value as? [String: Any], let token = json["idToken"] as? String,
              let refresh = json["refreshToken"] as? String, let uid = json["localId"] as? String else { throw CloudError.invalidResponse }
        let expires = Double(json["expiresIn"] as? String ?? "3600") ?? 3600
        return CloudSession(uid: uid, email: (json["email"] as? String) ?? email, idToken: token, refreshToken: refresh,
                            expiresAt: Date().addingTimeInterval(expires), verified: false)
    }
    static func string(_ field: Any?) -> String? { (field as? [String: Any])?["stringValue"] as? String }
    static func bool(_ field: Any?) -> Bool? { (field as? [String: Any])?["booleanValue"] as? Bool }
    static func date(_ field: Any?) -> Date? {
        guard let text = (field as? [String: Any])?["timestampValue"] as? String else { return nil }
        let precise = ISO8601DateFormatter()
        precise.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = precise.date(from: text) { return date }
        // Firestore can send nanoseconds; keep milliseconds for the formatter.
        let trimmed = text.replacingOccurrences(of: "(\\.\\d{3})\\d+", with: "$1", options: .regularExpression)
        return precise.date(from: trimmed) ?? ISO8601DateFormatter().date(from: text)
    }
}
