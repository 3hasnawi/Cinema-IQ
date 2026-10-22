import SwiftUI

@main struct CinemaIQApp: App {
    @StateObject private var library = LibraryStore()
    var body: some Scene {
        WindowGroup {
            // The system font (SF Arabic) and Dynamic Type throughout.
            RootView()
                .environmentObject(library)
                .environment(\.layoutDirection, .rightToLeft)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}
