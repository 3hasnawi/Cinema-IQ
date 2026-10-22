import Foundation
import SwiftSoup

public struct VoduKey: Sendable {
    public let parent: String
    public let episode: String?
    public init(_ id: String) throws {
        guard id.range(of: "^vodu:[1-9][0-9]*(?::[1-9][0-9]*)?$", options: .regularExpression) != nil else { throw CinemaError.invalidID }
        let parts = id.split(separator: ":").map(String.init)
        parent = parts[1]; episode = parts.count == 3 ? parts[2] : nil
    }
}
public struct VoduClient: Sendable {
    public init() {}
    public static func pageURL(_ parameters: [String: String]) -> URL {
        var url = URLComponents(string: "https://movie.vodu.me/index.php")!
        url.queryItems = parameters.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return url.url!
    }
    private func page(_ parameters: [String: String]) async throws -> String {
        let data = try await SourceHTTP.read(Self.pageURL(parameters), expectedHost: "movie.vodu.me")
        guard let html = String(data: data, encoding: .utf8) else { throw CinemaError.invalidResponse }
        return html
    }
    public func catalog(series: Bool, query: String, page: Int) async throws -> [Media] {
        var params = ["do": "list", "type": series ? "1" : "0", "page": String(page)]
        if !query.isEmpty { params["title"] = query }
        return try VoduParser.catalog(await self.page(params), series: series)
    }
    public func detail(_ media: Media) async throws -> (Media, [Season]) {
        let key = try VoduKey(media.id)
        return try VoduParser.detail(await page(["do": "view", "type": "post", "id": key.parent]), media: media)
    }
    public func streams(_ id: String) async throws -> [StreamChoice] {
        let key = try VoduKey(id)
        return try VoduParser.streams(await page(["do": "view", "type": "post", "id": key.parent]), id: id)
    }
}
public enum VoduParser {
    private static let base = "https://movie.vodu.me/"
    private static func document(_ html: String) throws -> Document { try SwiftSoup.parse(html, base) }
    private static func url(_ raw: String) -> String {
        guard !raw.isEmpty, let url = URL(string: raw, relativeTo: URL(string: base))?.absoluteURL,
              url.scheme == "https", url.host != nil else { return "" }
        return url.absoluteString
    }
    private static func image(_ element: Element) throws -> String {
        guard let image = try element.select("img[src]").first() else { return "" }
        return url(try image.attr("src"))
    }
    public static func catalog(_ html: String, series: Bool) throws -> [Media] {
        let doc = try document(html)
        // A blocked or redirected landing page must not be mistaken for a successful empty catalog.
        guard try !doc.select(".pagehead, .myitem, .pagination").isEmpty() else { throw CinemaError.invalidResponse }
        return try doc.select(".mytitle a[href]").array().compactMap { link -> Media? in
            let raw = try link.attr("href")
            guard let linkURL = URL(string: raw, relativeTo: URL(string: base)),
                  let id = URLComponents(url: linkURL, resolvingAgainstBaseURL: true)?.queryItems?.first(where: { $0.name == "id" })?.value,
                  (try? VoduKey("vodu:" + id)) != nil else { return nil }
            let card = link.parents().array().first(where: { $0.hasClass("myitem") }) ?? link
            var media = Media(id: "vodu:" + id, title: try link.text())
            media.poster = try image(card); media.backdrop = media.poster; media.isSeries = series
            return media
        }.uniqueMedia()
    }
    private static func anchor(_ doc: Document, id: String) throws -> Element? {
        let key = try VoduKey(id)
        if let episode = key.episode {
            return try doc.select(".episodeitem a.play").array().first { try $0.attr("data-id") == episode }
        }
        return try doc.select(".postcontent a.play").array().first { link in
            !link.parents().array().contains { $0.hasClass("episodeitem") }
        }
    }
    private static func streamList(_ anchor: Element?) throws -> [StreamChoice] {
        guard let anchor else { return [] }
        var seen = Set<String>()
        return try [("data-url", "720p"), ("data-url1080", "1080p"), ("data-url4k", "2160p"), ("data-url360", "360p")].compactMap { attribute, quality in
            guard let stream = StreamChoice(url(try anchor.attr(attribute)), quality: quality), seen.insert(stream.id).inserted else { return nil }
            return stream
        }
    }
    public static func streams(_ html: String, id: String) throws -> [StreamChoice] {
        try streamList(anchor(document(html), id: id))
    }
    public static func detail(_ html: String, media: Media) throws -> (Media, [Season]) {
        let doc = try document(html), key = try VoduKey(media.id)
        guard let content = try doc.select(".postcontent").first(), let heading = try content.select("h1").first() else { throw CinemaError.invalidResponse }
        let seasons = try seasons(doc, parent: key.parent)
        if key.episode != nil {
            guard let episode = seasons.flatMap(\.episodes).first(where: { $0.id == media.id }) else { throw CinemaError.invalidResponse }
            return (episode, seasons)
        }
        var result = media; result.title = try heading.text(); result.isSeries = media.isSeries || !seasons.isEmpty
        let poster = try image(content)
        if !poster.isEmpty { result.poster = poster; result.backdrop = poster }
        var metadata: [String: String] = [:]
        for heading in try content.select("h3").array() {
            let text = try heading.text()
            if let span = try heading.select("span").first() {
                let label = try span.text()
                metadata[label.trimmingCharacters(in: CharacterSet(charactersIn: ": "))] = String(text.dropFirst(label.count)).trimmingCharacters(in: .whitespacesAndNewlines)
            }
            if text.trimmingCharacters(in: CharacterSet(charactersIn: ": ")).lowercased() == "synopsis" {
                var next = try heading.nextElementSibling()
                while let element = next {
                    if element.tagName() == "h4" { result.overview = try element.text(); break }
                    next = try element.nextElementSibling()
                }
            }
        }
        result.year = metadata["Year"] ?? result.year; result.duration = metadata["Runtime"] ?? result.duration
        result.rating = metadata["IMdB Rating"] ?? result.rating; result.quality = metadata["Quality"] ?? result.quality
        return (result, seasons)
    }
    private static func seasons(_ doc: Document, parent: String) throws -> [Season] {
        var labels: [String: String] = [:]
        for label in try doc.select(".customtabs .nav-tabs a[href]").array() { labels[try label.attr("href").replacingOccurrences(of: "#", with: "")] = try label.text() }
        let poster = try doc.select(".postcontent").first().map { try image($0) } ?? ""
        var episodes: [Media] = []
        for link in try doc.select(".episodeitem a.play").array() {
            let id = "vodu:\(parent):\(try link.attr("data-id"))"
            guard (try? VoduKey(id)) != nil else { continue }
            let title = try link.attr("data-title")
            let match = try NSRegularExpression(pattern: "S([0-9]+)E([0-9]+)", options: .caseInsensitive).firstMatch(in: title, range: NSRange(title.startIndex..., in: title))
            func number(_ group: Int) -> Int? { guard let match, let range = Range(match.range(at: group), in: title) else { return nil }; return Int(title[range]) }
            let tab = link.parents().array().first { $0.id().hasPrefix("tab-") }?.id() ?? ""
            let label = labels[tab] ?? ""
            let seasonMatch = try NSRegularExpression(pattern: "Season\\s*([0-9]+)", options: .caseInsensitive).firstMatch(in: label, range: NSRange(label.startIndex..., in: label))
            let labelSeason = seasonMatch.flatMap { Range($0.range(at: 1), in: label) }.flatMap { Int(label[$0]) }
            var media = Media(id: id, title: title); media.poster = poster; media.backdrop = poster
            media.seriesID = "vodu:" + parent; media.isSeries = true
            media.season = labelSeason ?? number(1) ?? 1; media.episode = number(2) ?? 0; media.episodeTitle = title
            episodes.append(media)
        }
        return Dictionary(grouping: episodes.uniqueMedia(), by: \.season).map { number, items in Season(id: number, episodes: items.sorted { $0.episode < $1.episode }) }.sorted { $0.id < $1.id }
    }
}
