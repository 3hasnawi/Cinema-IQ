import TVServices

/// The system extension and native validation call the same bounded loader.
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping ((any TVTopShelfContent)?) -> Void) {
        Task { completionHandler(await TVShelfContentLoader.load()) }
    }
}
