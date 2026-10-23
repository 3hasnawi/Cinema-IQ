import SwiftUI

@main struct CinemaIQApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var cloud = CloudSync()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            // The system font (SF Arabic) and Dynamic Type throughout.
            RootView()
                .environmentObject(library)
                .environmentObject(cloud)
                .environment(\.layoutDirection, .rightToLeft)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .task { cloud.start(library: library) }
                .onChange(of: phase) { _, value in
                    if value == .active { cloud.scene(active: true) } else if value == .background { cloud.scene(active: false) }
                }
        }
    }
}
