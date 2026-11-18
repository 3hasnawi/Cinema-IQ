import Foundation
import TVServices
import CinemaCore

enum TVShelfRenderer {
    static func content(_ snapshot: TopShelfSnapshot) -> (any TVTopShelfContent)? {
        guard snapshot.usable(), !snapshot.entries.isEmpty else { return nil }
        if snapshot.continuing.isEmpty, snapshot.featured.allSatisfy(\.landscape) {
            let items = snapshot.featured.map { value in
                let item = TVTopShelfCarouselItem(identifier: value.mediaID)
                item.title = value.title; item.contextTitle = "أفلام مميزة"
                item.summary = value.summary
                item.setImageURL(value.imageURL, for: .screenScale1x)
                item.setImageURL(value.imageURL, for: .screenScale2x)
                item.displayAction = TopShelfRoute(mediaID: value.mediaID, action: .display).url.map(TVTopShelfAction.init(url:))
                item.playAction = TopShelfRoute(mediaID: value.mediaID, action: .play).url.map(TVTopShelfAction.init(url:))
                return item
            }
            return TVTopShelfCarouselContent(style: .details, items: items)
        }
        var sections: [TVTopShelfItemCollection<TVTopShelfSectionedItem>] = []
        for (title, values) in [("تابع المشاهدة", snapshot.continuing), ("أفلام مميزة", snapshot.featured)] where !values.isEmpty {
            let items = values.map { value in
                let item = TVTopShelfSectionedItem(identifier: value.mediaID)
                item.title = value.subtitle.isEmpty ? value.title : value.title + " · " + value.subtitle
                item.imageShape = value.landscape ? .HDTV : .poster
                item.playbackProgress = value.progress
                item.setImageURL(value.imageURL, for: .screenScale1x)
                item.setImageURL(value.imageURL, for: .screenScale2x)
                item.displayAction = TopShelfRoute(mediaID: value.mediaID, action: .display).url.map(TVTopShelfAction.init(url:))
                item.playAction = TopShelfRoute(mediaID: value.mediaID, action: .play).url.map(TVTopShelfAction.init(url:))
                return item
            }
            let section = TVTopShelfItemCollection(items: items); section.title = title; sections.append(section)
        }
        return TVTopShelfSectionedContent(sections: sections)
    }
}
