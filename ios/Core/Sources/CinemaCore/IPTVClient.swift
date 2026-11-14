import Foundation

public protocol IPTVTransport: Sendable { func data(from url: URL) async throws -> Data }

public struct IPTVClient: Sendable {
    private let transport: any IPTVTransport
    public init(transport: any IPTVTransport = IPTVURLSessionTransport()) { self.transport = transport }
    public func load(_ source: IPTVSource) async throws -> IPTVCatalog {
        _ = try IPTVSource(id: source.id, name: source.name, kind: source.kind, address: source.address,
            username: source.username, password: source.password, output: source.output, allowHTTP: source.allowHTTP)
        try Task.checkCancellation()
        switch source.kind {
        case .playlist:
            return try IPTVParser.playlist(await transport.data(from: source.address), source: source)
        case .xtream:
            let authentication = try await transport.data(from: Self.apiURL(source: source))
            try IPTVParser.xtreamAuthentication(authentication)
            async let categories = optionalCategories(source)
            async let channels = transport.data(from: Self.apiURL(source: source, action: "get_live_streams"))
            let (data, groups) = try await (channels, categories)
            try Task.checkCancellation()
            return try IPTVParser.xtreamChannels(data, categories: groups, source: source)
        }
    }
    private func optionalCategories(_ source: IPTVSource) async -> Data? {
        try? await transport.data(from: Self.apiURL(source: source, action: "get_live_categories"))
    }
    public static func apiURL(source: IPTVSource, action: String? = nil) throws -> URL {
        guard source.kind == .xtream else { throw IPTVError.invalidServer }
        let base = source.address.appendingPathComponent("player_api.php")
        guard var parts = URLComponents(url: base, resolvingAgainstBaseURL: false) else { throw IPTVError.invalidServer }
        parts.queryItems = [URLQueryItem(name: "username", value: source.username), URLQueryItem(name: "password", value: source.password)]
        if let action { parts.queryItems?.append(URLQueryItem(name: "action", value: action)) }
        // PHP's query decoder treats a literal '+' as a space; account values must preserve it.
        parts.percentEncodedQuery = parts.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let url = parts.url else { throw IPTVError.invalidServer }
        return try IPTVURLPolicy.validate(url, allowHTTP: source.allowHTTP)
    }
    public static func liveURL(source: IPTVSource, streamID: String) throws -> URL {
        guard source.kind == .xtream, streamID.range(of: "^[1-9][0-9]*$", options: .regularExpression) != nil,
              var parts = URLComponents(url: source.address, resolvingAgainstBaseURL: false) else { throw IPTVError.invalidServer }
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        guard let user = source.username.addingPercentEncoding(withAllowedCharacters: unreserved),
              let password = source.password.addingPercentEncoding(withAllowedCharacters: unreserved) else { throw IPTVError.invalidServer }
        parts.percentEncodedPath = parts.percentEncodedPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .split(separator: "/").map(String.init).reduce("") { $0 + "/" + $1 }
            + "/live/" + user + "/" + password + "/" + streamID + "." + source.output.rawValue
        parts.query = nil; parts.fragment = nil
        guard let url = parts.url else { throw IPTVError.invalidServer }
        return try IPTVURLPolicy.validate(url, allowHTTP: source.allowHTTP)
    }
}
